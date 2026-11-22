package cc.hafa.inbox.core.pgp

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Sealing a private key under a 32-byte secret; a port of shared/src/pgp/seal.ts.

/** Length in bytes of the secret a seal is made with (a passkey's PRF output, or the Argon2id output). */
const val SEAL_SECRET_LENGTH = 32

/** Format version, the first byte of every sealed value. */
const val SEAL_VERSION = 1

/** HKDF `info`, binding the derived key to this use. */
const val SEAL_INFO = "inbox.hafa.cc/pgp-seal/v1"

/** AES-GCM nonce length in bytes. */
const val SEAL_IV_LENGTH = 12

private const val TAG_LENGTH = 16
private const val TAG_BITS = TAG_LENGTH * 8
private const val HMAC = "HmacSHA256"

/** Unsealing failed: wrong secret, or a damaged or unknown sealed value. */
class SealException(message: String) : Exception(message)

private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
    Mac.getInstance(HMAC).run {
        init(SecretKeySpec(key, HMAC))
        doFinal(data)
    }

/** HKDF-SHA256 with an empty salt, one 32-byte block: extract, then expand once. */
internal fun hkdfSha256(secret: ByteArray, info: ByteArray): ByteArray {
    val pseudoRandomKey = hmac(ByteArray(SEAL_SECRET_LENGTH), secret)
    return hmac(pseudoRandomKey, info + byteArrayOf(1))
}

private fun sealKey(secret: ByteArray): SecretKeySpec {
    if (secret.size != SEAL_SECRET_LENGTH) throw SealException("the secret must be $SEAL_SECRET_LENGTH bytes")
    return SecretKeySpec(hkdfSha256(secret, SEAL_INFO.toByteArray(Charsets.UTF_8)), "AES")
}

/**
 * Seal text (an armored private key) under a 32-byte secret.
 *
 * Key: HKDF-SHA256 of the secret with an empty salt and [SEAL_INFO], used as an AES-256-GCM key.
 * Result: base64 of [SEAL_VERSION] (one byte), the 12-byte nonce, then the ciphertext with its
 * 16-byte tag; no associated data. [iv] is for test vectors only; leave it out.
 */
fun seal(secret: ByteArray, plaintext: String, iv: ByteArray = randomBytes(SEAL_IV_LENGTH)): String {
    if (iv.size != SEAL_IV_LENGTH) throw SealException("bad nonce length")
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, sealKey(secret), GCMParameterSpec(TAG_BITS, iv))
    val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    return toBase64(byteArrayOf(SEAL_VERSION.toByte()) + iv + ciphertext)
}

/** Open a value made by [seal]; throws [SealException] for a wrong secret or damaged value. */
fun unseal(secret: ByteArray, sealed: String): String {
    val bytes = try {
        fromBase64(sealed)
    } catch (_: IllegalArgumentException) {
        throw SealException("the sealed value is not base64")
    }
    if (bytes.size < 1 + SEAL_IV_LENGTH + TAG_LENGTH) throw SealException("the sealed value is too short")
    if (bytes[0].toInt() != SEAL_VERSION) throw SealException("unknown seal version ${bytes[0]}")
    val key = sealKey(secret)
    val plaintext = try {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 1, SEAL_IV_LENGTH))
        cipher.doFinal(bytes, 1 + SEAL_IV_LENGTH, bytes.size - 1 - SEAL_IV_LENGTH)
    } catch (_: BadPaddingException) {
        throw SealException("wrong secret, or the sealed value is damaged")
    }
    return try {
        Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(plaintext)).toString()
    } catch (_: CharacterCodingException) {
        throw SealException("the sealed value is not text")
    }
}
