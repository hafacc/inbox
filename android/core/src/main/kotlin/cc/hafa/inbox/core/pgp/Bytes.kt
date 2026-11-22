package cc.hafa.inbox.core.pgp

import java.security.SecureRandom
import java.util.Base64

// Byte helpers; a port of shared/src/pgp/bytes.ts.

private val NOT_BASE64 = Regex("[^A-Za-z0-9+/]")
private val WHITESPACE = Regex("""\s+""")
private val secureRandom = SecureRandom()

/** Standard base64 with padding. */
fun toBase64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** Bytes from base64 or base64url, padded or not; whitespace is ignored. Throws on anything else. */
fun fromBase64(text: String): ByteArray {
    val standard = text.replace(WHITESPACE, "").replace('-', '+').replace('_', '/').trimEnd('=')
    require(!NOT_BASE64.containsMatchIn(standard) && standard.length % 4 != 1) { "not base64" }
    return Base64.getDecoder().decode(standard + "=".repeat((4 - standard.length % 4) % 4))
}

/** URL-safe base64 without padding (WebAuthn's encoding). */
fun toBase64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** Lower-case hex. */
fun toHex(bytes: ByteArray): String = bytes.toHexString()

/** A "binary string": one character per byte (Latin-1), so MIME text round-trips byte for byte. */
fun bytesToBinary(bytes: ByteArray): String = String(bytes, Charsets.ISO_8859_1)

/** Bytes from a binary string. */
fun binaryToBytes(binary: String): ByteArray {
    require(binary.all { it.code <= 0xFF }) { "not a binary string" }
    return binary.toByteArray(Charsets.ISO_8859_1)
}

/** Fresh random bytes from the platform's secure generator. */
fun randomBytes(length: Int): ByteArray = ByteArray(length).also(secureRandom::nextBytes)

/** The process's secure random source, shared by key and message generation. */
internal val pgpRandom: SecureRandom get() = secureRandom

/** Percent-encoding as JavaScript's `encodeURIComponent`: everything but `A-Z a-z 0-9 - _ . ! ~ * ' ( )`. */
fun encodeUriComponent(value: String): String = buildString {
    for (signed in value.toByteArray(Charsets.UTF_8)) {
        val byte = signed.toInt() and 0xFF
        val character = byte.toChar()
        if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character in "-_.!~*'()") {
            append(character)
        } else {
            append('%').append(signed.toHexString(HexFormat.UpperCase))
        }
    }
}

/** Base64 body text decoded leniently, as mail readers do: other characters dropped, missing padding allowed. */
internal fun lenientBase64(text: String): ByteArray {
    val clean = text.replace(NOT_BASE64, "")
    // A lone trailing character carries no whole byte.
    val usable = if (clean.length % 4 == 1) clean.dropLast(1) else clean
    return Base64.getDecoder().decode(usable + "=".repeat((4 - usable.length % 4) % 4))
}
