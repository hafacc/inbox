package cc.hafa.inbox.mail

import cc.hafa.inbox.core.runSuspendCatching
import kotlinx.coroutines.CancellationException
import android.util.Log
import cc.hafa.inbox.core.calendar.CalendarEvent
import cc.hafa.inbox.core.calendar.CalendarInvite
import cc.hafa.inbox.core.calendar.CalendarPerson
import cc.hafa.inbox.core.calendar.EventTime
import cc.hafa.inbox.core.calendar.ReplyInput
import cc.hafa.inbox.core.calendar.buildReply
import cc.hafa.inbox.core.calendar.findAttendee
import cc.hafa.inbox.core.calendar.parseCalendar
import cc.hafa.inbox.core.calendar.primaryEvent
import cc.hafa.inbox.core.calendar.replyMail
import cc.hafa.inbox.core.compose.CalendarPart
import cc.hafa.inbox.core.compose.DraftContent
import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.gmail.CalendarApiEvent
import cc.hafa.inbox.core.gmail.CalendarClient
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.decodeBase64UrlText
import cc.hafa.inbox.core.mail.decodeText
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "Invites"

/** What the invite card shows for one message. */
data class InviteCard(
    /** the invite as parsed */
    val invite: CalendarInvite,
    /** the event the card is about */
    val event: CalendarEvent,
    /** the user's entry among the attendees, or null (invited through a group) */
    val self: CalendarPerson?,
    /** the user's answer so far: `ACCEPTED`, `TENTATIVE`, `DECLINED`, or null */
    val answer: String?,
    /** the same event in the user's Google Calendar, when Google added it */
    val calendarEvent: CalendarApiEvent?,
    /** names of the user's other events at the same time */
    val conflicts: List<String>,
)

private fun partstatOf(responseStatus: String?): String? = when (responseStatus) {
    "accepted" -> "ACCEPTED"
    "tentative" -> "TENTATIVE"
    "declined" -> "DECLINED"
    else -> null
}

private fun EventTime.epochStart(zone: ZoneId): Long = when (this) {
    is EventTime.DateTime -> epochMs
    is EventTime.DateOnly -> LocalDate.parse(date).atStartOfDay(zone).toInstant().toEpochMilli()
}

/**
 * Calendar invites in messages (design.md "Calendar invites"): the card's details, and RSVP
 * through `events.patch` when the event is in the user's Google Calendar, else an iCalendar
 * REPLY mailed to the organizer through the send path.
 */
class Invites(
    private val calendar: CalendarClient,
    private val sending: Sending,
    private val attachmentBytes: suspend (messageId: String, partId: String, attachmentId: String) -> ByteArray,
) {
    private fun ownAddresses(): List<String> = sending.senders.value.map { it.email }

    /** The invite in [message], with what the user's calendar says about it; null when it has none. */
    suspend fun load(message: MessageView): InviteCard? {
        val source = message.body.calendar ?: return null
        val text = source.data?.let { decodeBase64UrlText(it, source.charset) } ?: source.attachmentId?.let { id ->
            runSuspendCatching { decodeText(attachmentBytes(message.id, source.partId, id), source.charset) }.getOrNull()
        } ?: return null
        val zone = ZoneId.systemDefault()
        val invite = runCatching { parseCalendar(text, zone.id) }.getOrNull() ?: return null
        if (invite.events.isEmpty()) return null
        val event = primaryEvent(invite)
        val self = findAttendee(event, ownAddresses())
        val occurrence = event.recurrenceId?.epochStart(zone)
        val calendarEvent = try {
            calendar.findEvent(event.uid, occurrence)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "calendar lookup failed", error)
            null
        }
        val conflicts = try {
            val start = event.start
            val end = event.end
            if (start is EventTime.DateTime && end is EventTime.DateTime && invite.method != "CANCEL") {
                calendar.overlapping(start.epochMs, end.epochMs, event.uid).mapNotNull { it.summary?.ifBlank { null } ?: "Busy" }
            } else {
                emptyList()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "conflict lookup failed", error)
            emptyList()
        }
        val answer = partstatOf(calendarEvent?.selfResponse)
            ?: self?.partstat?.takeIf { it in setOf("ACCEPTED", "TENTATIVE", "DECLINED") }
        return InviteCard(invite, event, self?.person, answer, calendarEvent, conflicts)
    }

    /** Whether the user can answer: the event is in their calendar, or there is an organizer to mail. */
    fun canAnswer(card: InviteCard): Boolean =
        card.invite.method == "REQUEST" && (card.calendarEvent?.selfResponse != null || card.event.organizer != null)

    /** Answer an invite: `ACCEPTED`, `TENTATIVE` or `DECLINED`. Returns the card as it now stands. */
    suspend fun answer(card: InviteCard, message: MessageView, partstat: String): InviteCard {
        val inCalendar = card.calendarEvent
        if (inCalendar?.selfResponse != null) {
            val updated = calendar.respond(inCalendar, partstat)
            return card.copy(calendarEvent = updated, answer = partstat)
        }
        val from = sending.fromFor(message)
        val attendee = card.self ?: CalendarPerson(from.email, from.name)
        val input = ReplyInput(card.event, attendee, partstat, System.currentTimeMillis(), null)
        val mail = replyMail(input)
        val sender = sending.senders.value.firstOrNull { addressKey(it.email) == addressKey(attendee.email) } ?: from
        sending.sendNow(
            DraftContent(
                localId = "",
                from = sender,
                to = listOf(mail.to),
                subject = mail.subject,
                markdown = mail.markdown,
                calendar = CalendarPart("REPLY", buildReply(input)),
            ),
        )
        return card.copy(answer = partstat)
    }
}
