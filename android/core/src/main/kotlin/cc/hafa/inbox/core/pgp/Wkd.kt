package cc.hafa.inbox.core.pgp

import java.security.MessageDigest

// WKD addressing; a port of shared/src/pgp/wkd.ts.

/** z-base-32 alphabet (RFC 6189 §5.1.6), as WKD uses it. */
const val ZBASE32_ALPHABET = "ybndrfg8ejkmcpqxot1uwisza345h769"

private const val FIVE_BITS = 31

/** z-base-32 of bytes, most significant bit first, no padding. */
fun zbase32(bytes: ByteArray): String = buildString {
    var buffer = 0
    var bits = 0
    for (signed in bytes) {
        buffer = (buffer shl 8) or (signed.toInt() and 0xFF)
        bits += 8
        while (bits >= 5) {
            append(ZBASE32_ALPHABET[(buffer ushr (bits - 5)) and FIVE_BITS])
            bits -= 5
        }
        buffer = buffer and ((1 shl bits) - 1)
    }
    if (bits > 0) append(ZBASE32_ALPHABET[(buffer shl (5 - bits)) and FIVE_BITS])
}

/** An address split at its last `@`; the domain lower-cased. */
data class AddressParts(
    /** the part before the `@` */
    val local: String,
    /** the domain, lower-cased */
    val domain: String,
)

/** Split an address at its last `@`; throws when it has none. */
fun splitAddress(address: String): AddressParts {
    val trimmed = address.trim()
    val at = trimmed.lastIndexOf('@')
    require(at > 0 && at != trimmed.length - 1) { "not an address" }
    return AddressParts(trimmed.substring(0, at), trimmed.substring(at + 1).lowercase())
}

/** WKD's hashed local part: z-base-32 of SHA-1 of the local part with ASCII letters lower-cased. */
fun wkdHash(localPart: String): String {
    val lowered = localPart.map { if (it in 'A'..'Z') it + ('a' - 'A') else it }.joinToString("")
    return zbase32(MessageDigest.getInstance("SHA-1").digest(lowered.toByteArray(Charsets.UTF_8)))
}

/** Where WKD keeps an address's key: the advanced method first, then the direct one. */
data class WkdUrls(
    /** `https://openpgpkey.<domain>/.well-known/openpgpkey/<domain>/hu/<hash>?l=<local>` */
    val advanced: String,
    /** `https://<domain>/.well-known/openpgpkey/hu/<hash>?l=<local>` */
    val direct: String,
)

/** The two WKD URLs for an address (draft-koch-openpgp-webkey-service §3.1). */
fun wkdUrls(address: String): WkdUrls {
    val (local, domain) = splitAddress(address)
    val hash = wkdHash(local)
    val query = "?l=${encodeUriComponent(local)}"
    return WkdUrls(
        advanced = "https://openpgpkey.$domain/.well-known/openpgpkey/$domain/hu/$hash$query",
        direct = "https://$domain/.well-known/openpgpkey/hu/$hash$query",
    )
}
