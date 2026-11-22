package cc.hafa.inbox.pgp

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import cc.hafa.inbox.data.safeFileName
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val KEYSTORE = "AndroidKeyStore"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val KEY_BITS = 256
private const val IV_BYTES = 12
private const val TAG_BITS = 128

/**
 * An AES-256-GCM key in Android Keystore that never leaves it, sealing small values on this device.
 *
 * The key needs no user authentication, so notifications can decrypt in the background
 * (docs/pgp-contract.md "The unlocked key on the device"). With [strongBox] it lives in the
 * StrongBox chip when the phone has one. Sealed values are the 12-byte nonce Keystore picked, then
 * the ciphertext with its tag. [delete] destroys the key, so everything sealed with it is gone.
 */
class DeviceVault(private val alias: String, private val strongBox: Boolean) {
    private fun keyStore(): KeyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    private fun generate(useStrongBox: Boolean): SecretKey {
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            .setUserAuthenticationRequired(false)
            .setIsStrongBoxBacked(useStrongBox)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(spec)
            generateKey()
        }
    }

    private fun key(create: Boolean): SecretKey? = synchronized(KEYS) {
        KEYS[alias]?.let { return it }
        val found = (keyStore().getKey(alias, null) as? SecretKey) ?: when {
            !create -> return null
            strongBox -> try {
                generate(true)
            } catch (_: StrongBoxUnavailableException) {
                // No StrongBox on this phone; the TEE-backed key is the standard fallback.
                generate(false)
            }
            else -> generate(false)
        }
        KEYS[alias] = found
        found
    }

    // Set once the owner is done with the vault (sign-out), so a late write can't make the key again.
    @Volatile
    private var closed = false

    /** Whether the key exists. */
    fun exists(): Boolean = key(create = false) != null

    /** Encrypt [plain], making the key on first use unless [close] was called; throws when there is no key to use. */
    fun seal(plain: ByteArray): ByteArray {
        val key = key(create = !closed) ?: throw IllegalStateException("the device key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plain)
    }

    /** Stop making the key: later seals use it only if it still exists. */
    fun close() {
        closed = true
    }

    /** Decrypt what [seal] made; throws when the key is gone or the value was changed. */
    fun open(sealed: ByteArray): ByteArray {
        val key = key(create = false) ?: throw IllegalStateException("the device key is gone")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 0, IV_BYTES))
        return cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES)
    }

    /** Destroy the key. */
    fun delete() {
        synchronized(KEYS) {
            KEYS.remove(alias)
            runCatching { keyStore().deleteEntry(alias) }
        }
    }

    companion object {
        /** The vault holding an account's unlocked private key. */
        fun privateKey(uid: String) = DeviceVault("pgp-key-${safeFileName(uid)}", strongBox = true)

        /** The vault holding an account's decrypted mail cache. */
        fun mailCache(uid: String) = DeviceVault("pgp-cache-${safeFileName(uid)}", strongBox = false)

        /** The key that seals drafts kept on the device: their text and files. */
        fun drafts(uid: String) = DeviceVault("drafts-${safeFileName(uid)}", strongBox = false)

        private val ALIAS_PREFIXES = listOf("pgp-key-", "pgp-cache-", "drafts-")

        // Keys already loaded from Keystore, by alias; shared by every vault of an alias so a delete clears it for all.
        private val KEYS = HashMap<String, SecretKey>()

        /** Delete every account's vault key on this device but [keep] (a parked account's drafts vault). */
        fun deleteAll(keep: DeviceVault? = null) {
            synchronized(KEYS) {
                KEYS.keys.retainAll { it == keep?.alias }
                runCatching {
                    val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
                    for (alias in store.aliases().toList()) {
                        if (alias != keep?.alias && ALIAS_PREFIXES.any(alias::startsWith)) store.deleteEntry(alias)
                    }
                }
            }
        }
    }
}
