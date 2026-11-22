package cc.hafa.inbox.core.calendar

import biweekly.Biweekly
import biweekly.ICalVersion
import biweekly.ICalendar
import biweekly.component.VEvent
import biweekly.property.Attendee as ICalAttendee
import biweekly.io.TimezoneInfo
import biweekly.property.DateOrDateTimeProperty
import biweekly.property.ICalProperty
import biweekly.util.ICalDate
import biweekly.util.Recurrence
import cc.hafa.inbox.core.compose.CRLF
import cc.hafa.inbox.core.compose.InvalidAddressError
import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.compose.isPlainAddress
import cc.hafa.inbox.core.mail.Person
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** When an event starts or ends: a whole day (all-day events), or an instant. */
sealed class EventTime {
    /** A whole day; an all-day end is the day after the last day. */
    data class DateOnly(
        /** `yyyy-mm-dd` */
        val date: String,
    ) : EventTime()

    /** An instant, with the zone it was written in. */
    data class DateTime(
        /** ISO 8601 in UTC to the second, `2026-11-10T17:00:00Z` */
        val utc: String,
        /** IANA zone it was written in (`UTC` for `Z` times), or null when unknown or floating */
        val timeZone: String?,
    ) : EventTime() {
        /** [utc] as epoch ms */
        val epochMs: Long get() = Instant.parse(utc).toEpochMilli()
    }
}

/** Someone named in an invite. */
data class CalendarPerson(
    /** address, without `mailto:` */
    val email: String,
    /** `CN`, or empty when missing or just the address */
    val name: String,
)

/** An invited person, room or group. */
data class Attendee(
    /** address, without `mailto:` */
    val email: String,
    /** `CN`, or empty when missing or just the address */
    val name: String,
    /** `PARTSTAT`: NEEDS-ACTION (default), ACCEPTED, DECLINED, TENTATIVE, DELEGATED… */
    val partstat: String,
    /** `ROLE`: REQ-PARTICIPANT (default), OPT-PARTICIPANT, CHAIR, NON-PARTICIPANT */
    val role: String,
    /** `CUTYPE`: INDIVIDUAL (default), ROOM, RESOURCE, GROUP… */
    val kind: String,
    /** `RSVP=TRUE`: a reply is asked for */
    val rsvp: Boolean,
) {
    /** who it is, without the invite details */
    val person: CalendarPerson get() = CalendarPerson(email, name)
}

/** One VEVENT. */
data class CalendarEvent(
    /** `UID` */
    val uid: String,
    /** `SEQUENCE`, 0 when missing */
    val sequence: Int,
    /** the occurrence a changed instance replaces, or null for the event itself */
    val recurrenceId: EventTime?,
    /** `STATUS` (CONFIRMED, TENTATIVE, CANCELLED), or null */
    val status: String?,
    /** title */
    val summary: String,
    /** place */
    val location: String,
    /** notes */
    val description: String,
    /** who sent it; null when missing, or when neither its EMAIL nor its value is a plain address */
    val organizer: CalendarPerson?,
    /** invited people, rooms and groups with a plain address (EMAIL, else the value), in order */
    val attendees: List<Attendee>,
    /** start */
    val start: EventTime,
    /** from DTEND, else DTSTART + DURATION, else the next day (all-day) or the start */
    val end: EventTime,
    /** the repeat rule in English (`Weekly on Tuesday`), or null when it doesn't repeat */
    val recurrence: String?,
)

/** A text/calendar part. */
data class CalendarInvite(
    /** `METHOD` upper-case (REQUEST, CANCEL, REPLY, PUBLISH…), or null */
    val method: String?,
    /** its events, in order */
    val events: List<CalendarEvent>,
)

/** `PRODID` written into replies. */
const val PRODID = "-//hafa.cc//Inbox//EN"

