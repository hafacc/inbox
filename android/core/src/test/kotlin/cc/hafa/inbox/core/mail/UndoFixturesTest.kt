package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.label.LabelDelta
import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/undo-fixtures.json`, as shared/src/undo.spec.ts does. */
class UndoFixturesTest {
    private val fixtures = Fixtures.load("undo-fixtures.json")

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    private fun strings(raw: Any?): List<String> = (raw as List<*>).map { it as String }

    // The fixtures name kinds as web does ("notSpam"); Kotlin's are upper snake case.
    private fun kind(raw: Any?): UndoKind = UndoKind.valueOf((raw as String).replace(Regex("([A-Z])"), "_$1").uppercase())

    private data class Item(val id: String, val labelIds: List<String>)

    private fun items(raw: Any?): List<Item> = (raw as List<*>).map { entry ->
        val item = entry as Map<*, *>
        Item(item["id"] as String, strings(item["labelIds"]))
    }

    @Test
    fun labels() {
        for (case in cases("labels")) {
            assertEquals(case["name"] as String, strings(case["expect"]), undoLabels(kind(case["kind"]), strings(case["touched"])))
        }
    }

    @Test
    fun change() {
        for (case in cases("change")) {
            val expected = case["expect"] as Map<*, *>
            assertEquals(
                case["name"] as String,
                LabelDelta(strings(expected["add"]), strings(expected["remove"])),
                undoChange(strings(case["before"]), strings(case["current"]), strings(case["restore"])),
            )
        }
    }

    @Test
    fun snooze() {
        @Suppress("UNCHECKED_CAST")
        val expected = fixtures["snooze"] as Map<String, Boolean>
        for ((name, restores) in expected) assertEquals(name, restores, restoresSnooze(kind(name)))
        assertEquals(UndoKind.entries.size, expected.size)
    }

    @Test
    fun merge() {
        for (case in cases("merge")) {
            assertEquals(case["name"] as String, items(case["expect"]), mergeUndoItems(items(case["earlier"]), items(case["later"]), Item::id))
        }
    }

    @Test
    fun text() {
        for (case in cases("text")) {
            val count = (case["count"] as Number).toInt()
            assertEquals(case["expect"], undoText(kind(case["kind"]), count, case["pinned"] as Boolean))
        }
    }
}
