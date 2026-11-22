package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** Runs `shared/testdata/todo-order-fixtures.json`, as shared/src/todos/order.spec.ts does. */
class TodoOrderFixturesTest {
    private val fixtures = Fixtures.load("todo-order-fixtures.json")
    private val zone = ZoneId.of(fixtures["zone"] as String)

    @Suppress("UNCHECKED_CAST")
    private val base = fixtures["base"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun todo(fields: Any?): Todo {
        val merged = base + (fields as Map<String, Any?>)
        return Todo.read(merged["id"] as String, merged, 0L)
    }

    @Test
    fun order() {
        for (case in Fixtures.cases(fixtures, "order")) {
            val name = case["name"] as String
            val todos = (case["todos"] as List<*>).map(::todo)
            assertEquals(name, case["ids"], todos.newestFirst(zone).map { it.id })
            assertEquals(name, case["ids"], todos.reversed().newestFirst(zone).map { it.id })
        }
    }
}
