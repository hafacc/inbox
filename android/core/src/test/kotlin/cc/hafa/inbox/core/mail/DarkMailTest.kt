package cc.hafa.inbox.core.mail

import cc.hafa.inbox.core.theme.ThemePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DarkMailTest {
    private val palette = ThemePalette.darkPalette(ThemePalette.defaultTheme)

    // A designed newsletter: a style sheet of many rules over many styled table cells.
    private fun newsletter(rows: Int, rules: Int): String = buildString {
        append("<style>")
        repeat(rules) { append(".c$it{color:#2${it % 10}2${it % 10}2${it % 10};background-color:#fafafa} ") }
        append("</style><table bgcolor=\"#ffffff\" style=\"background-color:#ffffff\">")
        repeat(rows) { row ->
            append("<tr><td class=\"c${row % rules}\" style=\"color:#333333;background-color:#f${row % 10}f${row % 10}f${row % 10}\">")
            append("<a href=\"https://example.com/$row\" style=\"color:#0066cc\">Story $row</a>")
            append("<p style=\"margin:0;color:#555\">Lorem ipsum dolor sit amet, consectetur adipiscing elit $row.</p></td></tr>")
        }
        append("</table>")
    }

    @Test
    fun `a mail darkened again for the same look reuses the result`() {
        val html = newsletter(rows = 4, rules = 2)
        val first = darkenMessageHtml(html, palette)
        assertSame(first, darkenMessageHtml(html, palette))
        assertNotEquals(first, darkenMessageHtml(html, palette, plain = true))
    }

    @Test
    fun `a mail past the element limit shows as it is`() {
        val html = "<div style=\"background-color:#ffffff;color:#000000\">${"<span>word</span>".repeat(20_001)}</div>"
        assertEquals(html, darkenMessageHtml(html, palette))
    }
}
