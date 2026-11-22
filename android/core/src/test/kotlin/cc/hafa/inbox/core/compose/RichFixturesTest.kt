package cc.hafa.inbox.core.compose

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs the `markdown` and `write` cases of `shared/testdata/rich-fixtures.json`, so Android reads and writes rich text as web does. */
class RichFixturesTest {
    private fun run(raw: Any?): RichRun {
        val map = raw as Map<*, *>
        val marks = (map["marks"] as List<*>?).orEmpty().map { name -> RichMark.entries.first { it.wire == name } }
        return RichRun(map["text"] as String, marks, map["href"] as String?)
    }

    private fun runs(raw: Any?): List<RichRun> = (raw as List<*>).map(::run)

    private fun block(raw: Any?): RichBlock {
        val map = raw as Map<*, *>
        return when (map["kind"]) {
            "paragraph" -> RichBlock.Paragraph(runs(map["runs"]))
            "heading" -> RichBlock.Heading(map["level"] as Int, runs(map["runs"]))
            "bullet" -> RichBlock.Bullet(runs(map["runs"]))
            "ordered" -> RichBlock.Ordered(map["number"] as Int, runs(map["runs"]))
            "quote" -> RichBlock.Quote(runs(map["runs"]))
            else -> RichBlock.Code(map["text"] as String)
        }
    }

    @Test
    fun markdown() {
        for (case in Fixtures.cases(Fixtures.rich, "markdown")) {
            val name = case["name"] as String
            val blocks = (case["blocks"] as List<*>).map(::block)
            assertEquals(name, blocks, markdownToRich(case["markdown"] as String))
            assertEquals(name, normalizeHtml(case["html"] as String), normalizeHtml(richToHtml(blocks)))
            assertEquals(name, case["text"], richToMarkdown(blocks))
        }
    }

    @Test
    fun write() {
        for (case in Fixtures.cases(Fixtures.rich, "write")) {
            val name = case["name"] as String
            val text = case["text"] as String
            assertEquals(name, text, richToMarkdown((case["blocks"] as List<*>).map(::block)))
            assertEquals(name, (case["reread"] as List<*>).map(::block), markdownToRich(text))
        }
    }
}
