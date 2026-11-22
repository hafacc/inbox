package cc.hafa.inbox.core.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTest {
    private val data = mapOf(
        "kind" to "message",
        "threadId" to "t1",
        "messageId" to "m1",
        "sender" to "Ana",
        "senderAddress" to "ana@example.com",
        "subject" to "Lunch?",
        "snippet" to "Are you free on Friday",
        "account" to "me@gmail.example.com",
        "actionToken" to "token",
        "sentAt" to "1800000000000",
    )
    private val payload = parsePush(data)!!

    @Test
    fun `reads the data map`() {
        assertEquals(PushKind.MESSAGE, payload.kind)
        assertEquals("t1", payload.threadId)
        assertEquals("token", payload.actionToken)
    }

    @Test
    fun `fills missing fields with empty text`() {
        val bare = parsePush(mapOf("kind" to "message", "threadId" to "t"))!!
        assertEquals(PushKind.MESSAGE, bare.kind)
        assertEquals("", bare.subject)
        assertEquals("", bare.actionToken)
    }

    @Test
    fun `shows only pushes for the account signed in`() {
        assertTrue(isForAccount(payload, "me@gmail.example.com"))
        assertTrue(isForAccount(payload, " Me@Gmail.example.com "))
        assertFalse(isForAccount(payload, "other@gmail.example.com"))
        assertFalse(isForAccount(payload, null))
        assertFalse(isForAccount(payload.copy(account = ""), "me@gmail.example.com"))
    }

    @Test
    fun `ignores pushes that aren't ours`() {
        assertNull(parsePush(emptyMap()))
        assertNull(parsePush(mapOf("kind" to "other", "threadId" to "t")))
        assertNull(parsePush(mapOf("kind" to "wake", "threadId" to "t")))
        assertNull(parsePush(mapOf("kind" to "message")))
    }

    @Test
    fun `shows the sender and the message`() {
        val content = notificationContent(payload)
        assertEquals("Ana", content.title)
        assertEquals("Lunch?\nAre you free on Friday", content.body)
        assertEquals("thread:t1", content.tag)
        assertEquals(1_800_000_000_000L, content.timestamp)
        assertTrue(content.canDone)
    }

    @Test
    fun `titles an ended snooze with the subject`() {
        val content = notificationContent(payload.copy(kind = PushKind.SNOOZE_ENDED, sentAt = ""))
        assertEquals("Lunch?", content.title)
        assertEquals("Back from snooze · Ana", content.body)
        assertNull(content.timestamp)
    }

    @Test
    fun `names a sender without a display name by address`() {
        assertEquals("ana@example.com", notificationContent(payload.copy(sender = "")).title)
    }

    @Test
    fun `offers Done only with an action token`() {
        assertFalse(notificationContent(payload.copy(actionToken = "")).canDone)
    }

    @Test
    fun `says an encrypted message is encrypted until the phone opens it`() {
        val encrypted = parsePush(data + mapOf("encrypted" to "true", "subject" to "", "snippet" to ""))!!
        assertTrue(encrypted.encrypted)
        assertFalse(payload.encrypted)
        val content = notificationContent(encrypted)
        assertEquals("Ana", content.title)
        assertEquals("Encrypted message from Ana", content.body)
        assertEquals("Lunch?\nAre you free on Friday", notificationContent(encrypted.copy(subject = "Lunch?", snippet = "Are you free on Friday")).body)
    }
}
