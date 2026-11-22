package cc.hafa.inbox.core.notify

import cc.hafa.inbox.core.mail.Labels
import cc.hafa.inbox.core.mail.ThreadSummary
import cc.hafa.inbox.core.snooze.LastCustom
import cc.hafa.inbox.core.snooze.SetStat
import cc.hafa.inbox.core.snooze.SnoozeStatsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class NotificationRulesTest {
    private val zone = ZoneId.of("America/New_York")
    private val arrived = 1_800_000_000_000L

    private fun summary(lastDate: Long = arrived, labels: List<String> = listOf(Labels.INBOX, Labels.UNREAD)) =
        ThreadSummary("t1", "1", "Hi", "", emptyList(), 1, lastDate, labels, Labels.UNREAD in labels)

    @Test
    fun `reads a clear push`() {
        assertEquals(ClearPush("me@gmail.example.com", listOf("t1", "t2")), parseClear(mapOf("kind" to "clear", "account" to "me@gmail.example.com", "threadIds" to "t1, t2,")))
        assertNull(parseClear(mapOf("kind" to "clear", "threadIds" to "")))
        assertNull(parseClear(mapOf("kind" to "message", "threadIds" to "t1")))
    }

    @Test
    fun `a notification goes once its thread is read, out of the inbox or snoozed`() {
        assertFalse(mailNotificationStale(arrived, summary(), snoozed = false))
        assertTrue(mailNotificationStale(arrived, summary(labels = listOf(Labels.INBOX)), snoozed = false))
        assertTrue(mailNotificationStale(arrived, summary(labels = listOf(Labels.UNREAD)), snoozed = false))
        assertTrue(mailNotificationStale(arrived, summary(), snoozed = true))
        assertTrue(mailNotificationStale(arrived, summary(lastDate = arrived + 1, labels = listOf(Labels.INBOX)), snoozed = false))
    }

    @Test
    fun `a summary from before the mail came, or none, keeps it`() {
        assertFalse(mailNotificationStale(arrived, summary(lastDate = arrived - 1, labels = listOf(Labels.INBOX)), snoozed = false))
        assertFalse(mailNotificationStale(arrived, null, snoozed = true))
    }

    @Test
    fun `snooze picks the first suggestion the sheet shows, in the future`() {
        val now = ZonedDateTime.of(2026, 11, 14, 9, 0, 0, 0, zone).toInstant()
        val learned = SnoozeStatsSnapshot(sets = mapOf("D0@1500__D0_h3" to SetStat(2.0, now.toEpochMilli())))
        val picked = notificationSnoozeTime(learned, now, zone)
        assertTrue(picked > now.toEpochMilli())
    }

    @Test
    fun `with nothing learned, snooze picks the sheet's last custom time, else where its picker opens`() {
        val now = ZonedDateTime.of(2026, 11, 14, 9, 5, 0, 0, zone).toInstant()
        val evening = ZonedDateTime.of(2026, 11, 14, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        val custom = SnoozeStatsSnapshot(lastCustom = LastCustom(evening, now.toEpochMilli() - 1))
        assertEquals(evening, notificationSnoozeTime(custom, now, zone))
        val quarter = ZonedDateTime.of(2026, 11, 14, 9, 15, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(quarter, notificationSnoozeTime(SnoozeStatsSnapshot(), now, zone))
        val lateNight = ZonedDateTime.of(2026, 11, 14, 23, 50, 0, 0, zone).toInstant()
        val midnight = ZonedDateTime.of(2026, 11, 15, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(midnight, notificationSnoozeTime(SnoozeStatsSnapshot(), lateNight, zone))
    }

    @Test
    fun `the snooze caption says the time, with the weekday when not today`() {
        val now = ZonedDateTime.of(2026, 11, 14, 9, 0, 0, 0, zone).toInstant()
        val evening = ZonedDateTime.of(2026, 11, 14, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        val monday = ZonedDateTime.of(2026, 11, 16, 8, 5, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Snooze until 18:00", snoozeActionLabel(evening, now, zone))
        assertEquals("Snooze until Mon 08:05", snoozeActionLabel(monday, now, zone))
    }
}
