package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/thread-join-fixtures.json`, so a list joins split encrypted replies as web's does. */
class ThreadJoinsFixturesTest {
    private val fixtures = Fixtures.load("thread-join-fixtures.json")

    private fun strings(value: Any?): List<String> = (value as List<*>).map { it as String }

    private fun long(value: Any?): Long = (value as Number).toLong()

    private fun thread(value: Any?): JoinThread {
        val thread = value as Map<*, *>
        return JoinThread(
            id = thread["id"] as String,
            hiddenSubject = thread["hiddenSubject"] as Boolean,
            messageIds = strings(thread["messageIds"]),
            answers = strings(thread["answers"]),
            firstDate = long(thread["firstDate"]),
        )
    }

    private fun summary(value: Any?): ThreadSummary {
        val summary = value as Map<*, *>
        return ThreadSummary(
            id = summary["id"] as String,
            historyId = summary["historyId"] as String,
            subject = summary["subject"] as String,
            snippet = summary["snippet"] as String,
            senders = (summary["senders"] as List<*>).map { person ->
                val fields = person as Map<*, *>
                Person(fields["name"] as String, fields["email"] as String)
            },
            messageCount = (summary["messageCount"] as Number).toInt(),
            lastDate = long(summary["lastDate"]),
            labelIds = strings(summary["labelIds"]),
            unread = summary["unread"] as Boolean,
            sentDate = long(summary["sentDate"]),
        )
    }

    private fun status(value: Any?): SnoozeStatus = SnoozeStatus.valueOf((value as String).uppercase())

    @Test
    fun joins() {
        for (case in Fixtures.cases(fixtures, "joins")) {
            val want = (case["expect"] as List<*>).map(::strings)
            assertEquals(case["name"] as String, want, joinThreads((case["threads"] as List<*>).map(::thread)))
        }
    }

    @Test
    fun summaries() {
        for (case in Fixtures.cases(fixtures, "summaries")) {
            val joined = joinSummaries((case["members"] as List<*>).map(::summary))
            assertEquals(case["name"] as String, summary(case["expect"]), joined)
        }
    }

    @Test
    fun snooze() {
        for (case in Fixtures.cases(fixtures, "snooze")) {
            val members = (case["members"] as List<*>).map { entry ->
                val member = entry as Map<*, *>
                JoinSnoozeMember(
                    status = status(member["status"]),
                    snoozeUntil = member["snoozeUntil"]?.let(::long),
                    watermark = member["watermark"] as String?,
                    historyId = member["historyId"] as String,
                    inInbox = member["inInbox"] as Boolean,
                )
            }
            val expect = case["expect"] as Map<*, *>
            val want = JoinedSnooze(status(expect["status"]), expect["snoozeUntil"]?.let(::long))
            assertEquals(case["name"] as String, want, joinedSnooze(members))
        }
    }

    @Test
    fun answered() {
        for (case in Fixtures.cases(fixtures, "answered")) {
            assertEquals(case["name"] as String, case["search"], answeredSearch(strings(case["answers"])))
        }
    }
}
