package cc.hafa.inbox.core.gmail

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/** Every Gmail call says whether Gmail was reached, whichever call it was and however it ended. */
class ReportingTransportTest {
    private object Tokens : TokenSource {
        override suspend fun get(): String = "token"

        override fun invalidate() = Unit
    }

    @Test
    fun `an answer of any status is reported and a network failure is not one`() = runTest {
        val events = mutableListOf<ConnectionEvent>()
        var reachable = false
        val transport = ReportingTransport({ _, _, _, _ ->
            if (reachable) HttpResponse(404, """{"error":{"message":"Not Found"}}""") else throw IOException("no network")
        }) { events += it }
        val client = GmailClient(Tokens, transport = transport)
        runCatching { client.getThread("t1", full = true) }
        reachable = true
        runCatching { client.getThread("t1", full = true) }
        assertEquals(listOf(ConnectionEvent.Unanswered, ConnectionEvent.Answered(404)), events)
    }

    @Test
    fun `a socket timeout is reported as one and doesn't count as offline`() = runTest {
        val events = mutableListOf<ConnectionEvent>()
        val transport = ReportingTransport({ _, _, _, _ -> throw SocketTimeoutException("slow") }) { events += it }
        val failure = runCatching { GmailClient(Tokens, transport = transport).getThread("t1", full = true) }.exceptionOrNull()
        assertEquals(listOf(ConnectionEvent.TimedOut), events)
        assertFalse(isNetworkError(failure!!))
        assertTrue(isRetryable(failure))
    }
}
