package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/offline-queue-fixtures.json`, so the queue folds and replays as on web. */
class OfflineQueueTest {
    private val fixtures = Fixtures.load("offline-queue-fixtures.json")

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    @Suppress("UNCHECKED_CAST")
    private fun strings(raw: Any?): List<String> = raw as List<String>

    private fun change(raw: Any?): LabelChange {
        val map = raw as Map<*, *>
        return LabelChange((map["seq"] as Number).toLong(), map["threadId"] as String, strings(map["add"]), strings(map["remove"]))
    }

    private fun changes(raw: Any?): List<LabelChange> = (raw as List<*>).map(::change)

    private fun enqueueAll(queue: List<LabelChange>, raw: Any?): List<LabelChange> =
        (raw as List<*>).fold(queue) { queued, added ->
            val map = added as Map<*, *>
            enqueue(queued, map["threadId"] as String, strings(map["add"]), strings(map["remove"]))
        }

    @Test
    fun enqueue() {
        for (case in cases("enqueue")) {
            val queue = enqueueAll(changes(case["queue"]), case["add"])
            assertEquals(case["name"] as String, (case["seqs"] as List<*>).map { (it as Number).toLong() }, queue.map { it.seq })
        }
    }

    @Test
    fun applyChange() {
        for (case in cases("applyChange")) {
            assertEquals(case["name"] as String, case["expect"], applyChange(strings(case["labels"]), strings(case["add"]), strings(case["remove"])))
        }
    }

    @Test
    fun compact() {
        for (case in cases("compact")) {
            assertEquals(case["name"] as String, changes(case["expect"]), compact(changes(case["queue"])))
        }
    }

    @Test
    fun replay() = runTest {
        for (case in cases("replay")) {
            var queue = changes(case["queue"])
            val later = strings(case["later"])
            val meanwhile = case["queuedWhileSending"] as Map<*, *>
            val queuedFor = mutableSetOf<String>()
            val sent = mutableListOf<String>()
            val drained = replay({ queue }) { change ->
                sent += change.threadId
                if (change.threadId in meanwhile && queuedFor.add(change.threadId)) {
                    queue = enqueueAll(queue, meanwhile[change.threadId])
                }
                if (change.threadId in later) {
                    ReplayStep.LATER
                } else {
                    queue = queue.filter { it.threadId != change.threadId || it.seq > change.seq }
                    ReplayStep.SETTLED
                }
            }
            assertEquals(case["name"] as String, listOf(strings(case["sent"]), case["drained"], (case["left"] as List<*>).map { (it as Number).toLong() }), listOf(sent, drained, queue.map { it.seq }))
        }
    }

    @Test
    fun `a change remembers whether a notification made it`() {
        var queue = enqueue(emptyList(), "a", emptyList(), listOf("INBOX"), fromNotification = true)
        queue = enqueue(queue, "b", emptyList(), listOf("INBOX"))
        assertEquals(listOf(true, false), queue.map { it.fromNotification })
    }
}
