package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/selection-fixtures.json`, so selecting and acting on several threads matches web. */
class SelectionFixturesTest {
    @Suppress("UNCHECKED_CAST")
    private fun strings(value: Any?): List<String> = value as List<String>

    private fun threads(value: Any?): List<SelectedThread> = (value as List<*>).map { entry ->
        val thread = entry as Map<*, *>
        SelectedThread(thread["id"] as String, strings(thread["labelIds"]), SelectedStatus.valueOf((thread["status"] as String).uppercase()))
    }

    private fun toggle(value: Any?): SetToggle {
        val toggle = value as Map<*, *>
        return SetToggle(toggle["on"] as Boolean, strings(toggle["ids"]))
    }

    @Test
    fun toggle() {
        for (case in Fixtures.cases(Fixtures.selection, "toggle")) {
            assertEquals(case["name"] as String, strings(case["expect"]), toggleSelected(strings(case["selected"]), case["id"] as String))
        }
    }

    @Test
    fun range() {
        for (case in Fixtures.cases(Fixtures.selection, "range")) {
            assertEquals(
                case["name"] as String,
                strings(case["expect"]),
                selectRange(strings(case["selected"]), strings(case["order"]), case["anchor"] as String?, case["id"] as String),
            )
        }
    }

    @Test
    fun keepShown() {
        for (case in Fixtures.cases(Fixtures.selection, "keepShown")) {
            assertEquals(case["name"] as String, strings(case["expect"]), keepShown(strings(case["selected"]), strings(case["shown"])))
        }
    }

    @Test
    fun after() {
        val after = Fixtures.selection["after"] as Map<*, *>
        assertEquals(SetAction.entries.size, after.size)
        for ((action, want) in after) {
            val kind = SetAction.valueOf((action as String).uppercase())
            assertEquals(action, want == "clear", clearsSelection(kind))
        }
    }

    @Test
    fun plan() {
        for (case in Fixtures.cases(Fixtures.selection, "plan")) {
            val expect = case["expect"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                SelectionPlan(
                    done = strings(expect["done"]),
                    wake = strings(expect["wake"]),
                    pin = toggle(expect["pin"]),
                    unread = toggle(expect["unread"]),
                    spam = toggle(expect["spam"]),
                ),
                selectionPlan(threads(case["threads"])),
            )
        }
    }

    @Test
    fun label() {
        for (case in Fixtures.cases(Fixtures.selection, "label")) {
            val expect = case["expect"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                LabelChoice(LabelState.valueOf((expect["state"] as String).uppercase()), strings(expect["add"]), strings(expect["remove"])),
                labelChoice(threads(case["threads"]), case["labelId"] as String),
            )
        }
    }

    @Test
    fun category() {
        for (case in Fixtures.cases(Fixtures.selection, "category")) {
            assertEquals(case["name"] as String, case["expect"], selectionCategory(threads(case["threads"])))
        }
    }

    @Test
    fun todos() {
        for (case in Fixtures.cases(Fixtures.selection, "todos")) {
            val expect = case["expect"] as Map<*, *>
            val pin = expect["pin"] as Map<*, *>
            val todos = (case["todos"] as List<*>).map { entry ->
                val todo = entry as Map<*, *>
                SelectedTodo(todo["id"] as String, todo["done"] as Boolean, todo["pinned"] as Boolean)
            }
            assertEquals(
                case["name"] as String,
                TodoSelectionPlan(
                    done = strings(expect["done"]),
                    snooze = strings(expect["snooze"]),
                    pin = MixedPin(pin["on"] as Boolean, strings(pin["threads"]), strings(pin["todos"])),
                ),
                todoSelectionPlan(threads(case["threads"]), todos),
            )
        }
    }

    @Test
    fun all() {
        for (case in Fixtures.cases(Fixtures.selection, "all")) {
            val name = case["name"] as String
            assertEquals(name, case["all"], allSelected(strings(case["selected"]), strings(case["shown"])))
            assertEquals(name, strings(case["expect"]), selectAll(strings(case["selected"]), strings(case["shown"])))
        }
    }

    @Test
    fun actions() {
        for (case in Fixtures.cases(Fixtures.selection, "actions")) {
            assertEquals(
                case["name"] as String,
                strings(case["expect"]).map { SelectionAction.valueOf(it.uppercase()) },
                selectionActions((case["threads"] as Number).toInt(), (case["todos"] as Number).toInt()),
            )
        }
    }

    @Test
    fun disc() {
        for (case in Fixtures.cases(Fixtures.selection, "disc")) {
            val actions = { key: String -> strings(case[key]).map { SelectionDiscAction.valueOf(it.uppercase()) } }
            assertEquals(case["name"] as String, SelectionDisc(actions("inner"), actions("outer")), selectionDisc(actions("available")))
        }
    }
}
