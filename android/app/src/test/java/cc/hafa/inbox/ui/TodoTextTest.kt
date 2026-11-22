package cc.hafa.inbox.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.gmail.GmailLabelColor
import cc.hafa.inbox.core.todo.LabelTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the todo editor reads Enter and pastes, and maps offsets around its tag chips. */
class TodoTextTest {
    @Test
    fun `a lone line break is Enter`() {
        assertTrue(typedEnter("abcd", "ab\ncd"))
        assertTrue(typedEnter("", "\n"))
    }

    @Test
    fun `a paste with line breaks is not Enter, so it keeps every line`() {
        assertFalse(typedEnter("abcd", "abX\nY\nZcd"))
        assertFalse(typedEnter("abcd", "a\n\nd"))
        assertFalse(typedEnter("", "\nY"))
    }

    @Test
    fun `offsets map both ways wherever tags and styles meet`() {
        for (text in listOf("#_home_", "*#a* b #c", "_x #y_ #z", "plain", "")) {
            val shown = styledTodoText(text, Color.Black, TagColors(Color.Blue)).text
            val mapping = TagPadMapping(text)
            for (offset in 0..text.length) {
                val transformed = mapping.originalToTransformed(offset)
                assertTrue("$text: $offset maps to $transformed", transformed in 0..shown.length)
                assertEquals("$text: $offset", offset, mapping.transformedToOriginal(transformed))
            }
            for (offset in 0..shown.length) {
                assertTrue("$text: shown $offset", mapping.transformedToOriginal(offset) in 0..text.length)
            }
            assertEquals(text, shown.replace("\u2009", ""))
        }
    }

    @Test
    fun `the first line weighs as a new email's subject, bold above it`() {
        val shown = styledTodoText("Call *Ana*\nlater", Color.Black, TagColors(Color.Blue))
        val firstLine = shown.spanStyles.single { it.start == 0 && it.end == "Call *Ana*".length }
        assertEquals(FontWeight.SemiBold, firstLine.item.fontWeight)
        assertTrue(shown.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
    }

    @Test
    fun `a tag naming a user label takes the label's tone, others the default`() {
        val labels = LabelTags(
            listOf(
                GmailLabel("L1", "Read Later", color = GmailLabelColor(backgroundColor = "#16a766")),
                GmailLabel("CATEGORY_SOCIAL", "CATEGORY_SOCIAL", type = "system"),
            ),
        )
        val colors = TagColors(Color.Blue, labels, mapOf("green" to Color.Green))
        val shown = styledTodoText("#read-later #category_social #home", Color.Black, colors)
        val tagged = shown.getStringAnnotations("tag", 0, shown.length).map { Color(it.item.toULong()) }
        assertEquals(listOf(Color.Green, Color.Blue, Color.Blue), tagged)
    }

    @Test
    fun `an open editor's tags take label colors that load later`() {
        val tones = mapOf("green" to Color.Green)
        val before = InlineStyleTransformation(Color.Black, TagColors(Color.Blue, tones = tones))
        val loaded = LabelTags(listOf(GmailLabel("L1", "Work", color = GmailLabelColor(backgroundColor = "#16a766"))))
        val after = InlineStyleTransformation(Color.Black, TagColors(Color.Blue, loaded, tones))
        // The text field runs a transformation again only when it isn't equal to the last.
        assertFalse(before == after)
        val shown = after.filter(AnnotatedString("#work")).text
        val tagged = shown.getStringAnnotations("tag", 0, shown.length).map { Color(it.item.toULong()) }
        assertEquals(listOf(Color.Green), tagged)
    }
}
