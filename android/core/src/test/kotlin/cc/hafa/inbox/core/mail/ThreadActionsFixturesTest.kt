package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/thread-action-fixtures.json`, as `shared/src/thread-actions.spec.ts` does on web. */
class ThreadActionsFixturesTest {
    private val fixtures = Fixtures.load("thread-action-fixtures.json")

    // A web action name ("addTodo", "notSpam") as this port's constant name.
    private fun constantName(name: String): String = name.replace(Regex("([A-Z])"), "_$1").uppercase()

    @Test
    fun cases() {
        for (case in Fixtures.cases(fixtures, "cases")) {
            val thread = case["thread"] as Map<*, *>
            val todo = when (thread["todo"]) {
                "add" -> ThreadTodoAction.ADD
                "edit" -> ThreadTodoAction.EDIT
                else -> null
            }
            val actions = threadActions(
                labelIds = (thread["labelIds"] as List<*>).map { it as String },
                snoozed = thread["snoozed"] as Boolean,
                unread = thread["unread"] as Boolean,
                todo = todo,
                hasSender = thread["hasSender"] as Boolean,
            )
            val expected = (case["expect"] as List<*>).map { constantName(it as String) }
            assertEquals(case["name"] as String, expected, actions.map { it.name })
            val places = case["places"] as Map<*, *>
            val placed = threadActionPlaces(actions)
            assertEquals(case["name"] as String, (places["inner"] as List<*>).map { constantName(it as String) }, placed.inner.map { it.name })
            assertEquals(case["name"] as String, (places["outer"] as List<*>).map { constantName(it as String) }, placed.outer.map { it.name })
            assertEquals(case["name"] as String, (places["marker"] as String?)?.let(::constantName), placed.marker?.name)
            assertEquals(case["name"] as String, (places["line"] as List<*>).map { constantName(it as String) }, placed.line.map { it.name })
        }
    }

    @Test
    fun rows() {
        for (case in Fixtures.cases(fixtures, "rows")) {
            val names = { key: String -> (case[key] as List<*>).map { constantName(it as String) } }
            val offered = rowHoldActions(names("actions").map { ThreadAction.valueOf(it) })
            assertEquals(case["name"] as String, names("expect"), offered.map { it.name })
        }
    }
}
