package cc.hafa.inbox.core.pgp

import cc.hafa.inbox.core.compose.addressKey
import java.util.Date

// Key lookup and the encrypt-iff rule; a port of shared/src/pgp/lookup.ts and autocrypt.ts.

/** The domain whose mail may be encrypted (mail from other addresses always goes as is). */
const val ENCRYPTING_DOMAIN = "hafa.cc"

/** keys.openpgp.org's lookup by (verified) address; answers armored, 404 when unknown. */
fun vksUrl(address: String): String = "https://keys.openpgp.org/vks/v1/by-email/${encodeUriComponent(address.trim())}"

/**
 * The key to use from a lookup answer (WKD binary, keys.openpgp.org armored, or an Autocrypt
 * header's key data) for an address: armored, or null when the answer has no public key with that
 * address that can be encrypted to. Never throws on bad data.
 */
fun keyFromAnswer(data: ByteArray, address: String, now: Date = Date()): String? =
    try {
        pickKeyFor(readKeyRings(data), address, now)
    } catch (_: Exception) {
        null
    }

/** [keyFromAnswer] for text answers. */
fun keyFromAnswer(text: String, address: String, now: Date = Date()): String? =
    keyFromAnswer(text.toByteArray(Charsets.UTF_8), address, now)

/** Why a message will or won't be encrypted. */
enum class EncryptionReason(
    /** its name on the wire */
    val wire: String,
) {
    /** encrypt */
    ENCRYPT("encrypt"),

    /** From isn't an @hafa.cc address */
    NOT_ENCRYPTING_SENDER("not-encrypting-sender"),

    /** nobody to send to yet */
    NO_RECIPIENTS("no-recipients"),

    /** some recipients have no key */
    MISSING_KEYS("missing-keys"),
}

/** Whether to encrypt a message, and which recipients (lower-cased) lack a key. */
data class EncryptionDecision(
    /** whether to encrypt */
    val encrypt: Boolean,
    /** why */
    val reason: EncryptionReason,
    /** recipients (lower-cased) with no key */
    val missing: List<String>,
)

/**
 * Decide whether to encrypt: only mail from an `@hafa.cc` address, and only when every recipient
 * (To, Cc and Bcc) has a key. [keys] maps lower-cased addresses to an armored key, or null /
 * absent when none was found.
 */
fun encryptionDecision(from: String, recipients: List<String>, keys: Map<String, String?>): EncryptionDecision {
    val domain = runCatching { splitAddress(from).domain }.getOrDefault("")
    if (domain != ENCRYPTING_DOMAIN) return EncryptionDecision(false, EncryptionReason.NOT_ENCRYPTING_SENDER, emptyList())
    val unique = recipients.map(::addressKey).distinct()
    if (unique.isEmpty()) return EncryptionDecision(false, EncryptionReason.NO_RECIPIENTS, emptyList())
    val missing = unique.filter { keys[it].isNullOrEmpty() }
    return if (missing.isEmpty()) {
        EncryptionDecision(true, EncryptionReason.ENCRYPT, missing)
    } else {
        EncryptionDecision(false, EncryptionReason.MISSING_KEYS, missing)
    }
}

/** A parsed `Autocrypt` header (Autocrypt Level 1). */
data class AutocryptHeader(
    /** the sender address it is for, lower-cased */
    val addr: String,
    /** `mutual` when the sender asks for encryption by default, else `nopreference` */
    val preferEncrypt: String,
    /** the public key, binary OpenPGP */
    val keydata: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is AutocryptHeader && addr == other.addr && preferEncrypt == other.preferEncrypt && keydata.contentEquals(other.keydata)

    override fun hashCode(): Int = (addr.hashCode() * 31 + preferEncrypt.hashCode()) * 31 + keydata.contentHashCode()
}

/** Characters per space-separated piece of `keydata`, so the header can be folded at the spaces. */
const val KEYDATA_CHUNK = 64

/**
 * The value of the `Autocrypt` header for outgoing mail:
 * `addr=<address>; prefer-encrypt=mutual; keydata=<base64>`, the key data split into
 * [KEYDATA_CHUNK]-character pieces joined by spaces. Pass it to `buildMime` in `extraHeaders`.
 */
fun autocryptHeaderValue(address: String, publicKey: String): String {
    // Re-serialized for transfer, so packets only a keyring keeps (trust packets) are left out, as on web.
    val chunks = toBase64(readPublicKey(publicKey).getEncoded(true)).chunked(KEYDATA_CHUNK)
    return "addr=${addressKey(address)}; prefer-encrypt=mutual; keydata=${chunks.joinToString(" ")}"
}

private val KNOWN_ATTRIBUTES = setOf("addr", "prefer-encrypt", "keydata")

/**
 * Read an `Autocrypt` header value (unfolded). Null when it isn't valid: `addr` or `keydata`
 * missing, keydata not base64, or an attribute this version doesn't know that doesn't start with
 * `_`. Whether `addr` matches the message's `From` is [autocryptFor]'s check.
 */
fun parseAutocrypt(value: String): AutocryptHeader? {
    val attributes = linkedMapOf<String, String>()
    for (piece in value.split(";")) {
        if (piece.isBlank()) continue
        val equals = piece.indexOf('=')
        if (equals <= 0) return null
        val name = piece.substring(0, equals).trim()
        if (name in attributes) return null
        attributes[name] = piece.substring(equals + 1).trim()
    }
    if (attributes.keys.any { it !in KNOWN_ATTRIBUTES && !it.startsWith("_") }) return null
    val addr = attributes["addr"]?.let(::addressKey)?.takeIf { it.isNotEmpty() } ?: return null
    val keydataText = attributes["keydata"]?.takeIf { it.isNotEmpty() } ?: return null
    val keydata = try {
        fromBase64(keydataText)
    } catch (_: IllegalArgumentException) {
        return null
    }
    if (keydata.isEmpty()) return null
    return AutocryptHeader(addr, if (attributes["prefer-encrypt"] == "mutual") "mutual" else "nopreference", keydata)
}

/**
 * The Autocrypt header that applies to a message: of the `Autocrypt` values given, the one whose
 * `addr` is the `From` address; null when none or more than one (the spec ignores both).
 */
fun autocryptFor(values: List<String>, fromAddress: String): AutocryptHeader? {
    val from = addressKey(fromAddress)
    val matching = values.mapNotNull(::parseAutocrypt).filter { it.addr == from }
    return matching.singleOrNull()
}
