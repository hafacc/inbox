package cc.hafa.inbox.core.gmail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/gmail-pace-fixtures.json`, as shared/src/gmail-pace.spec.ts does. */
class GmailPaceFixturesTest {
    private val fixtures = Fixtures.load("gmail-pace-fixtures.json")

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    private fun long(value: Any?): Long = (value as Number).toLong()

    @Test
    fun usageLimit() {
        for (case in cases("usageLimit")) {
            val status = (case["status"] as Number).toInt()
            val reason = case["reason"] as String?
            assertEquals("$status $reason", case["limited"], isUsageLimit(status, reason))
        }
    }

    @Test
    fun backoff() {
        for (case in cases("backoff")) {
            val ms = backoffMs((case["strike"] as Number).toInt(), (case["retryAfter"] as Number?)?.toLong(), (case["jitter"] as Number).toDouble())
            assertEquals(case["name"] as String, long(case["ms"]), ms)
        }
    }

    @Test
    fun retry() {
        for (case in cases("retry")) {
            assertEquals(case.toString(), case["retry"], retriesAfterLimit(case["isRead"] as Boolean, (case["attempt"] as Number).toInt()))
        }
    }

    @Test
    fun next() {
        for (case in cases("next")) {
            val waiting = (case["waiting"] as List<*>).map { entry ->
                val call = entry as Map<*, *>
                WaitingCall(call["id"] as String, CallPriority.valueOf((call["priority"] as String).uppercase()), long(call["order"]))
            }
            val state = case["state"] as Map<*, *>
            val pace = PaceState(
                running = (state["running"] as Number).toInt(),
                runningSaving = (state["runningSaving"] as Number).toInt(),
                pausedUntil = long(state["pausedUntil"]),
                savingStartedAt = long(state["savingStartedAt"]),
                savingHeldUntil = long(state["savingHeldUntil"]),
            )
            val wakeAt = (case["wakeAt"] as Number?)?.toLong()
            assertEquals(case["name"] as String, Turn(case["id"] as String?, wakeAt), nextCall(waiting, pace, long(case["now"])))
        }
    }

    @Test
    fun savingHold() {
        for (case in cases("savingHold")) {
            assertEquals(case.toString(), long(case["heldUntil"]), savingHeldUntil(long(case["pausedUntil"])))
        }
    }

    private fun connectionEvent(raw: Map<*, *>): ConnectionEvent = when (val kind = raw["kind"]) {
        "answered" -> ConnectionEvent.Answered((raw["status"] as Number).toInt())
        "unanswered" -> ConnectionEvent.Unanswered
        "timedOut" -> ConnectionEvent.TimedOut
        "networkUp" -> ConnectionEvent.NetworkUp
        "networkDown" -> ConnectionEvent.NetworkDown
        else -> error("unknown event $kind")
    }

    @Test
    fun offline() {
        for (case in cases("offline")) {
            val event = connectionEvent(case["event"] as Map<*, *>)
            assertEquals(case["name"] as String, case["expect"], offlineAfter(case["offline"] as Boolean, event))
        }
    }
}
