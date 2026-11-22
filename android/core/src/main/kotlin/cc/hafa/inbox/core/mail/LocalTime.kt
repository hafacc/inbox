package cc.hafa.inbox.core.mail

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

// Wall-clock times with no zone, as latr stores `snoozeUntil`; each device reads them in its own
// zone. Pinned by shared/testdata/local-time-fixtures.json, like shared/src/local-time.ts.

// LocalDateTime.toString drops `:00` seconds; stored times always have them. Strict, so Feb 30
// fails rather than becoming Feb 28.
private val LOCAL_ISO: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

private val LOCAL_ISO_PATTERN = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""")

/** The instant a local `YYYY-MM-DDTHH:mm:ss` names in [zone], epoch milliseconds. */
fun isoToEpoch(iso: String, zone: ZoneId = ZoneId.systemDefault()): Long =
    LocalDateTime.parse(iso, LOCAL_ISO).atZone(zone).toInstant().toEpochMilli()

/** An instant as a local `YYYY-MM-DDTHH:mm:ss` in [zone], seconds kept, no zone. */
fun epochToIso(epoch: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epoch).atZone(zone).format(LOCAL_ISO)

/** Whether a value is a local `YYYY-MM-DDTHH:mm:ss` naming a real date and time of day. */
fun isLocalIso(value: Any?): Boolean =
    value is String && LOCAL_ISO_PATTERN.matches(value) &&
        try {
            LocalDateTime.parse(value, LOCAL_ISO)
            true
        } catch (_: DateTimeParseException) {
            false
        }
