package cc.hafa.inbox.mail

import cc.hafa.inbox.core.mail.SyncMode
import cc.hafa.inbox.core.mail.SyncRun
import cc.hafa.inbox.core.mail.SyncState
import org.junit.Assert.assertEquals
import org.junit.Test

/** The saved copy's history id only moves forward when a sync or rebuild settles. */
class OfflineCopySettleTest {
    @Test
    fun `a rebuild keeps the newer id a history sync beside it reached`() {
        val before = SyncState(historyId = "120", filled = true)
        val after = settledState(before, SyncRun(SyncMode.REBUILD, listed = true, read = true, reached = "100"))
        assertEquals(SyncState(historyId = "120", filled = true), after)
    }

    @Test
    fun `a rebuild after an expired id moves to the id it reached`() {
        val before = SyncState(historyId = "5", filled = true)
        val after = settledState(before, SyncRun(SyncMode.REBUILD, listed = true, read = true, reached = "100"))
        assertEquals("100", after.historyId)
    }

    @Test
    fun `the first fill is marked filled at the id it reached`() {
        val after = settledState(SyncState(historyId = null, filled = false), SyncRun(SyncMode.REBUILD, listed = true, read = true, reached = "100"))
        assertEquals(SyncState(historyId = "100", filled = true), after)
    }

    @Test
    fun `a sync cut short changes nothing`() {
        val before = SyncState(historyId = "120", filled = true)
        assertEquals(before, settledState(before, SyncRun(SyncMode.INCREMENTAL, listed = false, read = true, reached = "130")))
    }
}