/** Windows zone names (Outlook's TZIDs) for the zones most invites use. */
val WINDOWS_ZONES: Map<String, String> = mapOf(
    "Eastern Standard Time" to "America/New_York",
    "Central Standard Time" to "America/Chicago",
    "Mountain Standard Time" to "America/Denver",
    "US Mountain Standard Time" to "America/Phoenix",
    "Pacific Standard Time" to "America/Los_Angeles",
    "Alaskan Standard Time" to "America/Anchorage",
    "Hawaiian Standard Time" to "Pacific/Honolulu",
    "Atlantic Standard Time" to "America/Halifax",
    "GMT Standard Time" to "Europe/London",
    "Greenwich Standard Time" to "Atlantic/Reykjavik",
    "W. Europe Standard Time" to "Europe/Berlin",
    "Romance Standard Time" to "Europe/Paris",
    "Central Europe Standard Time" to "Europe/Budapest",
    "Central European Standard Time" to "Europe/Warsaw",
    "FLE Standard Time" to "Europe/Kyiv",
    "India Standard Time" to "Asia/Kolkata",
    "China Standard Time" to "Asia/Shanghai",
    "Singapore Standard Time" to "Asia/Singapore",
    "Tokyo Standard Time" to "Asia/Tokyo",
    "AUS Eastern Standard Time" to "Australia/Sydney",
    "UTC" to "UTC",
)

private val IANA_NAME_RE = Regex("""^[A-Za-z_]+(/[A-Za-z0-9_+-]+)+$|^UTC$""")

private fun isIanaZone(name: String): Boolean =
    IANA_NAME_RE.matches(name) && try {
        ZoneId.of(name)
        true
    } catch (_: DateTimeException) {
        false
    }

/** The IANA zone a `TZID` names (itself, or a Windows name), or null. */
fun ianaZone(tzid: String): String? {
    val trimmed = tzid.trim()
    return if (isIanaZone(trimmed)) trimmed else WINDOWS_ZONES[trimmed]
}

private fun isoUtc(epochMs: Long): String = Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()

/** A wall-clock time in [zone]: a repeated time takes the earlier instant, a skipped one moves forward by the gap. */
private fun wallToEpoch(wall: LocalDateTime, zone: String): Long = ZonedDateTime.of(wall, ZoneId.of(zone)).toInstant().toEpochMilli()

private fun wallTime(date: ICalDate): LocalDateTime {
    val raw = date.rawComponents
    return LocalDateTime.of(raw.year, raw.month, raw.date, raw.hour, raw.minute, raw.second)
}

/** How a property's time was written: its `TZID`, and whether the part's VTIMEZONE resolved it. */
private class Written(val tzid: String?, val byVtimezone: Boolean)

private fun written(property: ICalProperty, timezones: TimezoneInfo): Written {
    val assignment = timezones.getTimezone(property)
    val component = assignment?.component
    return when {
        component != null -> Written(component.timezoneId?.value, true)
        assignment?.globalId != null -> Written(assignment.globalId, false)
        else -> Written(property.parameters.timezoneId, false)
    }
}

private fun eventTime(date: ICalDate, how: Written, defaultTimeZone: String): EventTime {
    if (!date.hasTime()) {
        val raw = date.rawComponents
        return EventTime.DateOnly("%04d-%02d-%02d".format(raw.year, raw.month, raw.date))
    }
    if (date.rawComponents.isUtc) return EventTime.DateTime(isoUtc(date.time), "UTC")
    val named = how.tzid?.let(::ianaZone)
    return if (how.byVtimezone) {
        EventTime.DateTime(isoUtc(date.time), named)
    } else {
        EventTime.DateTime(isoUtc(wallToEpoch(wallTime(date), named ?: defaultTimeZone)), named)
    }
}

private fun propertyTime(property: DateOrDateTimeProperty?, timezones: TimezoneInfo, defaultTimeZone: String): EventTime? {
    val value = property?.value ?: return null
    return eventTime(value, written(property, timezones), defaultTimeZone)
}

private fun endFromDuration(event: VEvent, start: EventTime, startProperty: DateOrDateTimeProperty, timezones: TimezoneInfo, defaultTimeZone: String): EventTime {
    val duration = event.duration?.value
    return when (start) {
        is EventTime.DateOnly -> {
            val day = LocalDate.parse(start.date)
            val days = if (duration == null) 1L else duration.toMillis() / 86_400_000L
            EventTime.DateOnly(day.plusDays(days).toString())
        }
        is EventTime.DateTime -> {
            if (duration == null) return start
            val how = written(startProperty, timezones)
            val startDate = startProperty.value
            if (how.byVtimezone || startDate.rawComponents.isUtc) {
                EventTime.DateTime(isoUtc(start.epochMs + duration.toMillis()), start.timeZone)
            } else {
                val wall = wallTime(startDate).plusSeconds(duration.toMillis() / 1000)
                EventTime.DateTime(isoUtc(wallToEpoch(wall, start.timeZone ?: defaultTimeZone)), start.timeZone)
            }
        }
    }
}

