package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/todo-page-fixtures.json`, so lists page their todos as web's do. */
class TodoPagesFixturesTest {
    private val fixtures = Fixtures.load("todo-page-fixtures.json")

    private fun int(value: Any?): Int = (value as Number).toInt()

    // A todo is Todo.new(id, id, 0) with the fields given.
    @Suppress("UNCHECKED_CAST")
    private fun todo(fields: Any?): Todo {
        val given = fields as Map<String, Any?>
        val id = given["id"] as String
        return Todo.read(id, Todo.new(id, id, 0).fields() + given.filterKeys { it != "id" }, 0L)
    }

    @Test
    fun pageSize() {
        assertEquals(int(fixtures["pageSize"]), TODO_PAGE)
    }

    @Test
    fun limits() {
        for (case in Fixtures.cases(fixtures, "limits")) {
            assertEquals("pages ${case["pages"]}", int(case["limit"]), todoLimit(int(case["pages"])))
        }
    }

    @Test
    fun pages() {
        for (case in Fixtures.cases(fixtures, "pages")) {
            val todos = List(int(case["count"])) { Todo.new("t$it", "t$it", 0) }
            val paged = pageTodos(todos, todoLimit(int(case["pages"])))
            assertEquals(case["name"] as String, listOf(int(case["shown"]), int(case["hidden"])), listOf(paged.shown.size, paged.hidden))
        }
    }

    @Test
    fun loaded() {
        for (case in Fixtures.cases(fixtures, "loaded")) {
            val todos = (case["todos"] as List<*>).map(::todo)
            val shown = todosLoaded(todos, (case["oldestShown"] as Number).toLong(), case["more"] as Boolean)
            assertEquals(case["name"] as String, case["expect"], shown.map { it.id })
        }
    }
}
