package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** Runs `shared/testdata/todos-view-fixtures.json`, so the Todos view lists what web's does. */
class TodosViewFixturesTest {
    private val fixtures = Fixtures.load("todos-view-fixtures.json")
    private val zone = ZoneId.of(fixtures["zone"] as String)
    private val now = (fixtures["now"] as Number).toLong()

    // A todo is Todo.new(id, id, 0) with the fields given.
    @Suppress("UNCHECKED_CAST")
    private fun todo(fields: Any?): Todo {
        val given = fields as Map<String, Any?>
        val id = given["id"] as String
        return Todo.read(id, Todo.new(id, id, 0).fields() + given.filterKeys { it != "id" }, 0L)
    }

    @Suppress("UNCHECKED_CAST")
    private fun email(fields: Any?): TodosViewEmail<String> {
        val given = fields as Map<String, Any?>
        val threadId = given["threadId"] as String
        return TodosViewEmail(
            email = threadId,
            todo = EmailTodo.new(threadId, threadId, 0),
            inbox = given["inbox"] as Boolean,
            snoozed = given["snoozed"] as Boolean,
            pinned = given["pinned"] as Boolean,
            sortDate = (given["sortDate"] as Number).toLong(),
            held = (given["held"] as Map<*, *>?)?.let { TodosViewPlace((it["date"] as Number).toLong(), it["pinned"] as Boolean) },
        )
    }

    private fun headings(buckets: List<TodosViewBucket<String>>): List<Map<String, Any>> = buckets.map { bucket ->
        mapOf(
            "label" to bucket.label,
            "rows" to bucket.entries.map {
                when (it) {
                    is TodosViewEntry.Own -> "todo:${it.todo.id}"
                    is TodosViewEntry.OnEmail -> "email:${it.email}"
                }
            },
        )
    }

    @Test
    fun cases() {
        for (case in Fixtures.cases(fixtures, "cases")) {
            val name = case["name"] as String
            val buckets = todosView((case["todos"] as List<*>).map(::todo), (case["emails"] as List<*>).map(::email), now, zone)
            assertEquals(name, case["expect"], headings(buckets))
            assertEquals(name, (case["count"] as Number).toInt(), todosViewCount(buckets))
            val limit = case["limit"] as Number?
            if (limit != null) {
                assertEquals(name, case["limited"], headings(limitTodosView(buckets, limit.toInt())))
            }
        }
    }

    @Test
    fun places() {
        for (case in Fixtures.cases(fixtures, "places")) {
            val expect = (case["expect"] as Map<*, *>?)?.let { TodosViewPlace((it["date"] as Number).toLong(), it["pinned"] as Boolean) }
            assertEquals(case["name"] as String, expect, todosViewPlace(email(case["email"])))
        }
    }
}