/**
 * The person a property names, or null when neither its EMAIL nor its value is a plain address.
 *
 * biweekly moves an attendee's EMAIL parameter into [email] and keeps the value as [uri]; an
 * organizer keeps the parameter and has the value in [email] (or [uri] without `mailto:`), so the
 * candidates in order are the parameter, [email], then [uri].
 */
private fun person(email: String?, uri: String?, emailParameter: String?, commonName: String?): CalendarPerson? {
    val value = (uri ?: "").trim()
    val address = listOf(emailParameter ?: "", email ?: "", if (value.startsWith("mailto:", ignoreCase = true)) value.substring(7) else value)
        .map { it.trim() }
        .firstOrNull(::isPlainAddress) ?: return null
    val cn = (commonName ?: "").trim()
    return CalendarPerson(address, if (cn.equals(address, ignoreCase = true)) "" else cn)
}

private fun attendee(property: ICalAttendee): Attendee? {
    val who = person(property.email, property.uri, property.parameters.email, property.commonName) ?: return null
    return Attendee(
        who.email,
        who.name,
        (property.participationStatus?.value ?: "NEEDS-ACTION").uppercase(),
        // biweekly splits ROLE into a role (CHAIR) and a participation level (REQ-/OPT-/NON-PARTICIPANT).
        (property.participationLevel?.getValue(ICalVersion.V2_0) ?: property.role?.value ?: "REQ-PARTICIPANT").uppercase(),
        (property.calendarUserType?.value ?: "INDIVIDUAL").uppercase(),
        property.rsvp == true,
    )
}

private fun text(value: String?): String = value?.trim() ?: ""

/** A parsed rule back as RRULE text, for [recurrenceText]. */
private fun ruleText(rule: Recurrence): String = buildList {
    add("FREQ=${rule.frequency?.name ?: ""}")
    rule.interval?.let { add("INTERVAL=$it") }
    if (rule.byDay.isNotEmpty()) add("BYDAY=" + rule.byDay.joinToString(",") { (it.num?.toString() ?: "") + it.day.abbr })
    if (rule.byMonthDay.isNotEmpty()) add("BYMONTHDAY=" + rule.byMonthDay.joinToString(","))
    if (rule.bySetPos.isNotEmpty()) add("BYSETPOS=" + rule.bySetPos.joinToString(","))
    rule.count?.let { add("COUNT=$it") }
    rule.until?.let { until ->
        val raw = until.rawComponents
        val day = "%04d%02d%02d".format(raw.year, raw.month, raw.date)
        add(
            "UNTIL=" + if (until.hasTime()) {
                "${day}T%02d%02d%02d%s".format(raw.hour, raw.minute, raw.second, if (raw.isUtc) "Z" else "")
            } else {
                day
            },
        )
    }
}.joinToString(";")

private fun eventFrom(event: VEvent, timezones: TimezoneInfo, defaultTimeZone: String): CalendarEvent? {
    val startProperty = event.dateStart ?: return null
    val start = propertyTime(startProperty, timezones, defaultTimeZone) ?: return null
    val end = propertyTime(event.dateEnd, timezones, defaultTimeZone)
        ?: endFromDuration(event, start, startProperty, timezones, defaultTimeZone)
    val organizer = event.organizer?.let { person(it.email, it.uri, it.parameters.email, it.commonName) }
    val status = text(event.status?.value).uppercase()
    val zone = (start as? EventTime.DateTime)?.timeZone ?: defaultTimeZone
    return CalendarEvent(
        uid = text(event.uid?.value),
        sequence = event.sequence?.value ?: 0,
        recurrenceId = propertyTime(event.recurrenceId, timezones, defaultTimeZone),
        status = status.ifEmpty { null },
        summary = text(event.summary?.value),
        location = text(event.location?.value),
        description = text(event.description?.value),
        organizer = organizer,
        attendees = event.attendees.mapNotNull(::attendee),
        start = start,
        end = end,
        recurrence = event.recurrenceRule?.value?.let { recurrenceText(ruleText(it), zone) },
    )
}

private val FOLD_RE = Regex("(?:\r\n|\r|\n)[ \t]")
private val FREQ_RE = Regex("(?:^|;)FREQ=([A-Z]+)", RegexOption.IGNORE_CASE)

