package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/offline-sync-fixtures.json`, as shared/src/offline-sync.spec.ts does. */
class OfflineSyncFixturesTest {
    private val fixtures = Fixtures.load("offline-sync-fixtures.json")

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    private fun mode(raw: Any?): SyncMode = SyncMode.valueOf((raw as String).uppercase())

    private fun state(raw: Any?): SyncState {
        val state = raw as Map<*, *>
        return SyncState(state["historyId"] as String?, state["filled"] as Boolean)
    }

    @Test
    fun mode() {
        for (case in cases("mode")) {
            assertEquals(case["name"] as String, mode(case["expect"]), syncMode(case["historyId"] as String?, case["filled"] as Boolean))
        }
    }

    @Test
    fun history() {
        for (case in cases("history")) {
            val read = HistoryRead.valueOf((case["read"] as String).uppercase())
            assertEquals(case["name"] as String, HistoryNext.valueOf((case["expect"] as String).uppercase()), afterHistory(read))
        }
    }

    @Test
    fun after() {
        for (case in cases("after")) {
            val run = case["run"] as Map<*, *>
            val syncRun = SyncRun(mode(run["mode"]), run["listed"] as Boolean, run["read"] as Boolean, run["reached"] as String?)
            assertEquals(case["name"] as String, state(case["expect"]), syncAfter(state(case["before"]), syncRun))
        }
    }

    @Test
    fun dropsUnlisted() {
        for (case in cases("dropsUnlisted")) {
            val run = case["run"] as Map<*, *>
            assertEquals(case["name"] as String, case["expect"], dropsUnlisted(mode(run["mode"]), run["listed"] as Boolean))
        }
    }
}
