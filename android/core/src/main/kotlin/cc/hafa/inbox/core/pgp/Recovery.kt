package cc.hafa.inbox.core.pgp

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

// Recovery phrases; a port of shared/src/pgp/recovery.ts.

/** Words in a recovery phrase: 8 × log2(7776) ≈ 103.4 bits. */
const val PHRASE_WORDS = 8

/** Salt length in bytes for new recovery seals. */
const val RECOVERY_SALT_LENGTH = 16

/** Argon2id settings stored beside each recovery-sealed key, so they can change later. */
data class RecoveryKdf(
    /** always `argon2id` (version 0x13) */
    val name: String,
    /** memory in KiB */
    val memoryKiB: Int,
    /** passes over memory */
    val iterations: Int,
    /** lanes */
    val parallelism: Int,
)

/** Settings for new recovery seals: 64 MiB, 3 passes, 1 lane, 32-byte output (RFC 9106's second recommended option). */
val RECOVERY_KDF = RecoveryKdf(name = "argon2id", memoryKiB = 65_536, iterations = 3, parallelism = 1)

/** `users/{uid}.pgp.recoverySealed`. */
data class RecoverySealed(
    /** Argon2id salt, base64 */
    val salt: String,
    /** the private key sealed with the Argon2id output ([seal]) */
    val sealedPrivateKey: String,
    /** the Argon2id settings used */
    val kdf: RecoveryKdf,
)

/** The EFF large wordlist (shared/wordlists/eff_large_wordlist.txt), in order. */
val EFF_LARGE_WORDLIST: List<String> by lazy {
    val stream = requireNotNull(RecoveryKdf::class.java.classLoader?.getResourceAsStream("eff_large_wordlist.txt")) {
        "eff_large_wordlist.txt missing from resources"
    }
    stream.bufferedReader().useLines { lines ->
        lines.filter { it.isNotBlank() }.map { it.substringAfter('\t').trim() }.toList()
    }
}

private val WORD_SET: Set<String> by lazy { EFF_LARGE_WORDLIST.toSet() }

private val acceptLimit: Int get() = 65_536 / EFF_LARGE_WORDLIST.size * EFF_LARGE_WORDLIST.size

/**
 * Turn random bytes into phrase words: each 2 bytes, big-endian, give a number below 65,536;
 * numbers ≥ 62,208 (8 × 7,776) are skipped so every word is equally likely, the rest pick word
 * `number % 7776`. Returns the first [count] words; throws when the bytes run out first.
 */
fun wordsFromBytes(bytes: ByteArray, count: Int = PHRASE_WORDS): List<String> {
    val words = mutableListOf<String>()
    var index = 0
    while (index + 1 < bytes.size && words.size < count) {
        val value = ((bytes[index].toInt() and 0xFF) shl 8) or (bytes[index + 1].toInt() and 0xFF)
        if (value < acceptLimit) words += EFF_LARGE_WORDLIST[value % EFF_LARGE_WORDLIST.size]
        index += 2
    }
    require(words.size >= count) { "not enough random bytes" }
    return words
}

/** A fresh recovery phrase: [PHRASE_WORDS] words joined by single spaces. */
fun generatePhrase(): String {
    while (true) {
        try {
            return wordsFromBytes(randomBytes(4 * PHRASE_WORDS)).joinToString(" ")
        } catch (_: IllegalArgumentException) {
            // Too many skipped numbers in this draw (about 1 in 10^6); draw again.
        }
    }
}

/** A typed phrase checked against the wordlist. */
data class ParsedPhrase(
    /** the words, lower-cased, in order */
    val words: List<String>,
    /** positions of words not in the list */
    val unknown: List<Int>,
    /** the phrase as Argon2id takes it, when it has [PHRASE_WORDS] known words; else null */
    val normalized: String?,
)

private val PHRASE_SPLIT = Regex("""\s+""")

/** Read a typed phrase: split on whitespace (not hyphens, which some words have), lower-case. */
fun parsePhrase(text: String): ParsedPhrase {
    val words = text.lowercase().split(PHRASE_SPLIT).filter { it.isNotEmpty() }
    val unknown = words.indices.filter { words[it] !in WORD_SET }
    val normalized = if (unknown.isEmpty() && words.size == PHRASE_WORDS) words.joinToString(" ") else null
    return ParsedPhrase(words, unknown, normalized)
}

/** Argon2id of a normalized phrase (UTF-8) with a salt: the 32-byte sealing secret. */
fun recoverySecret(normalizedPhrase: String, salt: ByteArray, kdf: RecoveryKdf = RECOVERY_KDF): ByteArray {
    require(kdf.name == "argon2id") { "unknown KDF ${kdf.name}" }
    val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withSalt(salt)
        .withMemoryAsKB(kdf.memoryKiB)
        .withIterations(kdf.iterations)
        .withParallelism(kdf.parallelism)
        .build()
    val generator = Argon2BytesGenerator()
    generator.init(parameters)
    val output = ByteArray(SEAL_SECRET_LENGTH)
    generator.generateBytes(normalizedPhrase.toByteArray(Charsets.UTF_8), output)
    return output
}

/** Seal a private key under a recovery phrase (normalized or as typed). [salt] is for tests only. */
fun sealWithPhrase(
    phrase: String,
    privateKey: String,
    salt: ByteArray = randomBytes(RECOVERY_SALT_LENGTH),
    kdf: RecoveryKdf = RECOVERY_KDF,
): RecoverySealed {
    val normalized = requireNotNull(parsePhrase(phrase).normalized) { "not a recovery phrase" }
    val secret = recoverySecret(normalized, salt, kdf)
    return RecoverySealed(toBase64(salt), seal(secret, privateKey), kdf)
}

/** Open a recovery seal with a phrase; throws [SealException] for a wrong phrase. */
fun unsealWithPhrase(phrase: String, sealed: RecoverySealed): String {
    val normalized = requireNotNull(parsePhrase(phrase).normalized) { "not a recovery phrase" }
    return unseal(recoverySecret(normalized, fromBase64(sealed.salt), sealed.kdf), sealed.sealedPrivateKey)
}