/**
 * The text without each VTIMEZONE rule that doesn't repeat yearly: real zones change at most a few
 * times a year, and biweekly walks a rule like FREQ=SECONDLY one step at a time while parsing,
 * which never ends. An observance left without a rule applies from its DTSTART on.
 */
private fun withYearlyZoneRules(text: String): String {
    var inZone = false
    return text.replace(FOLD_RE, "").split(LINE_BREAK_RE).filterNot { line ->
        val name = line.substringBefore(':').substringBefore(';').uppercase()
        val value = line.substringAfter(':').trim().uppercase()
        if (name == "BEGIN" && value == "VTIMEZONE") inZone = true
        if (name == "END" && value == "VTIMEZONE") inZone = false
        inZone && name == "RRULE" && FREQ_RE.find(value)?.groupValues?.get(1) != "YEARLY"
    }.joinToString("\r\n")
}

/**
 * Read a text/calendar part; null when it isn't iCalendar or holds no VEVENT with a DTSTART.
 *
 * Times written with a `TZID` use the part's VTIMEZONE when it has one, else the IANA zone the
 * TZID names (or its Windows name), else [defaultTimeZone]; floating times use [defaultTimeZone].
 * A VTIMEZONE rule that isn't FREQ=YEARLY is ignored, and an event that can't be read is skipped.
 */
fun parseCalendar(text: String, defaultTimeZone: String): CalendarInvite? {
    val calendar: ICalendar = try {
        Biweekly.parse(withYearlyZoneRules(text)).first()
    } catch (_: Exception) {
        null
    } ?: return null
    val events = calendar.events.mapNotNull { runCatching { eventFrom(it, calendar.timezoneInfo, defaultTimeZone) }.getOrNull() }
    if (events.isEmpty()) return null
    return CalendarInvite(calendar.method?.value?.trim()?.uppercase(), events)
}

/** The event a card shows: the first without a RECURRENCE-ID, else the first. */
fun primaryEvent(invite: CalendarInvite): CalendarEvent = invite.events.firstOrNull { it.recurrenceId == null } ?: invite.events.first()

/** The attendee entry that is the user: the first whose address is one of [ownAddresses]. */
fun findAttendee(event: CalendarEvent, ownAddresses: List<String>): Attendee? {
    val own = ownAddresses.map(::addressKey).toSet()
    return event.attendees.firstOrNull { addressKey(it.email) in own }
}

private val DAY_NAMES = mapOf(
    "MO" to "Monday", "TU" to "Tuesday", "WE" to "Wednesday", "TH" to "Thursday", "FR" to "Friday",
    "SA" to "Saturday", "SU" to "Sunday",
)

private val ORDINALS = mapOf("1" to "first", "2" to "second", "3" to "third", "4" to "fourth", "5" to "fifth", "-1" to "last")

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

private val UNTIL_RE = Regex("""^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z?))?$""")
private val POSITIONED_DAY_RE = Regex("""^([+-]?\d)?(MO|TU|WE|TH|FR|SA|SU)$""")
private val DIGITS_RE = Regex("""^\d+$""")

private fun englishList(items: List<String>): String =
    if (items.size <= 1) items.firstOrNull() ?: "" else "${items.dropLast(1).joinToString(", ")} and ${items.last()}"

private fun untilText(until: String, timeZone: String): String? {
    val match = UNTIL_RE.matchEntire(until) ?: return null
    val groups = match.groupValues
    var year = groups[1].toInt()
    var month = groups[2].toInt()
    var day = groups[3].toInt()
    if (groups[4].isNotEmpty() && groups[7] == "Z") {
        val utc = LocalDateTime.of(year, month, day, groups[4].toInt(), groups[5].toInt(), groups[6].toInt())
        val local = utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of(timeZone))
        year = local.year
        month = local.monthValue
        day = local.dayOfMonth
    }
    return "${MONTHS[month - 1]} $day, $year"
}

private val RRULE_PREFIX_RE = Regex("^RRULE:", RegexOption.IGNORE_CASE)

/**
 * An RRULE in English: `Daily`, `Every 2 weeks on Monday and Thursday`, `Weekly on weekdays`,
 * `Monthly on day 15`, `Monthly on the last Friday`, `Annually`, then `, 6 times` or
 * `, until Dec 18, 2026` (a UTC `UNTIL` as its date in [timeZone]); other frequencies give `Repeats`.
 */
