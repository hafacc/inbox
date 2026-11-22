package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** Runs `shared/testdata/todo-fixtures.json`, as shared/src/todos/todo.spec.ts does. */
class TodoFixturesTest {
    private val fixtures = Fixtures.load("todo-fixtures.json")
    private val zone = ZoneId.of(fixtures["zone"] as String)

    @Suppress("UNCHECKED_CAST")
    private val base = fixtures["base"] as Map<String, Any?>

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    // Every todo in the fixtures is `base` with some fields replaced.
    @Suppress("UNCHECKED_CAST")
    private fun todo(fields: Any?): Todo {
        val merged = base + (fields as Map<String, Any?>)
        return Todo.read(merged["id"] as String, merged, 0L)
    }

    private fun long(value: Any?): Long = (value as Number).toLong()

    @Test
    fun read() {
        for (case in cases("read")) {
            @Suppress("UNCHECKED_CAST")
            val data = case["data"] as Map<String, Any?>
            assertEquals(case["name"] as String, todo(case["todo"]), Todo.read(case["id"] as String, data, long(case["now"])))
        }
    }

    @Test
    fun fields() {
        for (case in cases("fields")) {
            assertEquals(case["name"] as String, case["fields"], todo(case["todo"]).fields())
        }
    }

    @Test
    fun filters() {
        for (case in cases("filters")) {
            val shown = TodoFilter.entries.filter { todo(case["todo"]).matches(it, long(case["now"]), zone) }.map { it.name }
            assertEquals(case["name"] as String, case["filters"], shown)
        }
    }

    @Test
    fun keys() {
        for (case in cases("keys")) {
            val subject = todo(case["todo"])
            assertEquals(case["name"] as String, listOf(case["ownKey"], case["activeKey"]), listOf(subject.ownKey(), subject.activeKey(zone)))
        }
    }

    @Test
    fun listDates() {
        for (case in cases("listDates")) {
            assertEquals(case["name"] as String, long(case["listDate"]), todo(case["todo"]).listDate(zone))
        }
    }

    @Test
    fun sort() {
        for (case in cases("sort")) {
            val todos = (case["todos"] as List<*>).map(::todo)
            val filter = TodoFilter.valueOf(case["filter"] as String)
            assertEquals(case["name"] as String, case["ids"], todos.sortedFor(filter, zone).map { it.id })
        }
    }

    @Test
    fun edits() {
        for (case in cases("edits")) {
            val name = case["name"] as String
            val before = todo(case["todo"])
            val now = long(case["now"])
            val after = when (case["edit"]) {
                "text" -> before.withText(case["text"] as String, now, zone)
                "snooze" -> before.withSnoozed(long(case["until"]), now, zone)
                "pin" -> before.withPinToggled(now, zone)
                "done" -> {
                    val (done, spawned) = before.withDone(now, case["newId"] as String, zone)
                    assertEquals(name, case["spawned"]?.let(::todo), spawned)
                    done
                }
                "reactivate" -> before.withReactivated(now, zone)
                "repeat" -> before.withRepeat(requireNotNull(Repeat.readDraft(case["draft"])), now, zone)
                "delete" -> before.withDeleted(now)
                else -> error("unknown edit ${case["edit"]}")
            }
            assertEquals(name, todo(case["out"]), after)
        }
    }
}
