package cc.hafa.inbox.core.calendar

import cc.hafa.inbox.core.compose.InvalidAddressError
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Runs `shared/testdata/calendar-fixtures.json`, as `shared/src/calendar.spec.ts` does on web. */
class CalendarFixturesTest {
    private fun cases(section: String) = Fixtures.cases(Fixtures.calendar, section)

    private fun crlf(lines: List<*>): String = lines.joinToString("\r\n") + "\r\n"

    private fun inviteText(name: String): String = crlf((Fixtures.calendar["invites"] as Map<*, *>)[name] as List<*>)

    private fun invite(name: String, zone: String = "America/New_York"): CalendarInvite = parseCalendar(inviteText(name), zone)!!

    private fun time(raw: Any?): EventTime? {
        val map = raw as? Map<*, *> ?: return null
        return if (map["kind"] == "date") {
            EventTime.DateOnly(map["date"] as String)
        } else {
            EventTime.DateTime(map["utc"] as String, map["timeZone"] as String?)
        }
    }

    private fun calendarPerson(raw: Any?): CalendarPerson? {
        val map = raw as? Map<*, *> ?: return null
        return CalendarPerson(map["email"] as String, map["name"] as String)
    }

    private fun expectedInvite(raw: Any?): CalendarInvite? {
        val map = raw as? Map<*, *> ?: return null
        return CalendarInvite(
            map["method"] as String?,
            (map["events"] as List<*>).map {
                val event = it as Map<*, *>
                CalendarEvent(
                    uid = event["uid"] as String,
                    sequence = (event["sequence"] as Number).toInt(),
                    recurrenceId = time(event["recurrenceId"]),
                    status = event["status"] as String?,
                    summary = event["summary"] as String,
                    location = event["location"] as String,
                    description = event["description"] as String,
                    organizer = calendarPerson(event["organizer"]),
                    attendees = (event["attendees"] as List<*>).map { entry ->
                        val attendee = entry as Map<*, *>
                        Attendee(
                            attendee["email"] as String,
                            attendee["name"] as String,
                            attendee["partstat"] as String,
                            attendee["role"] as String,
                            attendee["kind"] as String,
                            attendee["rsvp"] as Boolean,
                        )
                    },
                    start = time(event["start"])!!,
                    end = time(event["end"])!!,
                    recurrence = event["recurrence"] as String?,
                )
            },
        )
    }

    @Test
    fun parse() {
        for (case in cases("parse")) {
            val name = case["invite"] as String
            assertEquals(name, expectedInvite(case["expect"]), parseCalendar(inviteText(name), case["defaultTimeZone"] as String))
        }
    }

    @Test
    fun recurrence() {
        for (case in cases("recurrence")) {
            val rrule = case["rrule"] as String
            assertEquals(rrule, case["expect"], recurrenceText(rrule, case["timeZone"] as String))
        }
    }

    @Test
    fun reply() {
        for (case in cases("reply")) {
            val input = ReplyInput(
                event = invite(case["invite"] as String).events[(case["event"] as Number).toInt()],
                attendee = calendarPerson(case["attendee"])!!,
                partstat = case["partstat"] as String,
                now = Instant.parse(case["now"] as String).toEpochMilli(),
                comment = case["comment"] as String?,
            )
            val name = case["name"] as String
            assertEquals(name, crlf(case["expect"] as List<*>), buildReply(input))
            val mail = case["mail"] as Map<*, *>
            val to = mail["to"] as Map<*, *>
            assertEquals(
                name,
                ReplyMail(Person(to["name"] as String, to["email"] as String), mail["subject"] as String, mail["markdown"] as String),
                replyMail(input),
            )
            for (line in case["expect"] as List<*>) assertTrue((line as String).toByteArray(Charsets.UTF_8).size <= 75)
        }
    }

    @Test
    fun replyRefused() {
        for (case in cases("replyRefused")) {
            val input = ReplyInput(
                event = invite(case["invite"] as String).events[(case["event"] as Number).toInt()],
                attendee = calendarPerson(case["attendee"])!!,
                partstat = case["partstat"] as String,
                now = Instant.parse(case["now"] as String).toEpochMilli(),
                comment = case["comment"] as String?,
            )
            assertThrows(case["name"] as String, InvalidAddressError::class.java) { buildReply(input) }
        }
    }

    @Test
    fun replyMailRefusesAnOrganizerWithoutAPlainAddress() {
        val event = invite("google").events[0]
        val hostile = event.copy(organizer = CalendarPerson("priya@example.com\r\nBcc: eve@evil.example", "Priya"))
        val input = ReplyInput(hostile, CalendarPerson("maya.chen@example.org", "Maya"), "ACCEPTED", 0, null)
        assertThrows(InvalidAddressError::class.java) { replyMail(input) }
        assertThrows(InvalidAddressError::class.java) { buildReply(input) }
    }

    @Test
    fun fold() {
        for (case in cases("fold")) {
            assertEquals((case["expect"] as List<*>).joinToString("\r\n"), foldLine(case["line"] as String))
        }
    }

    @Test
    fun replyParsesBackWithTheAnswer() {
        val event = primaryEvent(invite("google"))
        val own = findAttendee(event, listOf("Maya.Chen@example.org"))!!
        assertEquals("maya.chen@example.org", own.email)
        val reply = parseCalendar(buildReply(ReplyInput(event, own.person, "TENTATIVE", 0, null)), "UTC")!!
        assertEquals("REPLY", reply.method)
        val answered = reply.events.single()
        assertEquals(event.uid, answered.uid)
        assertEquals(EventTime.DateTime("2026-11-10T17:00:00Z", "UTC"), answered.start)
        assertEquals(listOf(Attendee(own.email, own.name, "TENTATIVE", own.role, own.kind, false)), answered.attendees)
    }

    @Test
    fun primaryEventIsTheOneWithoutRecurrenceId() {
        val both = invite("googleException").events + invite("google").events
        assertNull(primaryEvent(CalendarInvite("REQUEST", both)).recurrenceId)
    }

    @Test
    fun noOrganizerNoReply() {
        val event = invite("floatingAndWindowsName").events[0]
        assertThrows(IllegalArgumentException::class.java) {
            buildReply(ReplyInput(event, CalendarPerson("a@b.example", ""), "ACCEPTED", 0, null))
        }
    }
}
