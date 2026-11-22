package cc.hafa.inbox.core.gmail

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TokenCacheTest {
    @Test
    fun `keeps a token until a minute before expiry and shares one fetch`() = runTest {
        var now = 0L
        var fetches = 0
        val cache = TokenCache(
            fetchToken = {
                fetches++
                delay(10)
                AccessToken("token-$fetches", now + 120_000)
            },
            clock = { now },
        )
        val tokens = (1..3).map { async { cache.get() } }.awaitAll()
        assertEquals(listOf("token-1", "token-1", "token-1"), tokens)
        now = 59_000
        assertEquals("token-1", cache.get())
        now = 61_000
        assertEquals("token-2", cache.get())
        cache.invalidate()
        assertEquals("token-3", cache.get())
        assertEquals(3, fetches)
    }
}
