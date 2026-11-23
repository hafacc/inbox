package cc.hafa.inbox.ui

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.hafa.inbox.core.compose.LinkMode
import cc.hafa.inbox.core.compose.LinkRequest
import cc.hafa.inbox.core.mail.linkAddress
import cc.hafa.inbox.core.menu.rowHoldWedges
import cc.hafa.inbox.mail.BodyLink
import cc.hafa.inbox.mail.CODE_INDENT
import cc.hafa.inbox.mail.QUOTE_INDENT
import cc.hafa.inbox.mail.RichBody
import cc.hafa.inbox.ui.theme.paper
import com.mohamedrejeb.richeditor.model.TextPaddingValues
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor

private val CODE_CHIP_PADDING = TextPaddingValues(horizontal = 2.sp, vertical = (-2).sp)

/**
 * The composer's body field: rich text, with the quote bar and code background drawn behind its
 * lines. A hold on a link opens the round menu with Open, Edit and Remove (TalkBack lists them
 * among the field's actions), and a tap on one still puts the caret there; Edit and the toolbar's
 * Link button open the link dialog ([RichBody.linkDialog]).
 */
@Composable
fun RichBodyField(body: RichBody, readOnly: Boolean, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    val colors = paper
    val focus = focusRequester ?: remember { FocusRequester() }
    val context = LocalContext.current
    val root = LocalView.current
    val haptics = LocalHapticFeedback.current
    val menu = LocalHoldMenu.current
    var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
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
                .focusRequester(focus)
                .onGloballyPositioned { placed = it }
                .then(
                    if (readOnly) {
                        Modifier
                    } else {
                        Modifier.pointerInput(body, menu) {
                            // The link pressed and where in the text, from the press going down to the tap or hold it becomes.
                            var pressed: Pair<BodyLink, Int>? = null
                            holdMenuGestures(
                                menu ?: HoldMenuState(),
                                haptics,
                                claim = true,
                                // The field never saw the press, so the caret goes where it landed here.
                                onTap = {
                                    pressed?.let { (_, offset) ->
                                        focus.requestFocus()
                                        body.state.selection = TextRange(offset)
                                    }
                                },
                                open = { pressedAt ->
                                    val link = pressed?.first
                                    if (menu == null || link == null || pressedAt == null) {
                                        false
                                    } else {
                                        menu.show(composerLinkMenu(context, root, pressedAt, body, link))
                                        true
                                    }
                                },
                                takes = { position ->
                                    pressed = layout?.let { shown -> letterAt(shown, position) }?.let { offset ->
                                        body.linkAt(offset, letter = true)?.let { it to offset }
                                    }
                                    pressed != null
                                },
                                pass = PointerEventPass.Initial,
                            ) { placed }
                        }
                    },
                )
                .semantics {
                    contentDescription = "Message"
                    if (!readOnly) customActions = composerLinkActions(context, body)
                }
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
    body.linkDialog?.let { dialog ->
        LinkDialog(
            dialog.request,
            onDismiss = { body.linkDialog = null },
            onSave = { text, href ->
                body.saveLink(text, href)
                focus.requestFocus()
            },
        )
    }
}

// The letter of the text laid out by [layout] under [position], or null off the text.
private fun letterAt(layout: TextLayoutResult, position: Offset): Int? {
    val offset = layout.getOffsetForPosition(position)
    return listOf(offset, offset - 1).firstOrNull { index ->
        index in 0 until layout.layoutInput.text.length && layout.getBoundingBox(index).contains(position)
    }
}

// What the link dialog says under an address no link can lead to.
private const val NOT_A_LINK = "A link can lead only to a web page (http or https) or an email address (mailto)"

private fun pillOf(url: String) = url.removePrefix("https://").removePrefix("http://")

// Run a composer link's action: open it, edit it in the dialog, or turn it back into plain text.
private fun runComposerLinkAction(context: Context, body: RichBody, link: BodyLink, id: String) {
    when (id) {
        "open" -> openWebLink(context, link.link.href)
        "edit" -> body.editLink(link)
        "remove" -> body.removeLink(link)
    }
}

private val COMPOSER_LINK_ITEMS = listOf(
    HoldMenuItem("open", "Open", Icons.AutoMirrored.Outlined.OpenInNew),
    HoldMenuItem("edit", "Edit", Icons.Outlined.Edit),
    HoldMenuItem("remove", "Remove", Icons.Outlined.LinkOff),
)

// The round menu of [link] in the composer held at [finger] (pixels in [root]), laid out as a row's is.
private fun composerLinkMenu(context: Context, root: View, finger: Offset, body: RichBody, link: BodyLink): HoldMenuSpec {
    val density = root.resources.displayMetrics.density
    val arcs = rowHoldWedges(
        COMPOSER_LINK_ITEMS.map { it.id },
        finger.x / density,
        finger.y / density,
        root.width / density,
    )
    return HoldMenuSpec(
        center = finger,
        wedges = holdWedges(COMPOSER_LINK_ITEMS, arcs),
        pill = pillOf(link.link.href),
        url = true,
        label = "Link actions",
        onPick = { item -> runComposerLinkAction(context, body, link, item.id) },
    )
}

// TalkBack's Open, Edit and Remove for each link in the composer, naming the link when there are several.
private fun composerLinkActions(context: Context, body: RichBody): List<CustomAccessibilityAction> {
    val links = body.links
    return links.flatMap { link ->
        val name = if (links.size > 1) " ${pillOf(link.link.href)}" else ""
        listOf("open" to "Open link", "edit" to "Edit link", "remove" to "Remove link").map { (id, label) ->
            CustomAccessibilityAction("$label$name") {
                runComposerLinkAction(context, body, link, id)
                true
            }
        }
    }
}

/**
 * The composer's link dialog for [request]: the link's text (not asked when making selected text
 * a link) and address, prefilled when editing; Save hands both to [onSave] (the address as the
 * text when the text is left empty, `https://` put before an address without a scheme). An
 * address to anything but a web or mail address ([linkAddress] null) can't be saved.
 */
@Composable
internal fun LinkDialog(request: LinkRequest, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var text by remember(request) { mutableStateOf(request.text) }
    var address by remember(request) { mutableStateOf(request.href) }
    val asksText = request.mode != LinkMode.SELECTION
    val href = linkAddress(address)
    val canSave = address.isNotBlank() && href != null
    val save = {
        if (href != null) {
            val words = when {
                !asksText -> request.text
                text.isBlank() -> href
                else -> text
            }
            onSave(words, href)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = paper.card,
        title = { Text(if (request.mode == LinkMode.EDIT) "Edit link" else "Add link", color = paper.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (asksText) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        label = { Text("Text") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address") },
                    singleLine = true,
                    isError = address.isNotBlank() && href == null,
                    supportingText = if (address.isNotBlank() && href == null) {
                        { Text(NOT_A_LINK) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (canSave) save() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = save, enabled = canSave) { Text("Save", color = if (canSave) paper.accent else paper.faint) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = paper.textSecondary) } },
    )
}
