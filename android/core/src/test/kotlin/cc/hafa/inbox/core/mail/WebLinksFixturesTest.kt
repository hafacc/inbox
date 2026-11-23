package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

/** Runs `shared/testdata/web-link-fixtures.json`, as web does. */
class WebLinksFixturesTest {
    private val fixtures = Fixtures.load("web-link-fixtures.json")

    @Test
    fun find() {
        for (case in Fixtures.cases(fixtures, "find")) {
            val text = case["text"] as String
            val found = findWebLinks(text).map { mapOf("text" to text.substring(it.start, it.end), "href" to it.href) }
            assertEquals(case["name"] as String, case["links"], found)
        }
    }

    @Test
    fun address() {
        for (case in Fixtures.cases(fixtures, "address")) {
            assertEquals(case["typed"] as String, case["expect"], linkAddress(case["typed"] as String))
        }
    }

    @Test
    fun safe() {
        for (case in Fixtures.cases(fixtures, "safe")) {
            assertEquals(case["href"] as String, case["expect"], safeHref(case["href"] as String))
        }
    }

    @Test
    fun finished() {
        for (case in Fixtures.cases(fixtures, "finished")) {
            val before = case["before"] as String
            val link = finishedWebLink(before)
            assertEquals(before, case["link"], link?.let { mapOf("text" to before.substring(it.start, it.end), "href" to it.href) })
        }
    }

    @Test
    fun pieces() {
        assertEquals(
            listOf(
                WebLinkPiece("see ", null),
                WebLinkPiece("example.com/x", "https://example.com/x"),
                WebLinkPiece(", then", null),
            ),
            webLinkPieces("see example.com/x, then"),
        )
        assertEquals(emptyList<WebLinkPiece>(), webLinkPieces(""))
    }
}
