package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import cc.hafa.inbox.core.todo.EmailTodo
import cc.hafa.inbox.core.todo.Todo
import cc.hafa.inbox.core.todo.emailTodosByThread
import cc.hafa.inbox.core.todo.rowEmailTodo
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

    // The fixture's todos, and the labels each one's tags name.
    private fun todos(fixture: Any?): Pair<List<Todo>, (Todo) -> List<String>> {
        val cases = (fixture as List<*>?).orEmpty().map { it as Map<*, *> }
        val labels = cases.associate { it["id"] as String to strings(it["labels"]) }
        val todos = cases.map { todo ->
            val id = todo["id"] as String
            Todo.new(id, id, epoch(todo["date"])).copy(pinned = todo["pinned"] == true)
        }
        return todos to { todo -> labels[todo.id].orEmpty() }
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

    // A thread as the shape writes it: `id+todo` when its email has a todo, which shows inside its row.
    private fun threadShape(row: Row, byThread: Map<String, EmailTodo>): String =
        if (rowEmailTodo(byThread, listOf(row.thread.id)) == null) row.thread.id else "${row.thread.id}+todo"

    private fun shape(sections: List<SectionModel>, byThread: Map<String, EmailTodo> = emptyMap()): List<Map<String, Any>> = sections.map { section ->
        mapOf(
            "key" to section.key,
            "entries" to section.entries.map { entry ->
                when (entry) {
                    is ListEntry.Single -> threadShape(entry.thread, byThread)
                    is ListEntry.Group -> "${entry.labelId}[${entry.items.joinToString(",") { item ->
                        when (item) {
                            is ListEntry.Single -> threadShape(item.thread, byThread)
                            is TodoEntry -> "todo:${item.todo.id}"
                            is ListEntry.Group -> item.labelId
                        }
                    }}]"
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
            val threads = (case["threads"] as List<*>).map { it as Map<*, *> }
            val rows = threads.map(::row)
            val (todos, todoLabels) = todos(case["todos"])
            // Each email's todo's tag labels, for the threads that have a todo.
            val rowTodoLabels = threads.filter { it["todoLabels"] != null }.associate { it["id"] as String to strings(it["todoLabels"]) }
            val todoThreads = case["todoThreads"]?.let(::strings).orEmpty() + rowTodoLabels.keys
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
                        todos,
                        exhausted = case["exhausted"]?.let(::strings).orEmpty().toSet(),
                        todoLabels = todoLabels,
                        rowTodoLabels = { row -> rowTodoLabels[row.thread.id].orEmpty() },
                    ),
                    emailTodosByThread(todoThreads.map { EmailTodo.new(it, "on $it", 0L) }),
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
