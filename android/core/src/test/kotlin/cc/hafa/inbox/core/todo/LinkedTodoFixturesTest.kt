package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/linked-todo-fixtures.json`, as shared/src/todos/linked.spec.ts does. */
class LinkedTodoFixturesTest {
    private val fixtures = Fixtures.load("linked-todo-fixtures.json")

    @Suppress("UNCHECKED_CAST")
    private val base = fixtures["base"] as Map<String, Any?>

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    @Suppress("UNCHECKED_CAST")
    private fun map(value: Any?): Map<String, Any?> = value as Map<String, Any?>

    private fun todo(fields: Any?): EmailTodo {
        val merged = base + map(fields)
        return EmailTodo.read(merged["id"] as String, merged, 0L)
    }

    private fun long(value: Any?): Long = (value as Number).toLong()

    private fun byThread(case: Map<String, Any?>): Map<String, EmailTodo> =
        emailTodosByThread((case["todos"] as List<*>).map(::todo))

    private fun strings(value: Any?): List<String> = (value as List<*>).map { it as String }

    private fun plain(todo: EmailTodo): Map<String, Any?> = mapOf("id" to todo.id) + todo.fields()

    @Test
    fun read() {
        for (case in cases("read")) {
            val name = case["name"] as String
            val data = map(case["data"])
            val email = EmailTodo.isDoc(data)
            assertEquals(name, case["email"], email)
            if (email) {
                val now = (case["now"] as? Number)?.toLong() ?: 0L
                assertEquals(name, map(case["todo"]), plain(EmailTodo.read(case["id"] as String, data, now)))
            }
        }
    }

    @Test
    fun fields() {
        for (case in cases("fields")) {
            val made = EmailTodo.new(case["threadId"] as String, case["text"] as String, long(case["now"]))
            assertEquals(case["name"] as String, map(case["fields"]), made.fields())
        }
    }

    @Test
    fun text() {
        for (case in cases("text")) {
            val after = todo(case["todo"]).withText(case["text"] as String)
            assertEquals(case["name"] as String, plain(todo(case["out"])), plain(after))
        }
    }

    @Test
    fun ended() {
        for (case in cases("ended")) {
            val after = todo(case["todo"]).editEnded(case["startText"] as String?, long(case["now"]))
            assertEquals(case["name"] as String, plain(todo(case["out"])), plain(after))
        }
    }

    @Test
    fun delete() {
        for (case in cases("delete")) {
            val after = todo(case["todo"]).withDeleted(long(case["now"]))
            assertEquals(case["name"] as String, plain(todo(case["out"])), plain(after))
        }
    }

    @Test
    fun row() {
        for (case in cases("row")) {
            assertEquals(case["name"] as String, case["id"], rowEmailTodo(byThread(case), strings(case["threadIds"]))?.id)
        }
    }

    @Test
    fun edit() {
        for (case in cases("edit")) {
            val name = case["name"] as String
            val edit = emailTodoToEdit(byThread(case), strings(case["threadIds"]), long(case["now"]))
            assertEquals(name, case["id"], edit.todo.id)
            assertEquals(name, case["fresh"], edit.fresh)
        }
    }
}
