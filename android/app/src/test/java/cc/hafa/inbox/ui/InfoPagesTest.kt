package cc.hafa.inbox.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The privacy and support pages read from their Markdown, as the app shows them. */
class InfoPagesTest {
    private fun plain(spans: List<InfoSpan>) = spans.joinToString("") { it.text }

    @Test
    fun headingsParagraphsAndLists() {
        val blocks = parseInfoPage(
            """
            # Title

            First line
            runs on.

            ## Section

            - **One** item
              that runs on.
            - Two
            After the list.
            """.trimIndent(),
        )
        assertEquals(4, blocks.size)
        assertEquals(InfoBlock.Heading(1, listOf(InfoSpan("Title"))), blocks[0])
        assertEquals(InfoBlock.Paragraph(listOf(InfoSpan("First line runs on."))), blocks[1])
        assertEquals(InfoBlock.Heading(2, listOf(InfoSpan("Section"))), blocks[2])
        val list = blocks[3] as InfoBlock.Bullets
        assertEquals(listOf(InfoSpan("One", bold = true), InfoSpan(" item that runs on.")), list.items[0])
        assertEquals("Two After the list.", plain(list.items[1]))
    }

    @Test
    fun linksOpenAsOnTheWeb() {
        val spans = infoSpans(
            "See the [privacy policy](/privacy/), [GitHub](https://github.com/hafacc/inbox), " +
                "https://myaccount.google.com/connections. Or email support@inbox.hafa.cc.",
        )
        val links = spans.filter { it.href != null }.map { it.text to it.href }
        assertEquals(
            listOf(
                "privacy policy" to "https://inbox.hafa.cc/privacy/",
                "GitHub" to "https://github.com/hafacc/inbox",
                "https://myaccount.google.com/connections" to "https://myaccount.google.com/connections",
                "support@inbox.hafa.cc" to "mailto:support@inbox.hafa.cc",
            ),
            links,
        )
        assertEquals(".", spans.last().text)
    }

    @Test
    fun aLinkToABundledPageNamesIt() {
        assertEquals(InfoPage.PRIVACY, infoPageAt("/privacy/"))
        assertEquals(InfoPage.PRIVACY, infoPageAt("/privacy"))
        assertEquals(InfoPage.SUPPORT, infoPageAt("https://inbox.hafa.cc/support/"))
        assertEquals(InfoPage.SUPPORT, infoPageAt("https://inbox.hafa.cc/support"))
        assertNull(infoPageAt("https://inbox.hafa.cc/"))
        assertNull(infoPageAt("https://example.com/privacy"))
        assertNull(infoPageAt("mailto:support@inbox.hafa.cc"))
    }

    @Test
    fun anAddressWithoutAName() {
        assertEquals(listOf(InfoSpan("your @hafa.cc address")), infoSpans("your @hafa.cc address"))
    }

    @Test
    fun theBundledPagesRead() {
        for (page in InfoPage.entries) {
            val blocks = parseInfoPage(File("../../docs/${page.asset}").readText())
            val first = blocks.first() as InfoBlock.Heading
            assertEquals(1, first.level)
            assertTrue(blocks.none { block -> block is InfoBlock.Paragraph && plain(block.spans).contains("**") })
            assertTrue(blocks.any { it is InfoBlock.Heading && it.level == 2 })
        }
        val privacy = parseInfoPage(File("../../docs/privacy.md").readText())
        assertEquals("Privacy", plain((privacy.first() as InfoBlock.Heading).spans))
        assertTrue(privacy.any { it is InfoBlock.Bullets })
    }
}
