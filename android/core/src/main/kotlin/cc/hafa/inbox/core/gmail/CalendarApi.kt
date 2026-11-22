package cc.hafa.inbox.core.gmail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

private const val CALENDAR_BASE = "https://www.googleapis.com/calendar/v3/calendars/primary"

/** A start or end of a Google Calendar event: `dateTime` (RFC 3339) or an all-day `date`. */
@Serializable
data class CalendarApiTime(
    /** timed start or end, RFC 3339 */
    val dateTime: String? = null,
    /** all-day date, `yyyy-mm-dd` */
    val date: String? = null,
    /** IANA time zone */
    val timeZone: String? = null,
)

/** A Google Calendar event, reduced to what the invite card reads; attendees kept whole for patching. */
@Serializable
data class CalendarApiEvent(
    /** event id */
    val id: String,
    /** `confirmed`, `tentative` or `cancelled` */
    val status: String? = null,
    /** title */
    val summary: String? = null,
    /** iCalendar UID */
    val iCalUID: String? = null,
    /** start */
    val start: CalendarApiTime? = null,
    /** end */
    val end: CalendarApiTime? = null,
    /** attendees, kept whole */
    val attendees: List<JsonObject>? = null,
    /** `opaque` or `transparent` (busy or free) */
    val transparency: String? = null,
) {
    /** The user's own `responseStatus` (`needsAction`, `accepted`, `tentative`, `declined`), or null when not invited. */
    val selfResponse: String?
        get() = attendees?.firstOrNull { it["self"]?.jsonPrimitive?.booleanOrNull == true }
            ?.get("responseStatus")?.jsonPrimitive?.contentOrNull
}

/** A page of `events.list` or `events.instances`. */
@Serializable
private data class CalendarApiEventList(val items: List<CalendarApiEvent>? = null)

/** Calendar's `responseStatus` for an iCalendar PARTSTAT. */
fun responseStatusOf(partstat: String): String = when (partstat) {
    "ACCEPTED" -> "accepted"
    "TENTATIVE" -> "tentative"
    "DECLINED" -> "declined"
    else -> "needsAction"
}

/** The Google Calendar calls the invite card makes, with the user's token. */
class CalendarClient(
    private val tokens: TokenSource,
    private val transport: HttpTransport = UrlConnectionTransport,
) {
    private suspend fun request(method: String, path: String, body: String? = null): String =
        googleRequest(tokens, transport, method, "$CALENDAR_BASE$path", body)

    /**
     * The event in the user's primary calendar with this iCalendar UID, or the one occurrence
     * starting at [occurrenceStart] (epoch ms) when the invite answers one occurrence; null when
     * Google hasn't added it.
     */
    suspend fun findEvent(iCalUid: String, occurrenceStart: Long? = null): CalendarApiEvent? {
        val params = listOf("iCalUID" to iCalUid, "showDeleted" to "false")
        val found = GmailJson.decodeFromString(CalendarApiEventList.serializer(), request("GET", "/events?${queryString(params)}"))
            .items.orEmpty().firstOrNull { it.status != "cancelled" } ?: return null
        if (occurrenceStart == null) return found
        val instanceParams = listOf("originalStart" to Instant.ofEpochMilli(occurrenceStart).toString())
        val instances = GmailJson.decodeFromString(
            CalendarApiEventList.serializer(),
            request("GET", "/events/${encode(found.id)}/instances?${queryString(instanceParams)}"),
        )
        return instances.items.orEmpty().firstOrNull() ?: found
    }

    /** Set the user's answer on an event; Google mails the organizer. */
    suspend fun respond(event: CalendarApiEvent, partstat: String): CalendarApiEvent {
        val status = responseStatusOf(partstat)
        val attendees = event.attendees.orEmpty().map { attendee ->
            if (attendee["self"]?.jsonPrimitive?.booleanOrNull == true) {
                JsonObject(attendee + ("responseStatus" to JsonPrimitive(status)))
            } else {
                attendee
            }
        }
        val body = buildJsonObject { put("attendees", JsonArray(attendees)) }.toString()
        val text = request("PATCH", "/events/${encode(event.id)}?sendUpdates=all", body)
        return GmailJson.decodeFromString(CalendarApiEvent.serializer(), text)
    }

    /** Busy events of the user overlapping [start]..[end] (epoch ms), other than [exceptICalUid]. */
    suspend fun overlapping(start: Long, end: Long, exceptICalUid: String?): List<CalendarApiEvent> {
        val params = listOf(
            "timeMin" to Instant.ofEpochMilli(start).toString(),
            "timeMax" to Instant.ofEpochMilli(end).toString(),
            "singleEvents" to "true",
            "orderBy" to "startTime",
            "maxResults" to "10",
        )
        return GmailJson.decodeFromString(CalendarApiEventList.serializer(), request("GET", "/events?${queryString(params)}"))
            .items.orEmpty()
            .filter { event ->
                event.status != "cancelled" &&
                    event.transparency != "transparent" &&
                    event.iCalUID != exceptICalUid &&
                    event.start?.dateTime != null &&
                    event.selfResponse != "declined"
            }
    }
}
