package cc.hafa.inbox.pgp

import cc.hafa.inbox.core.pgp.RecoveryKdf
import cc.hafa.inbox.core.pgp.RecoverySealed
import com.google.firebase.Timestamp
import java.util.Date

/** One passkey's sealed copy of the private key (`users/{uid}.pgp.sealed[]`). */
data class SealedCopy(
    /** the passkey's credential id, base64url */
    val credentialId: String,
    /** its PRF salt, base64url */
    val salt: String,
    /** the private key sealed with the passkey's PRF output */
    val sealedPrivateKey: String,
    /** label shown in Settings */
    val name: String,
    /** when it was added, epoch milliseconds */
    val addedAt: Long,
)

/** `users/{uid}.pgp`: the public key and the private key's sealed copies (docs/pgp-contract.md). */
data class PgpRecord(
    /** armored public key */
    val publicKey: String,
    /** one per passkey, at most [MAX_PASSKEYS] */
    val sealed: List<SealedCopy>,
    /** the copy the recovery phrase opens */
    val recoverySealed: RecoverySealed,
) {
    /** The record as Firestore stores it, written whole. */
    fun toFirestore(): Map<String, Any> = mapOf(
        "publicKey" to publicKey,
        "sealed" to sealed.map {
            mapOf(
                "credentialId" to it.credentialId,
                "salt" to it.salt,
                "sealedPrivateKey" to it.sealedPrivateKey,
                "name" to it.name.take(MAX_NAME),
                "addedAt" to Timestamp(Date(it.addedAt)),
            )
        },
        "recoverySealed" to mapOf(
            "salt" to recoverySealed.salt,
            "sealedPrivateKey" to recoverySealed.sealedPrivateKey,
            "kdf" to mapOf(
                "name" to recoverySealed.kdf.name,
                "memoryKiB" to recoverySealed.kdf.memoryKiB.toLong(),
                "iterations" to recoverySealed.kdf.iterations.toLong(),
                "parallelism" to recoverySealed.kdf.parallelism.toLong(),
            ),
        ),
    )

    companion object {
        /** Most passkeys the rules allow. */
        const val MAX_PASSKEYS = 8

        /** Longest passkey label the rules allow. */
        const val MAX_NAME = 100

        private fun int(value: Any?): Int? = (value as? Number)?.toInt()

        /** Read the `pgp` field of `users/{uid}`; null when absent or not well formed. */
        fun parse(raw: Any?): PgpRecord? {
            val map = raw as? Map<*, *> ?: return null
            val publicKey = map["publicKey"] as? String ?: return null
            val recovery = map["recoverySealed"] as? Map<*, *> ?: return null
            val kdf = recovery["kdf"] as? Map<*, *> ?: return null
            val sealed = (map["sealed"] as? List<*>).orEmpty().mapNotNull { entry ->
                val copy = entry as? Map<*, *> ?: return@mapNotNull null
                SealedCopy(
                    credentialId = copy["credentialId"] as? String ?: return@mapNotNull null,
                    salt = copy["salt"] as? String ?: return@mapNotNull null,
                    sealedPrivateKey = copy["sealedPrivateKey"] as? String ?: return@mapNotNull null,
                    name = copy["name"] as? String ?: "",
                    addedAt = (copy["addedAt"] as? Timestamp)?.toDate()?.time ?: 0L,
                )
            }
            return PgpRecord(
                publicKey = publicKey,
                sealed = sealed,
                recoverySealed = RecoverySealed(
                    salt = recovery["salt"] as? String ?: return null,
                    sealedPrivateKey = recovery["sealedPrivateKey"] as? String ?: return null,
                    kdf = RecoveryKdf(
                        name = kdf["name"] as? String ?: return null,
                        memoryKiB = int(kdf["memoryKiB"]) ?: return null,
                        iterations = int(kdf["iterations"]) ?: return null,
                        parallelism = int(kdf["parallelism"]) ?: return null,
                    ),
                ),
            )
        }
    }
}