fun recurrenceText(rrule: String, timeZone: String): String {
    val parts = mutableMapOf<String, String>()
    for (pair in rrule.replace(RRULE_PREFIX_RE, "").split(";")) {
        val equals = pair.indexOf('=')
        if (equals > 0) parts[pair.substring(0, equals).uppercase()] = pair.substring(equals + 1).uppercase()
    }
    val interval = maxOf(1, parts["INTERVAL"]?.toIntOrNull() ?: 1)
    val days = (parts["BYDAY"] ?: "").split(",").filter { it.isNotEmpty() }
    val units = mapOf(
        "DAILY" to ("Daily" to "days"),
        "WEEKLY" to ("Weekly" to "weeks"),
        "MONTHLY" to ("Monthly" to "months"),
        "YEARLY" to ("Annually" to "years"),
    )
    val frequency = parts["FREQ"] ?: ""
    val (single, plural) = units[frequency] ?: return "Repeats"
    val sentence = StringBuilder(if (interval == 1) single else "Every $interval $plural")
    if (frequency == "WEEKLY" && days.isNotEmpty()) {
        val plain = days.map { it.takeLast(2) }
        val weekdays = listOf("MO", "TU", "WE", "TH", "FR")
        sentence.append(
            if (plain.size == 5 && plain.containsAll(weekdays)) " on weekdays" else " on ${englishList(plain.map { DAY_NAMES[it] ?: it })}",
        )
    } else if (frequency == "MONTHLY") {
        val monthDay = parts["BYMONTHDAY"]
        val positioned = if (days.size == 1) POSITIONED_DAY_RE.matchEntire(days[0]) else null
        val position = positioned?.groupValues?.get(1)?.ifEmpty { null }?.replace("+", "") ?: parts["BYSETPOS"]
        val ordinal = position?.let { ORDINALS[it] }
        when {
            monthDay != null && DIGITS_RE.matches(monthDay) -> sentence.append(" on day ${monthDay.toInt()}")
            monthDay == "-1" -> sentence.append(" on the last day")
            positioned != null && ordinal != null -> sentence.append(" on the $ordinal ${DAY_NAMES[positioned.groupValues[2]]}")
        }
    }
    val count = parts["COUNT"]
    val until = parts["UNTIL"]
    if (count != null && DIGITS_RE.matches(count)) {
        sentence.append(if (count.toInt() == 1) ", once" else ", ${count.toInt()} times")
    } else if (until != null) {
        untilText(until, timeZone)?.let { sentence.append(", until $it") }
    }
    return sentence.toString()
}

private val LINE_BREAK_RE = Regex("""\r\n|\r|\n""")

/** Text escaped for an iCalendar TEXT value. */
fun escapeText(value: String): String =
    value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(LINE_BREAK_RE, "\\\\n")

private val PARAMETER_UNSAFE_RE = Regex("""["\r\n]""")

private fun parameterValue(value: String): String {
    val clean = value.replace(PARAMETER_UNSAFE_RE, "")
    return if (clean.any { it == ':' || it == ';' || it == ',' }) "\"$clean\"" else clean
}

/** Fold a content line at 75 octets (RFC 5545 3.1), never inside a UTF-8 sequence. */
fun foldLine(line: String): String {
    val pieces = mutableListOf<String>()
    val current = StringBuilder()
    var octets = 0
    var index = 0
    while (index < line.length) {
        val codePoint = line.codePointAt(index)
        val character = String(Character.toChars(codePoint))
        val size = character.toByteArray(Charsets.UTF_8).size
        val limit = if (pieces.isEmpty()) 75 else 74
        if (octets + size > limit) {
            pieces += current.toString()
            current.setLength(0)
            octets = 0
        }
        current.append(character)
        octets += size
        index += Character.charCount(codePoint)
    }
    pieces += current.toString()
    return pieces.joinToString("$CRLF ")
}

private val UTC_SEPARATORS_RE = Regex("[-:]")
private val FRACTION_RE = Regex("""\.\d+""")

private fun basicUtc(utc: String): String = utc.replace(UTC_SEPARATORS_RE, "").replace(FRACTION_RE, "")

private fun timeProperty(name: String, time: EventTime): String = when (time) {
    is EventTime.DateOnly -> "$name;VALUE=DATE:${time.date.replace("-", "")}"
    is EventTime.DateTime -> "$name:${basicUtc(time.utc)}"
}

