package cc.hafa.inbox.core.compose

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.Base64

// Byte-level encodings for building MIME messages; a port of shared/src/compose/encoding.ts.

/** Line break used throughout a built message. */
const val CRLF = "\r\n"

/** Longest line, without its CRLF, a base64 or quoted-printable body produces. */
const val MAX_BODY_LINE = 76

/** Folded header lines are kept to this length where a break point allows. */
const val MAX_HEADER_LINE = 78

// UTF-8 bytes per encoded word: 45 bytes is 60 base64 characters, 72 with `=?UTF-8?B?` and `?=`.
private const val ENCODED_WORD_BYTES = 45

/** Whether every character is printable ASCII (space through `~`). */
fun isPrintableAscii(text: String): Boolean = text.all { it in ' '..'~' }

/** Standard base64 (with `=` padding) of bytes, on one line. */
fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

/** Base64 of bytes in lines of 76 characters joined by CRLF, with no trailing break. */
fun base64Lines(bytes: ByteArray): String = base64(bytes).chunked(MAX_BODY_LINE).joinToString(CRLF)

/** Line breaks of any kind (CRLF, CR, LF) turned into LF. */
fun normalizeNewlines(text: String): String = text.replace(CR_LINE_BREAK, "\n")

private val CR_LINE_BREAK = Regex("\r\n?")

private fun hexByte(byte: Int): String = "=" + byte.toByte().toHexString(HexFormat.UpperCase)

/**
 * Quoted-printable (RFC 2045) of text as UTF-8, lines joined by CRLF with no trailing break.
 *
 * Bytes 33–60 and 62–126 stay as they are; space and tab stay unless they end a line; everything
 * else is `=XX` in upper-case hex. An encoded line holds at most 75 characters before a soft break
 * (`=` CRLF), and an escape is never split.
 */
fun quotedPrintable(text: String): String {
    val out = mutableListOf<String>()
    for (line in normalizeNewlines(text).split("\n")) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        val current = StringBuilder()
        for ((index, signed) in bytes.withIndex()) {
            val byte = signed.toInt() and 0xFF
            val last = index == bytes.lastIndex
            val token = when {
                (byte == 0x20 || byte == 0x09) && !last -> byte.toChar().toString()
                byte in 33..126 && byte != 61 -> byte.toChar().toString()
                else -> hexByte(byte)
            }
            if (current.length + token.length > MAX_BODY_LINE - 1) {
                out += "$current="
                current.clear()
            }
            current.append(token)
        }
        out += current.toString()
    }
    return out.joinToString(CRLF)
}

/**
 * RFC 2047 encoded words (`=?UTF-8?B?…?=`) for text, each holding at most 45 bytes of UTF-8 and
 * never splitting a character, so each word is at most 72 characters long.
 */
fun encodedWords(text: String): List<String> {
    val words = mutableListOf<String>()
    val chunk = ByteArrayOutputStream()
    var offset = 0
    while (offset < text.length) {
        val codePoint = text.codePointAt(offset)
        val bytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8)
        if (chunk.size() + bytes.size > ENCODED_WORD_BYTES) {
            words += "=?UTF-8?B?${base64(chunk.toByteArray())}?="
            chunk.reset()
        }
        chunk.write(bytes)
        offset += Character.charCount(codePoint)
    }
    if (chunk.size() > 0 || words.isEmpty()) words += "=?UTF-8?B?${base64(chunk.toByteArray())}?="
    return words
}

/**
 * Unstructured header text (such as a subject) as it goes on the wire: unchanged when it is
 * printable ASCII without `=?`, otherwise the whole text as encoded words separated by spaces.
 */
fun encodeHeaderText(text: String): String =
    if (isPrintableAscii(text) && !text.contains("=?")) text else encodedWords(text).joinToString(" ")

/**
 * A header line `Name: value`, folded (CRLF and a space) at spaces so lines stay within 78
 * characters where possible. A word longer than a line is never split.
 */
fun foldHeader(name: String, value: String): String {
    val lines = mutableListOf<String>()
    var current = "$name:"
    var lineHasWord = false
    for (word in value.split(" ")) {
        if (lineHasWord && current.length + 1 + word.length > MAX_HEADER_LINE) {
            lines += current
            current = " $word"
        } else {
            current += " $word"
            lineHasWord = true
        }
    }
    lines += current
    return lines.joinToString(CRLF)
}

private val random = SecureRandom()

/** A fresh random token for [ComposeInput.boundary]: 24 hex digits from a secure random source. */
fun randomBoundary(): String {
    val bytes = ByteArray(12)
    random.nextBytes(bytes)
    return bytes.toHexString()
}

/** Percent-encoding as JavaScript's `encodeURIComponent`, with `'()*!` encoded too (RFC 5987). */
internal fun percentEncoded(value: String): String = buildString {
    for (signed in value.toByteArray(Charsets.UTF_8)) {
        val byte = signed.toInt() and 0xFF
        val character = byte.toChar()
        if (character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character in "-_.~") {
            append(character)
        } else {
            append('%').append(signed.toHexString(HexFormat.UpperCase))
        }
    }
}
