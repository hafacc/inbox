package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** Runs `shared/testdata/todo-typing-fixtures.json`, as shared/src/todos/typing.spec.ts does. */
class TodoTypingFixturesTest {
    private val fixtures = Fixtures.load("todo-typing-fixtures.json")
    private val zone = ZoneId.of(fixtures["zone"] as String)

    @Suppress("UNCHECKED_CAST")
    private val base = fixtures["base"] as Map<String, Any?>

    // Every todo in the fixtures is `base` with some fields replaced.
    @Suppress("UNCHECKED_CAST")
    private fun todo(fields: Any?): Todo {
        val merged = base + (fields as Map<String, Any?>)
        return Todo.read(merged["id"] as String, merged, 0L)
    }

    private fun long(value: Any?): Long = (value as Number).toLong()

    @Test
    fun typing() {
        for (case in Fixtures.cases(fixtures, "typing")) {
            val stored = (case["stored"] as List<*>).map(::todo)

            @Suppress("UNCHECKED_CAST")
            val typing = case["typing"] as Map<String, String>
            val expected = (case["out"] as List<*>).map(::todo)
            assertEquals(case["name"] as String, expected, stored.withTyping(typing, long(case["now"]), zone))
        }
    }

    @Test
    fun undo() {
        for (case in Fixtures.cases(fixtures, "undo")) {
            val current = case["current"]?.let(::todo)
            val after = todo(case["before"]).undone(current, long(case["now"]), zone)
            assertEquals(case["name"] as String, todo(case["out"]), after)
        }
    }

    @Test
    fun ended() {
        for (case in Fixtures.cases(fixtures, "ended")) {
            val after = todo(case["todo"]).editEnded(case["startText"] as String?, long(case["now"]), zone)
            assertEquals(case["name"] as String, todo(case["out"]), after)
        }
    }
}