private fun personProperty(name: String, who: CalendarPerson, parameters: List<String>): String {
    if (!isPlainAddress(who.email)) throw InvalidAddressError(who.email)
    val cn = if (who.name.isBlank()) emptyList() else listOf("CN=${parameterValue(who.name)}")
    return "${(listOf(name) + parameters + cn).joinToString(";")}:mailto:${who.email}"
}

/** What an RSVP by mail needs. */
data class ReplyInput(
    /** the event answered (an occurrence when it has a RECURRENCE-ID) */
    val event: CalendarEvent,
    /** the user, as invited ([findAttendee]) or their own address */
    val attendee: CalendarPerson,
    /** `ACCEPTED`, `TENTATIVE` or `DECLINED` */
    val partstat: String,
    /** epoch ms, for DTSTAMP */
    val now: Long,
    /** a note to the organizer, or null */
    val comment: String?,
)

/**
 * The iCalendar REPLY (RFC 5546) for an RSVP, CRLF line ends, folded at 75 octets.
 *
 * One VEVENT with DTSTART, DTEND, DTSTAMP, ORGANIZER, UID, RECURRENCE-ID (for one occurrence),
 * the user's ATTENDEE with PARTSTAT, COMMENT (when given), SEQUENCE and SUMMARY. UID is written
 * TEXT-escaped like SUMMARY. Throws when the event has no organizer to reply to, and
 * [InvalidAddressError] when the organizer's or attendee's address isn't a plain address.
 */
fun buildReply(input: ReplyInput): String {
    val event = input.event
    val organizer = requireNotNull(event.organizer) { "The invite has no organizer" }
    val lines = mutableListOf(
        "BEGIN:VCALENDAR",
        "PRODID:$PRODID",
        "VERSION:2.0",
        "CALSCALE:GREGORIAN",
        "METHOD:REPLY",
        "BEGIN:VEVENT",
        timeProperty("DTSTART", event.start),
        timeProperty("DTEND", event.end),
        "DTSTAMP:${basicUtc(isoUtc(input.now))}",
        personProperty("ORGANIZER", organizer, emptyList()),
        "UID:${escapeText(event.uid)}",
    )
    event.recurrenceId?.let { lines += timeProperty("RECURRENCE-ID", it) }
    lines += personProperty("ATTENDEE", input.attendee, listOf("PARTSTAT=${input.partstat}"))
    if (input.comment != null && input.comment.isNotBlank()) lines += "COMMENT:${escapeText(input.comment.trim())}"
    lines += listOf("SEQUENCE:${event.sequence}", "SUMMARY:${escapeText(event.summary)}", "END:VEVENT", "END:VCALENDAR")
    return lines.joinToString(CRLF, transform = ::foldLine) + CRLF
}

private val WHITESPACE_RUN_RE = Regex("""\s+""")
private val REPLY_SUBJECTS = mapOf("ACCEPTED" to "Accepted", "TENTATIVE" to "Tentatively accepted", "DECLINED" to "Declined")
private val REPLY_VERBS = mapOf("ACCEPTED" to "accepted", "TENTATIVE" to "tentatively accepted", "DECLINED" to "declined")

/** The mail an RSVP goes in, besides the calendar part. */
data class ReplyMail(
    /** the organizer */
    val to: Person,
    /** `Accepted: Design review` */
    val subject: String,
    /** body, markdown */
    val markdown: String,
)

/**
 * The message an RSVP by mail is sent as: to the organizer, subject `Accepted: …`,
 * `Tentatively accepted: …` or `Declined: …`, and a one-line body. Line breaks and other whitespace
 * runs in the subject become one space. Throws without an organizer, and [InvalidAddressError] when
 * the organizer's address isn't a plain address.
 */
fun replyMail(input: ReplyInput): ReplyMail {
    val organizer = requireNotNull(input.event.organizer) { "The invite has no organizer" }
    if (!isPlainAddress(organizer.email)) throw InvalidAddressError(organizer.email)
    val who = input.attendee.name.trim().ifEmpty { input.attendee.email }
    val comment = input.comment?.trim() ?: ""
    return ReplyMail(
        to = Person(organizer.name, organizer.email),
        subject = "${REPLY_SUBJECTS.getValue(input.partstat)}: ${input.event.summary}".replace(WHITESPACE_RUN_RE, " ").trim(),
        markdown = "$who has ${REPLY_VERBS.getValue(input.partstat)} this invitation." + if (comment.isEmpty()) "" else "\n\n$comment",
    )
}
