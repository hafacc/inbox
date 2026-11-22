package cc.hafa.inbox.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [runSuspendCatching] catches failures as [runCatching] does, but not cancellation. */
class CatchingTest {
    @Test
    fun `a failure is caught`() {
        val result = runSuspendCatching { error("refused") }
        assertEquals("refused", result.exceptionOrNull()?.message)
    }

    @Test
    fun `a value comes back`() {
        assertEquals(3, runSuspendCatching { 3 }.getOrNull())
    }

    @Test
    fun `cancellation is thrown on, so a canceled coroutine stops`() = runTest {
        val started = CompletableDeferred<Unit>()
        var carriedOn = false
        val job = async {
            runSuspendCatching {
                started.complete(Unit)
                awaitCancellation()
            }
            carriedOn = true
        }
        started.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertFalse("ran on past the cancellation", carriedOn)
    }

    @Test(expected = CancellationException::class)
    fun `a thrown cancellation is not caught`() {
        runSuspendCatching { throw CancellationException("stopped") }
    }
}
