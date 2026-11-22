package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/markdown-span-fixtures.json`, so Android reads text as web does. */
class MarkdownSpansTest {
    private val fixtures = Fixtures.load("markdown-span-fixtures.json")

    @Test
    fun spans() {
        for (case in Fixtures.cases(fixtures, "spans")) {
            val expected = (case["spans"] as List<*>).map { raw ->
                val (start, end, kind) = raw as List<*>
                listOf(start, end, kind)
            }
            val actual = markdownSpans(case["text"] as String).map { listOf(it.start, it.end, it.kind.wire) }
            assertEquals(case["name"] as String, expected, actual)
        }
    }
}
