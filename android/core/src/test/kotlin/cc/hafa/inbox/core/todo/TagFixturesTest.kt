package cc.hafa.inbox.core.todo

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/** Runs `shared/testdata/tag-fixtures.json` and `tag-complete-fixtures.json`, as shared/src/todos/tags.spec.ts does. */
class TagFixturesTest {
    private val tags = Fixtures.load("tag-fixtures.json")
    private val completion = Fixtures.load("tag-complete-fixtures.json")

    @Test
    fun parse() {
        for (case in Fixtures.cases(tags, "cases")) {
            val expected = (case["tags"] as List<*>).map { pair -> (pair as List<*>).map { (it as Number).toInt() } }
            assertEquals(case["name"] as String, expected, Tags.parse(case["in"] as String).map { listOf(it.start, it.end) })
        }
    }

    @Test
    fun typing() {
        for (case in Fixtures.cases(completion, "typing")) {
            val expected = (case["out"] as List<*>?)?.let { (start, prefix) -> listOf((start as Number).toInt(), prefix) }
            val typed = Tags.typing(case["in"] as String, (case["caret"] as Number).toInt())
            assertEquals(case["name"] as String, expected, typed?.let { listOf(it.start, it.prefix) })
        }
    }

    @Test
    fun suggest() {
        for (case in Fixtures.cases(completion, "suggest")) {
            val counts = Tags.count((case["texts"] as List<*>).map { it as String })
            val limit = (case["limit"] as Number?)?.toInt() ?: 5
            assertEquals(case["name"] as String, case["out"], Tags.suggest(counts, case["own"] as String, case["prefix"] as String, limit))
        }
    }

    @Test
    fun complete() {
        for (case in Fixtures.cases(completion, "complete")) {
            val name = case["name"] as String
            val text = case["in"] as String
            val caret = (case["caret"] as Number).toInt()
            val typed = Tags.typing(text, caret)
            assertNotNull(name, typed)
            val (completed, newCaret) = Tags.complete(text, requireNotNull(typed), caret, case["pick"] as String)
            assertEquals(name, listOf(case["out"], (case["outCaret"] as Number).toInt()), listOf(completed, newCaret))
        }
    }
}
