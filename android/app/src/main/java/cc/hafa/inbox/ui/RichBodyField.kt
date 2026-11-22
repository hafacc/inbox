package cc.hafa.inbox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hafa.inbox.mail.CODE_INDENT
import cc.hafa.inbox.mail.QUOTE_INDENT
import cc.hafa.inbox.mail.RichBody
import cc.hafa.inbox.ui.theme.paper
import com.mohamedrejeb.richeditor.model.TextPaddingValues
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

private val CODE_CHIP_PADDING = TextPaddingValues(horizontal = 2.sp, vertical = (-2).sp)

/** The composer's body field: rich text, with the quote bar and code background drawn behind its lines. */
@Composable
fun RichBodyField(body: RichBody, readOnly: Boolean, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    val colors = paper
    LaunchedEffect(body, colors) {
        body.state.config.linkColor = colors.accent
        body.state.config.codeSpanColor = colors.text
        body.state.config.codeSpanBackgroundColor = colors.muted
        body.state.config.codeSpanStrokeColor = colors.muted
        body.state.config.codeSpanStrokeWidth = 0.sp
        body.state.config.codeSpanCornerRadius = 4.sp
        // The chip is drawn behind the text and takes no room in the line, unlike web's padding.
        body.state.config.codeSpanPadding = CODE_CHIP_PADDING
    }
    LaunchedEffect(body) { body.watch() }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Box(modifier) {
        if (body.isEmpty) {
            Text("Message", style = bodyStyle(), color = paper.faint)
        }
        BasicRichTextEditor(
            state = body.state,
            readOnly = readOnly,
            textStyle = bodyStyle().copy(color = paper.text),
            cursorBrush = SolidColor(paper.accent),
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .semantics { contentDescription = "Message" }
                .drawBehind {
                    val result = layout ?: return@drawBehind
                    val text = body.state.annotatedString
                    for (range in text.paragraphStyles) {
                        val indent = range.item.textIndent
                        if (indent != QUOTE_INDENT && indent != CODE_INDENT) continue
                        val length = result.layoutInput.text.length
                        val first = range.start.coerceAtMost(length)
                        val last = (range.end - 1).coerceIn(first, length)
                        val top = result.getLineTop(result.getLineForOffset(first))
                        val bottom = result.getLineBottom(result.getLineForOffset(last))
                        if (indent == QUOTE_INDENT) {
                            drawRect(colors.borderStrong, Offset(0f, top), Size(3.dp.toPx(), bottom - top))
                        } else {
                            drawRoundRect(colors.muted, Offset(0f, top), Size(size.width, bottom - top), CornerRadius(4.dp.toPx()))
                        }
                    }
                },
        )
    }
}
