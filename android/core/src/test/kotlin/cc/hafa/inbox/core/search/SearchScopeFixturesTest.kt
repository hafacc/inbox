package cc.hafa.inbox.core.search

import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.testing.Fixtures
import cc.hafa.inbox.core.todo.EmailTodo
import cc.hafa.inbox.core.todo.LabelTags
import cc.hafa.inbox.core.todo.Todo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** Runs `shared/testdata/search-scope-fixtures.json`, as shared/src/search-scope.spec.ts does. */
class SearchScopeFixturesTest {
    private val fixtures = Fixtures.load("search-scope-fixtures.json")
    private val zone = ZoneId.of(fixtures["zone"] as String)
    private val labels = LabelTags(
        (fixtures["labels"] as List<*>).map { entry ->
            val label = entry as Map<*, *>
            GmailLabel(id = label["id"] as String, name = label["name"] as String, type = label["type"] as String)
        },
    )

    @Suppress("UNCHECKED_CAST")
    private val base = fixtures["base"] as Map<String, Any?>

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    @Suppress("UNCHECKED_CAST")
    private fun todo(fields: Any?): Todo {
        val merged = base + (fields as Map<String, Any?>)
        return Todo.read(merged["id"] as String, merged, 0L)
    }

    @Test
    fun scope() {
        for (case in cases("scope")) {
            val expect = case["expect"] as Map<*, *>
            val expected = SearchScope(
                gmailQuery = expect["gmailQuery"] as String?,
                includeSpamTrash = expect["includeSpamTrash"] as Boolean,
                todos = SearchTodos.valueOf((expect["todos"] as String).uppercase()),
                snoozedOnly = expect["snoozedOnly"] as Boolean,
            )
            assertEquals(case["name"] as String, expected, searchScope(case["query"] as String))
        }
    }

    @Test
    fun todos() {
        for (case in cases("todos")) {
            val todos = (case["todos"] as List<*>).map(::todo)
            val found = searchTodos(todos, case["query"] as String, (case["now"] as Number).toLong(), zone, labels)
            assertEquals(case["name"] as String, case["ids"], found.map { it.id })
        }
    }

    @Test
    fun emailTodos() {
        for (case in cases("emailTodos")) {
            val todos = (case["todos"] as List<*>).map { entry ->
                val fields = entry as Map<*, *>
                EmailTodo.new(fields["threadId"] as String, fields["text"] as String, 0L)
            }
            assertEquals(case["name"] as String, case["threadIds"], searchEmailTodos(todos, case["query"] as String, labels))
        }
    }

    @Test
    fun tags() {
        for (case in cases("tags")) {
            assertEquals(case["name"] as String, case["expect"], searchTagTerms(case["query"] as String, labels))
        }
    }
}
