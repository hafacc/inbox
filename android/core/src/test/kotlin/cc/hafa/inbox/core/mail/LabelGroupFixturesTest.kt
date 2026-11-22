package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** Runs `shared/testdata/label-group-fixtures.json`, so inbox bundles match web's. */
class LabelGroupFixturesTest {
    private fun epoch(local: Any?): Long = LocalDateTime.parse(local as String).atZone(NEW_YORK).toInstant().toEpochMilli()

    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?): List<String> = value as List<String>

    private fun row(fixture: Map<*, *>): Row {
        val date = epoch(fixture["date"])
        val thread = ThreadSummary(
            id = fixture["id"] as String,
            historyId = "1",
            subject = "",
            snippet = "",
            senders = emptyList(),
            messageCount = 1,
            lastDate = date,
            labelIds = strings(fixture["labels"]),
            unread = false,
        )
        return Row(thread, SnoozeStatus.NONE, null, date)
    }

    private fun held(fixture: Any?): HeldPlaces {
        val held = fixture as Map<*, *>? ?: return HeldPlaces()
        val threads = (held["threads"] as Map<*, *>).entries.associate { (id, place) ->
            place as Map<*, *>
            id as String to ThreadPlace(epoch(place["date"]), place["pinned"] as Boolean, place["single"] as Boolean)
        }
        val bundles = (held["bundles"] as Map<*, *>).entries.associate { (labelId, date) -> labelId as String to epoch(date) }
        val pins = (held["pins"] as Map<*, *>?).orEmpty().entries.associate { (id, pinned) -> id as String to pinned as Boolean }
        return HeldPlaces(threads, bundles, pins)
    }

    private fun shape(sections: List<SectionModel>): List<Map<String, Any>> = sections.map { section ->
        mapOf(
            "key" to section.key,
            "entries" to section.entries.map { entry ->
                when (entry) {
                    is ListEntry.Single -> entry.thread.thread.id
                    is ListEntry.Group -> "${entry.labelId}[${entry.threads.joinToString(",") { it.thread.id }}]"
                    is TodoEntry -> "todo:${entry.todo.id}"
                }
            },
        )
    }

    @Test
    fun preview() {
        for (case in Fixtures.cases(Fixtures.labelGroups, "preview")) {
            val threads = case["threads"] as Int
            val shown = groupShownCount(threads, case["showAll"] as Boolean)
            val expected = case["expect"] as Map<*, *>
            assertEquals(case["name"] as String, listOf(expected["shown"], expected["hidden"]), listOf(shown, threads - shown))
        }
    }

    @Test
    fun inbox() {
        val now = epoch(Fixtures.labelGroups["now"])
        for (case in Fixtures.cases(Fixtures.labelGroups, "inbox")) {
            val rows = (case["threads"] as List<*>).map { row(it as Map<*, *>) }
            assertEquals(
                case["name"] as String,
                case["sections"],
                shape(
                    inboxSections(
                        rows,
                        now,
                        strings(case["bundleLabels"]),
                        NEW_YORK,
                        held(case["held"]),
                        exhausted = case["exhausted"]?.let(::strings).orEmpty().toSet(),
                        hasEmailTodo = case["todoThreads"]?.let(::strings).orEmpty().toSet().let { withTodo -> { row -> row.thread.id in withTodo } },
                    ),
                ),
            )
        }
    }

    @Test
    fun plain() {
        val now = epoch(Fixtures.labelGroups["now"])
        for (case in Fixtures.cases(Fixtures.labelGroups, "plain")) {
            val rows = (case["threads"] as List<*>).map { row(it as Map<*, *>) }
            assertEquals(case["name"] as String, case["sections"], shape(plainSections(rows, now, NEW_YORK, held(case["held"]))))
        }
    }
}
