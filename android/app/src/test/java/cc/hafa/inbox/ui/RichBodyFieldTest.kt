package cc.hafa.inbox.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import cc.hafa.inbox.core.compose.RichBlock
import cc.hafa.inbox.core.compose.RichMark
import cc.hafa.inbox.core.compose.RichRun
import cc.hafa.inbox.core.compose.normalizeHtml
import cc.hafa.inbox.core.compose.richToHtml
import cc.hafa.inbox.core.compose.richToMarkdown
import cc.hafa.inbox.mail.RichBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The composer's body runs `shared/testdata/rich-fixtures.json` as the web composer does: loading markdown and typing it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w400dp-h2000dp", application = Application::class)
class RichBodyFieldTest {
    @get:Rule
    val compose = createComposeRule()

    private val fixtures by lazy {
        JSONObject(checkNotNull(javaClass.classLoader?.getResource("rich-fixtures.json")).readText())
    }

    private fun cases(section: String): List<JSONObject> = fixtures.getJSONArray(section).let { array ->
        (0 until array.length()).map(array::getJSONObject)
    }

    private fun runs(array: JSONArray): List<RichRun> = (0 until array.length()).map { index ->
        val run = array.getJSONObject(index)
        val marks = run.optJSONArray("marks")?.let { names ->
            (0 until names.length()).map { at -> RichMark.entries.first { it.wire == names.getString(at) } }
        }.orEmpty()
        RichRun(run.getString("text"), marks, if (run.has("href")) run.getString("href") else null)
    }

    private fun block(raw: JSONObject): RichBlock = when (raw.getString("kind")) {
        "paragraph" -> RichBlock.Paragraph(runs(raw.getJSONArray("runs")))
        "heading" -> RichBlock.Heading(raw.getInt("level"), runs(raw.getJSONArray("runs")))
        "bullet" -> RichBlock.Bullet(runs(raw.getJSONArray("runs")))
        "ordered" -> RichBlock.Ordered(raw.getInt("number"), runs(raw.getJSONArray("runs")))
        "quote" -> RichBlock.Quote(runs(raw.getJSONArray("runs")))
        else -> RichBlock.Code(raw.getString("text"))
    }

    @Test
    fun loadingMarkdownHoldsItsBlocks() {
        for (case in cases("markdown")) {
            val blocks = case.getJSONArray("blocks").let { array -> (0 until array.length()).map { block(array.getJSONObject(it)) } }
            assertEquals(case.getString("name"), blocks, RichBody(case.getString("markdown"), bareLinks = true).blocks())
        }
    }

    @Test
    fun typingMarkdownStylesItAsOnWeb() {
        var body by mutableStateOf(RichBody(""))
        compose.setContent {
            key(body) { RichBodyField(body, readOnly = false) }
        }
        val failures = mutableListOf<String>()
        for (case in cases("typing")) {
            body = RichBody("")
            compose.waitForIdle()
            for (char in case.getString("typed")) {
                compose.onNodeWithContentDescription("Message").performTextInput(char.toString())
                compose.waitForIdle()
            }
            val blocks = body.blocks()
            val html = normalizeHtml(richToHtml(blocks))
            val text = richToMarkdown(blocks)
            if (html != normalizeHtml(case.getString("html")) || text != case.getString("text")) {
                failures += "${case.getString("name")}: html $html, text ${JSONObject.quote(text)}"
            }
        }
        assertEquals("", failures.joinToString("\n"))
    }
}
