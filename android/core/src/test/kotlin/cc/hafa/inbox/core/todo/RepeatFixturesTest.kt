package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/repeat-fixtures.json`, as shared/src/todos/repeat.spec.ts does. */
class RepeatFixturesTest {
    private val fixtures = Fixtures.load("repeat-fixtures.json")

    private fun cases(section: String) = Fixtures.cases(fixtures, section)

    private fun rule(value: Any?): Repeat = requireNotNull(Repeat.read(value)) { "unreadable rule $value" }

    @Test
    fun next() {
        for (case in cases("next")) {
            assertEquals(case["name"] as String, case["next"], rule(case["repeat"]).nextOccurrence(case["after"] as String))
        }
    }

    @Test
    fun anchor() {
        for (case in cases("anchor")) {
            val draft = requireNotNull(Repeat.readDraft(case["repeat"]))
            assertEquals(case["name"] as String, case["anchor"], draft.anchored(case["now"] as String).anchor)
        }
    }

    @Test
    fun text() {
        for (case in cases("text")) {
            assertEquals(case["text"], rule(case["repeat"]).text())
        }
    }

    @Test
    fun read() {
        for (case in cases("read")) {
            assertEquals(case["name"] as String, case["out"], Repeat.read(case["in"])?.toMap())
        }
    }
}
