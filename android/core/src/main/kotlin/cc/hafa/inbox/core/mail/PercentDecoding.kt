package cc.hafa.inbox.core.mail

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

private val PERCENT_RUN_RE = Regex("""(%[0-9A-Fa-f]{2})+""")

/**
 * Percent-decode as UTF-8. A run of escapes that isn't valid UTF-8 is kept as written (or, with
 * [lossy], decoded with replacement characters); a `%` without two hex digits stays.
 */
internal fun percentDecoded(text: String, lossy: Boolean = false): String = PERCENT_RUN_RE.replace(text) { run ->
    val bytes = run.value.chunked(3).map { it.substring(1).toInt(16).toByte() }.toByteArray()
    val action = if (lossy) CodingErrorAction.REPLACE else CodingErrorAction.REPORT
    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(action).onUnmappableCharacter(action)
    try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        run.value
    }
}

private val LONE_PERCENT_RE = Regex("""%(?![0-9A-Fa-f]{2})""")

/** Percent-decode as UTF-8 like JavaScript's `decodeURIComponent`, or null where that throws. */
internal fun strictPercentDecoded(text: String): String? {
    if (LONE_PERCENT_RE.containsMatchIn(text)) return null
    var failed = false
    val decoded = PERCENT_RUN_RE.replace(text) { run ->
        val bytes = run.value.chunked(3).map { it.substring(1).toInt(16).toByte() }.toByteArray()
        try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            failed = true
            run.value
        }
    }
    return if (failed) null else decoded
}
