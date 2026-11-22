package cc.hafa.inbox.core.notify

import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.ThreadSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId

class SnoozeRemindersTest {
    private val zone = ZoneId.of("America/New_York")

    // 2026-11-12T09:00:00 in New York.
    private val nine = 1_794_492_000_000L
    private val hour = 3_600_000L

    private fun thread(encrypted: Boolean = false) = ThreadSummary(
        "t1", "1", "Lunch?", "", listOf(Person("", "bo@x.example.com"), Person("Ana", "ana@x.example.com")), 2, nine, listOf("INBOX"), unread = true, encrypted = encrypted,
    )

    @Test
    fun `notifies what ended since the last check and waits on the earliest still ahead`() {
        val before = SnoozeEnd("before", "2026-11-12T07:00:00")
        val ended = SnoozeEnd("ended", "2026-11-12T08:30:00")
        val now = SnoozeEnd("now", "2026-11-12T09:00:00")
        val later = SnoozeEnd("later", "2026-11-13T09:00:00")
        val sooner = SnoozeEnd("sooner", "2026-11-12T12:00:00")
        val check = checkSnoozes(listOf(later, now, before, sooner, ended), LastCheck(nine - hour, zone), nine, zone)
        assertEquals(listOf(ended, now), check.ended)
        assertEquals(sooner, check.next)
        assertEquals(nine + 3 * hour, check.next!!.endsAt(zone))
    }

    @Test
    fun `after flying east a snooze whose wall time passed is notified`() {
        // Last checked at 07:00 in New York; now 13:00 in London, five hours ahead.
        val london = ZoneId.of("Europe/London")
        val passed = SnoozeEnd("passed", "2026-11-12T10:00:00")
        val ahead = SnoozeEnd("ahead", "2026-11-12T14:00:00")
        val check = checkSnoozes(listOf(passed, ahead), LastCheck(nine - 2 * hour, zone), nine - hour, london)
        assertEquals(listOf(passed), check.ended)
        assertEquals(ahead, check.next)
    }

    @Test
    fun `after flying west a snooze already notified is not notified again`() {
        // Last checked at 10:30 in London; now 10:30 in New York, five hours behind.
        val london = ZoneId.of("Europe/London")
        val notified = SnoozeEnd("notified", "2026-11-12T10:00:00")
        val check = checkSnoozes(listOf(notified), LastCheck(nine - 3 * hour - hour / 2, london), nine + hour + hour / 2, zone)
        assertEquals(emptyList<SnoozeEnd>(), check.ended)
    }

    @Test
    fun `the first check only waits`() {
        val ended = SnoozeEnd("ended", "2026-11-12T08:30:00")
        assertEquals(SnoozeCheck(emptyList(), null), checkSnoozes(listOf(ended), null, nine, zone))
    }

    @Test
    fun `an ended snooze reads like a pushed notification`() {
        val snooze = SnoozeEnd("t1", "2026-11-12T09:00:00")
        val content = notificationContent(snoozeEndedPayload(snooze, thread(), zone))
        assertEquals("Lunch?", content.title)
        assertEquals("Back from snooze · Ana", content.body)
        assertEquals(notificationTag("t1"), content.tag)
        assertEquals(nine, content.timestamp)
        val sealed = notificationContent(snoozeEndedPayload(snooze, thread(encrypted = true), zone))
        assertEquals("Encrypted message", sealed.title)
        val unknown = notificationContent(snoozeEndedPayload(snooze, null, zone))
        assertEquals("(no subject)", unknown.title)
        assertNull(snoozeEndedPayload(snooze, null, zone).kind.takeIf { it != PushKind.SNOOZE_ENDED })
    }

    private val todo = mapOf<String, Any?>(
        "text" to "Call *Sam* #home",
        "state" to "ACTIVE",
        "snoozeUntil" to "2026-11-12T08:30:00",
        "deleted" to false,
    )

    @Test
    fun `a todo waits on its snooze until it is done or deleted`() {
        assertEquals(SnoozeEnd("todo:a", "2026-11-12T08:30:00"), todoSnoozeEnd("a", todo))
        assertNull(todoSnoozeEnd("a", todo + ("state" to "DONE")))
        assertNull(todoSnoozeEnd("a", todo + ("deleted" to true)))
        assertNull(todoSnoozeEnd("a", todo + ("snoozeUntil" to null)))
        assertEquals("a", todoIdOf(todoItemId("a")))
        assertNull(todoIdOf("18c2f0a1b2c3d4e5"))
    }

    @Test
    fun `a todo's notification shows its text without markers`() {
        val content = todoSnoozeEndedContent(SnoozeEnd("todo:a", "2026-11-12T08:30:00"), "Call *Sam* #home", zone)
        assertEquals(NotificationContent("Call Sam #home", "Back from snooze", "todo:a", nine - hour / 2, canDone = true), content)
        assertEquals("Todo", todoSnoozeEndedContent(SnoozeEnd("todo:a", "2026-11-12T08:30:00"), "  ", zone).title)
    }
}
