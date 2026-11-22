package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import cc.hafa.inbox.core.todo.Todo
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/focus-fixtures.json`, so the important mark and focus mode match web's. */
class FocusFixturesTest {
    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?): List<String> = value as List<String>

    private fun entry(fixture: Map<*, *>): SectionEntry<Row> {
        val bundle = fixture["bundle"] as String?
        val todo = fixture["todo"] as String?
        return if (bundle != null) {
            ListEntry.Group(bundle, strings(fixture["threads"]).map { row(it, emptyList()) })
        } else if (todo != null) {
            TodoEntry(Todo.new(todo, todo, 0).copy(pinned = fixture["pinned"] as Boolean))
        } else {
            ListEntry.Single(row(fixture["id"] as String, strings(fixture["labels"])))
        }
    }

    private fun row(id: String, labels: List<String>): Row {
        val thread = ThreadSummary(
            id = id,
            historyId = "1",
            subject = "",
            snippet = "",
            senders = emptyList(),
            messageCount = 1,
            lastDate = 0,
            labelIds = labels,
            unread = false,
        )
        return Row(thread, SnoozeStatus.NONE, null, 0)
    }

    @Test
    fun mark() {
        for (case in Fixtures.cases(Fixtures.focus, "mark")) {
            val change = importantChange(strings(case["labelIds"]), case["important"] as Boolean)
            assertEquals(case["name"] as String, case["expect"], mapOf("add" to change.add, "remove" to change.remove))
        }
    }

    @Test
    fun sections() {
        for (case in Fixtures.cases(Fixtures.focus, "sections")) {
            val sections = (case["sections"] as List<*>).map { section ->
                section as Map<*, *>
                SectionModel(
                    key = section["key"] as String,
                    title = "",
                    entries = (section["entries"] as List<*>).map { entry(it as Map<*, *>) },
                    canDoneAll = false,
                )
            }
            @Suppress("UNCHECKED_CAST")
            val heldPins = case["heldPins"] as Map<String, Boolean>? ?: emptyMap()
            val result = focusSections(sections, case["openId"] as String?, heldPins)
            val shape = mapOf(
                "sections" to result.sections.map { section ->
                    mapOf(
                        "key" to section.key,
                        "entries" to section.entries.map { entry ->
                            when (entry) {
                                is ListEntry.Single -> entry.thread.thread.id
                                is ListEntry.Group -> entry.labelId
                                is TodoEntry -> "todo:${entry.todo.id}"
                            }
                        },
                    )
                },
                "hidden" to result.hidden,
            )
            assertEquals(case["name"] as String, case["expect"], shape)
        }
    }

    @Test
    fun query() {
        for (case in Fixtures.cases(Fixtures.focus, "query")) {
            assertEquals(case["name"] as String, case["expect"], focusQuery(case["query"] as String))
        }
    }
}
