package cc.hafa.inbox.core.gmail

import cc.hafa.inbox.core.filters.GmailFilter
import cc.hafa.inbox.core.filters.GmailFilterCriteria
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GmailClientTest {
    private class CountingTokens : TokenSource {
        var issued = 0

        override suspend fun get(): String = "token-${issued + 1}"

        override fun invalidate() {
            issued++
        }
    }

    private data class Call(val method: String, val url: String, val auth: String?, val body: String?)

    @Test
    fun `lists threads with repeated labelIds and retries once with a fresh token on 401`() = runTest {
        val calls = mutableListOf<Call>()
        val client = GmailClient(CountingTokens()) { method, url, headers, body ->
            calls += Call(method, url, headers["Authorization"], body)
            if (calls.size == 1) HttpResponse(401, "") else HttpResponse(200, """{"threads":[{"id":"t1"}]}""")
        }
        val page = client.listThreads(ListOptions(labelIds = listOf("INBOX", "STARRED"), maxResults = 20))
        assertEquals(listOf(GmailThreadStub("t1")), page.threads)
        assertEquals(listOf("Bearer token-1", "Bearer token-2"), calls.map { it.auth })
        assertEquals(
            "https://gmail.googleapis.com/gmail/v1/users/me/threads?labelIds=INBOX&labelIds=STARRED&maxResults=20",
            calls[1].url,
        )
    }

    @Test
    fun `reports Gmail's error message and status`() = runTest {
        val client = GmailClient(CountingTokens()) { _, _, _, _ ->
            HttpResponse(404, """{"error":{"message":"Not Found"}}""")
        }
        val failure = runCatching { client.getThread("gone", full = true) }.exceptionOrNull()
        assertTrue(failure is GmailException)
        assertEquals(404, (failure as GmailException).status)
        assertEquals("Not Found", failure.message)
    }

    @Test
    fun `a 403 for a usage limit is retryable and a plain 403 is not`() = runTest {
        var body = """{"error":{"message":"Limit","errors":[{"reason":"userRateLimitExceeded"}]}}"""
        var now = 0L
        val client = GmailClient(CountingTokens(), RateGate(now = { now }, sleep = { now += it })) { _, _, _, _ -> HttpResponse(403, body) }
        val limited = runCatching { client.getThread("t1", full = true) }.exceptionOrNull()!!
        assertEquals("userRateLimitExceeded", (limited as GmailException).reason)
        assertTrue(isRateLimited(limited))
        assertTrue(isRetryable(limited))
        body = """{"error":{"message":"Nope","details":[{"reason":"insufficientPermissions"}]}}"""
        val refused = runCatching { client.getThread("t1", full = true) }.exceptionOrNull()!!
        assertEquals("insufficientPermissions", (refused as GmailException).reason)
        assertFalse(isRetryable(refused))
        assertTrue(isRetryable(GmailException(429, "slow down")))
        assertTrue(isRetryable(GmailException(503, "busy")))
        assertTrue(isRetryable(java.io.IOException("offline")))
    }

    @Test
    fun `modify sends JSON with the label changes`() = runTest {
        var sent: String? = null
        val client = GmailClient(CountingTokens()) { _, _, _, body ->
            sent = body
            HttpResponse(200, """{"id":"t1"}""")
        }
        client.modifyThread("t1", listOf("INBOX"), listOf("UNREAD"))
        assertEquals("""{"addLabelIds":["INBOX"],"removeLabelIds":["UNREAD"]}""", sent)
    }

    @Test
    fun `metadata fetches ask for the row headers and searches are encoded`() = runTest {
        val urls = mutableListOf<String>()
        val client = GmailClient(CountingTokens()) { _, url, _, _ ->
            urls += url
            HttpResponse(200, """{"id":"t"}""")
        }
        client.getThread("t", full = false)
        client.listThreads(ListOptions(query = "from:maya is:unread"))
        assertEquals(
            "https://gmail.googleapis.com/gmail/v1/users/me/threads/t?format=metadata" +
                "&metadataHeaders=From&metadataHeaders=Subject&metadataHeaders=To&metadataHeaders=Cc&metadataHeaders=Content-Type" +
                "&metadataHeaders=Message-ID&metadataHeaders=In-Reply-To&metadataHeaders=References" +
                "&fields=id%2ChistoryId%2Csnippet%2Cmessages%28id%2CthreadId%2ClabelIds%2Csnippet%2ChistoryId%2CinternalDate%2Cpayload%28mimeType%2Cheaders%29%29",
            urls[0],
        )
        assertEquals(
            "https://gmail.googleapis.com/gmail/v1/users/me/threads?q=from%3Amaya%20is%3Aunread&maxResults=50",
            urls[1],
        )
    }

    @Test
    fun `mapLimited keeps order and caps how many run at once`() = runTest {
        val running = AtomicInteger()
        var peak = 0
        val results = mapLimited(listOf(1, 2, 3, 4, 5), 2) { value ->
            val now = running.incrementAndGet()
            peak = maxOf(peak, now)
            delay(5)
            running.decrementAndGet()
            if (value == 3) error("three")
            value * 10
        }
        assertEquals(2, peak)
        assertEquals(listOf(true, true, false, true, true), results.map { it.isSuccess })
        assertEquals(50, results[4].getOrNull())
    }

    @Test
    fun `waits out Retry-After on a usage limit, tries a read again, and backs off writes`() = runTest {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val backoffs = mutableListOf<Backoff>()
        val gate = RateGate(
            now = { now },
            sleep = { ms: Long ->
                waits += ms
                now += ms
            },
            jitter = { 0.0 },
            onBackoff = { backoffs += it },
        )
        var calls = 0
        val client = GmailClient(CountingTokens(), gate) { _, _, _, _ ->
            calls++
            if (calls == 1) HttpResponse(429, """{"error":{"message":"Quota exceeded"}}""", "7") else HttpResponse(200, """{"id":"t1"}""")
        }
        assertEquals("t1", client.getThread("t1", full = false).id)
        assertEquals(2, calls)
        assertEquals(listOf(7_000L), waits)
        assertEquals(0L, gate.pausedUntil.value)

        val limited = GmailClient(CountingTokens(), gate) { _, _, _, _ -> HttpResponse(429, """{"error":{"message":"Quota"}}""") }
        val failure = runCatching { limited.modifyThread("t1", emptyList(), listOf("INBOX")) }.exceptionOrNull()!!
        assertTrue(isRateLimited(failure))
        assertEquals(now + 1_000L, gate.pausedUntil.value)
        assertEquals(listOf(Backoff(429, null, 1, 7_000L, CallPriority.VISIBLE), Backoff(429, null, 1, 1_000L, CallPriority.ACTION)), backoffs)
    }

    @Test
    fun `a read limited every time is tried four more times, and a limit met while paused is the same strike`() = runTest {
        var now = 0L
        val pauses = mutableListOf<Long>()
        val gate = RateGate(now = { now }, sleep = { now += it }, jitter = { 0.0 }, onBackoff = { pauses += it.pauseMs })
        var calls = 0
        val client = GmailClient(CountingTokens(), gate) { _, _, _, _ ->
            calls++
            HttpResponse(403, """{"error":{"message":"Limit","errors":[{"reason":"rateLimitExceeded"}]}}""")
        }
        assertTrue(isRateLimited(runCatching { client.getThread("t1", full = false) }.exceptionOrNull()!!))
        assertEquals(5, calls)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L), pauses)
        val paused = gate.pausedUntil.value
        assertEquals(paused, gate.slowDown(429, null, null, CallPriority.BACKGROUND))
        assertEquals(5, pauses.size)
    }

    @Test
    fun `runs four calls at once and starts the most urgent waiting one next`() = runTest {
        val gate = RateGate()
        val started = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val blockers = (1..4).map { index ->
            async { gate.withSlot({ CallPriority.ACTION }) { started += "busy$index"; release.await() } }
        }
        testScheduler.runCurrent()
        val background = async { gate.withSlot({ CallPriority.BACKGROUND }) { started += "background" } }
        val visible = async { gate.withSlot({ CallPriority.VISIBLE }) { started += "visible" } }
        testScheduler.runCurrent()
        assertEquals(4, started.size)
        release.complete(Unit)
        (blockers + background + visible).awaitAll()
        assertEquals(listOf("visible", "background"), started.drop(4))
    }

    @Test
    fun `a waiting read whose coroutine's priority rises goes before reads asked for after it`() = runTest {
        val gate = RateGate()
        val started = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val client = GmailClient(CountingTokens(), gate, io = StandardTestDispatcher(testScheduler)) { _, url, _, _ ->
            started += url.substringAfterLast("/").substringBefore("?")
            HttpResponse(200, """{"id":"t"}""")
        }
        val blockers = (1..4).map { async { gate.withSlot({ CallPriority.ACTION }) { release.await() } } }
        testScheduler.runCurrent()
        val refresh = GmailPriority(CallPriority.BACKGROUND)
        val raised = async(refresh) { client.getThread("refresh", full = false) }
        testScheduler.runCurrent()
        val other = async(GmailPriority(CallPriority.VISIBLE)) { client.getThread("other", full = false) }
        testScheduler.runCurrent()
        assertTrue(refresh.raise(CallPriority.ACTION))
        gate.poke()
        release.complete(Unit)
        (blockers + raised + other).awaitAll()
        assertEquals(listOf("refresh", "other"), started)
    }

    @Test
    fun `an open starts at once while the saved copy fills, which waits for it and is spaced`() = runTest {
        var clock = 1_000L
        // Sleepers wake when the test moves the clock.
        var tick = CompletableDeferred<Unit>()
        val gate = RateGate(now = { clock }, sleep = { tick.await() })
        fun advance(ms: Long) {
            clock += ms
            val woken = tick
            tick = CompletableDeferred()
            woken.complete(Unit)
        }
        val started = mutableListOf<String>()
        val releases = (1..3).associate { "fill$it" to CompletableDeferred<Unit>() } + ("open" to CompletableDeferred())
        fun ask(name: String, priority: CallPriority) = async { gate.withSlot({ priority }) { started += name; releases.getValue(name).await() } }
        val fills = (1..3).map { ask("fill$it", CallPriority.SAVING) }
        testScheduler.runCurrent()
        // One saved-copy read starts; the next only after the spacing.
        assertEquals(listOf("fill1"), started)
        advance(SAVING_SPACING_MS)
        testScheduler.runCurrent()
        assertEquals(listOf("fill1", "fill2"), started)
        val open = ask("open", CallPriority.VISIBLE)
        testScheduler.runCurrent()
        assertEquals(listOf("fill1", "fill2", "open"), started)
        releases.getValue("fill1").complete(Unit)
        advance(SAVING_SPACING_MS)
        testScheduler.runCurrent()
        // While the open runs, the saved copy waits.
        assertEquals(listOf("fill1", "fill2", "open"), started)
        releases.getValue("open").complete(Unit)
        testScheduler.runCurrent()
        assertEquals(listOf("fill1", "fill2", "open", "fill3"), started)
        releases.values.forEach { it.complete(Unit) }
        (fills + open).awaitAll()
    }

    @Test
    fun `after a usage limit the saved copy stays held past the pause while an open goes`() = runTest {
        var clock = 1_000L
        var tick = CompletableDeferred<Unit>()
        val gate = RateGate(now = { clock }, sleep = { tick.await() }, jitter = { 0.0 })
        fun advance(ms: Long) {
            clock += ms
            val woken = tick
            tick = CompletableDeferred()
            woken.complete(Unit)
        }
        val until = gate.slowDown(403, "userRateLimitExceeded", null, CallPriority.SAVING)
        val started = mutableListOf<String>()
        val fill = async { gate.withSlot({ CallPriority.SAVING }) { started += "fill" } }
        val open = async { gate.withSlot({ CallPriority.VISIBLE }) { started += "open" } }
        testScheduler.runCurrent()
        assertEquals(emptyList<String>(), started)
        advance(until - clock)
        testScheduler.runCurrent()
        assertEquals(listOf("open"), started)
        advance(SAVING_HOLD_MS - 1)
        testScheduler.runCurrent()
        assertEquals(listOf("open"), started)
        advance(1)
        testScheduler.runCurrent()
        assertEquals(listOf("open", "fill"), started)
        listOf(fill, open).awaitAll()
    }

    @Test
    fun `a saved-copy read held after a usage limit starts at once when its priority rises`() = runTest {
        var clock = 1_000L
        val tick = CompletableDeferred<Unit>()
        val gate = RateGate(now = { clock }, sleep = { tick.await() }, jitter = { 0.0 })
        val until = gate.slowDown(403, "userRateLimitExceeded", null, CallPriority.SAVING)
        var priority = CallPriority.SAVING
        var started = false
        val read = async { gate.withSlot({ priority }) { started = true } }
        clock = until
        gate.poke()
        testScheduler.runCurrent()
        assertFalse(started)
        priority = CallPriority.VISIBLE
        gate.poke()
        testScheduler.runCurrent()
        assertTrue(started)
        read.await()
    }

    @Test
    fun `a call with no answer in time fails as retryable and frees its slot`() = runTest {
        val client = GmailClient(CountingTokens(), io = StandardTestDispatcher(testScheduler)) { _, url, _, _ ->
            if ("slow" in url) delay(READ_TIMEOUT_MS + 1)
            HttpResponse(200, """{"id":"t"}""")
        }
        val slow = (1..4).map { async { runCatching { client.getThread("slow$it", full = false) }.exceptionOrNull() } }
        assertEquals("t", client.getThread("fast", full = false).id)
        val failures = slow.awaitAll()
        assertTrue(failures.all { it is GmailTimeoutException && isRetryable(it) })
    }

    @Test
    fun `sends one request for the same read asked for twice at once`() = runTest {
        val calls = AtomicInteger()
        val client = GmailClient(CountingTokens(), io = StandardTestDispatcher(testScheduler)) { _, _, _, _ ->
            calls.incrementAndGet()
            delay(10)
            HttpResponse(200, """{"id":"t1"}""")
        }
        val first = async { client.getThread("t1", full = true) }
        val second = async { client.getThread("t1", full = true) }
        assertEquals(first.await(), second.await())
        assertEquals(1, calls.get())
    }

    @Test
    fun `a caller that stops waiting doesn't cancel the others sharing its read`() = runTest {
        val calls = AtomicInteger()
        val client = GmailClient(CountingTokens(), io = StandardTestDispatcher(testScheduler)) { _, _, _, _ ->
            calls.incrementAndGet()
            delay(10)
            HttpResponse(200, """{"id":"t1"}""")
        }
        val first = async { client.getThread("t1", full = true) }
        val second = async { client.getThread("t1", full = true) }
        delay(5)
        first.cancel()
        assertEquals("t1", second.await().id)
        assertEquals(2, calls.get())
    }

    @Test
    fun `creates wait for their answer however long it takes, other writes keep their timeout`() = runTest {
        val calls = mutableListOf<String>()
        val transport = object : HttpTransport {
            override suspend fun send(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
                calls += "timed $method ${url.substringAfter("/users/me").substringBefore('?')}"
                return HttpResponse(200, """{"id":"x","message":{"id":"m","threadId":"t1"}}""")
            }

            override suspend fun sendUntimed(method: String, url: String, headers: Map<String, String>, body: String?): HttpResponse {
                delay(WRITE_TIMEOUT_MS + 1)
                calls += "untimed $method ${url.substringAfter("/users/me").substringBefore('?')}"
                return HttpResponse(200, """{"id":"x","name":"Trips","message":{"id":"m","threadId":"t1"}}""")
            }
        }
        val client = GmailClient(CountingTokens(), io = StandardTestDispatcher(testScheduler), transport = transport)
        assertEquals("x", client.createDraft("Subject: hi\r\n\r\nhi", "t1").id)
        assertEquals("x", client.createLabel("Trips").id)
        client.createFilter(GmailFilter(criteria = GmailFilterCriteria(from = "a@b.c")))
        client.updateDraft("d1", "Subject: hi\r\n\r\nhi", "t1")
        client.modifyThread("t1", listOf("STARRED"), emptyList())
        client.deleteDraft("d1")
        assertEquals(
            listOf(
                "untimed POST /drafts",
                "untimed POST /labels",
                "untimed POST /settings/filters",
                "timed PUT /drafts/d1",
                "timed POST /threads/t1/modify",
                "timed DELETE /drafts/d1",
            ),
            calls,
        )
    }

    @Test
    fun `a draft is created and updated in its thread`() = runTest {
        val bodies = mutableListOf<String>()
        val client = GmailClient(CountingTokens(), io = StandardTestDispatcher(testScheduler)) { _, _, _, body ->
            bodies += body.orEmpty()
            HttpResponse(200, """{"id":"d1","message":{"id":"m","threadId":"t1"}}""")
        }
        client.createDraft("Subject: hi\r\n\r\nhi", "t1")
        client.updateDraft("d1", "Subject: hi\r\n\r\nhi", "t1")
        assertTrue(bodies[0].contains("""{"message":{"threadId":"t1"}}"""))
        assertTrue(bodies[1].contains("""{"id":"d1","message":{"threadId":"t1"}}"""))
    }
}
