package cc.hafa.inbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withAnnotation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.hafa.inbox.core.compose.InlineStyle
import cc.hafa.inbox.core.mail.findWebLinks
import cc.hafa.inbox.core.theme.labelTone
import cc.hafa.inbox.core.todo.LabelTags
import cc.hafa.inbox.core.todo.StyledNode
import cc.hafa.inbox.core.todo.Tags
import cc.hafa.inbox.core.todo.styledTree
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.flow.first

// latr's look: markers kept but faded, tags tinted with a soft chip behind them.
private const val MARKER_ALPHA = 0.45f
private const val TAG_CHIP_ALPHA = 0.12f

// A thin space each side of a tag, inside its chip, so the chip has room around the name.
private const val TAG_PAD = "\u2009"
private const val TAG_ANNOTATION = "tag"
private const val LINK_ANNOTATION = "link"

/** How `#tags` are colored: one naming a user label of [labels] in that label's tone from [tones], any other in [default]. */
data class TagColors(
    /** the color of a tag naming no label */
    val default: Color,
    /** the labels a tag may name */
    val labels: LabelTags = LabelTags(emptyList()),
    /** label colors by tone, as `paper.labelTones` has them */
    val tones: Map<String, Color> = emptyMap(),
) {
    /** The color of [tag] (with or without its `#`). */
    fun of(tag: String): Color = labels.labelOf(tag.removePrefix("#"))?.let { tones[labelTone(it.color?.backgroundColor)] } ?: default
}

/** The labels a todo's `#tag` may name, for coloring it; the screen showing todos provides them. */
internal val LocalLabelTags = staticCompositionLocalOf { LabelTags(emptyList()) }

/** The tag colors for the current theme and [LocalLabelTags]. */
@Composable
internal fun tagColors(): TagColors = TagColors(paper.accentStrong, LocalLabelTags.current, paper.labelTones)

/**
 * [text] as latr shows it: the first line in semibold, as a new email's subject, `*bold*` (bold) and `_italic_` spans styled
 * with their markers faded against [baseColor], and `#tags` in [tagColors] with a thin space each
 * side (their chips are drawn by [tagChips]). With a [linkColor], its web addresses are underlined
 * in it and carry where they lead (for [webLinkAt]); without, they are plain text, as in the editor.
 */
fun styledTodoText(text: String, baseColor: Color, tagColors: TagColors, linkColor: Color? = null): AnnotatedString {
    val marker = SpanStyle(color = baseColor.copy(alpha = baseColor.alpha * MARKER_ALPHA), fontWeight = FontWeight.Normal)
    fun AnnotatedString.Builder.render(nodes: List<StyledNode>) {
        for (node in nodes) {
            when (node) {
                is StyledNode.Text -> append(node.text)
                is StyledNode.Marker -> withStyle(marker) { append(node.text) }
                is StyledNode.Link -> if (linkColor == null) {
                    append(node.text)
                } else {
                    withAnnotation(LINK_ANNOTATION, node.href) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(node.text) }
                    }
                }
                is StyledNode.Tag -> {
                    val color = tagColors.of(node.text)
                    withAnnotation(TAG_ANNOTATION, color.value.toString()) {
                        append(TAG_PAD)
                        withStyle(SpanStyle(color = color)) { append(node.text) }
                        append(TAG_PAD)
                    }
                }
                is StyledNode.Span -> {
                    val style = when (node.style) {
                        InlineStyle.Style.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                        InlineStyle.Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    }
                    withStyle(style) { render(node.children) }
                }
            }
        }
    }
    val styled = buildAnnotatedString { render(styledTree(text)) }
    // The first line weighs as a new email's subject does; bold and the markers keep their own.
    val firstLineEnd = styled.text.indexOf('\n').takeIf { it >= 0 } ?: styled.length
    return buildAnnotatedString {
        addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), 0, firstLineEnd)
        append(styled)
    }
}

/** Offsets between a todo's text and [styledTodoText]'s, which puts [TAG_PAD] before and after each tag. */
internal class TagPadMapping(text: String) : OffsetMapping {
    // Where a pad goes in the original text, in order: the start and end of each piece of a tag
    // [styledTodoText] draws (a style marker inside a tag splits it).
    private val inserts: List<Int> = buildList {
        var offset = 0
        fun walk(nodes: List<StyledNode>) {
            for (node in nodes) {
                when (node) {
                    is StyledNode.Text -> offset += node.text.length
                    is StyledNode.Marker -> offset += node.text.length
                    is StyledNode.Link -> offset += node.text.length
                    is StyledNode.Tag -> {
                        add(offset)
                        offset += node.text.length
                        add(offset)
                    }
                    is StyledNode.Span -> walk(node.children)
                }
            }
        }
        walk(styledTree(text))
    }

    override fun originalToTransformed(offset: Int): Int = offset + inserts.count { it < offset }

    override fun transformedToOriginal(offset: Int): Int {
        var shift = 0
        for (insert in inserts) {
            if (insert + shift >= offset) break
            shift++
        }
        return offset - shift
    }
}

/** The editor's twin of [styledTodoText]; the markers stay in the text and the tags' pads map to their edges. */
class InlineStyleTransformation(
    private val baseColor: Color,
    private val tagColors: TagColors,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText =
        TransformedText(styledTodoText(text.text, baseColor, tagColors), TagPadMapping(text.text))

    override fun equals(other: Any?): Boolean =
        other is InlineStyleTransformation && other.baseColor == baseColor && other.tagColors == tagColors

    override fun hashCode(): Int = 31 * baseColor.hashCode() + tagColors.hashCode()
}

/**
 * Draw a rounded chip in a soft tint of each tag's color behind it in the text laid out by
 * [layout] (as [styledTodoText] marks them); a tag wrapping onto a new line gets a chip per line.
 */
fun Modifier.tagChips(layout: () -> TextLayoutResult?): Modifier = drawBehind {
    val result = layout() ?: return@drawBehind
    val laidOut = result.layoutInput.text
    val radius = CornerRadius(6.dp.toPx())
    for (tag in laidOut.getStringAnnotations(TAG_ANNOTATION, 0, laidOut.length)) {
        val tagColor = Color(tag.item.toULong())
        val chip = tagColor.copy(alpha = tagColor.alpha * TAG_CHIP_ALPHA)
        fun draw(box: Rect) = drawRoundRect(chip, Offset(box.left, box.top), Size(box.width, box.height), radius)
        var box: Rect? = null
        var boxLine = -1
        for (offset in tag.start until tag.end) {
            val line = result.getLineForOffset(offset)
            val charBox = result.getBoundingBox(offset)
            val current = box
            if (current != null && line == boxLine) {
                box = Rect(minOf(current.left, charBox.left), current.top, maxOf(current.right, charBox.right), current.bottom)
            } else {
                current?.let(::draw)
                box = charBox
                boxLine = line
            }
        }
        box?.let(::draw)
    }
}

/** Where the link under [position] (pixels in the text) leads, in text laid out by [styledTodoText]; null off one. */
internal fun webLinkAt(layout: TextLayoutResult, position: Offset): String? {
    val laidOut = layout.layoutInput.text
    val offset = layout.getOffsetForPosition(position)
    return listOf(offset, offset - 1).firstNotNullOfOrNull { index ->
        if (index !in laidOut.indices || !layout.getBoundingBox(index).contains(position)) {
            null
        } else {
            laidOut.getStringAnnotations(LINK_ANNOTATION, index, index + 1).firstOrNull()?.item
        }
    }
}

/**
 * A todo's text at rest, styled as [styledTodoText] has it. [onPress] hears where in [text] each
 * press lands, so the editor a tap opens can put the cursor there. With [links], its web addresses
 * work as an email's links do: a tap opens one, a hold opens the link's hold menu (Open, Copy link,
 * Share), and TalkBack lists those among the text's actions; a press elsewhere is left to what is
 * around it.
 */
@Composable
internal fun TodoText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    onPress: ((Int) -> Unit)? = null,
    links: Boolean = false,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val colors = tagColors()
    val press by rememberUpdatedState(onPress)
    val context = LocalContext.current
    val root = LocalView.current
    val haptics = LocalHapticFeedback.current
    val menu = LocalHoldMenu.current
    val hrefs = remember(text, links) { if (links) findWebLinks(text).map { it.href } else emptyList() }
    var placed by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val linked = if (hrefs.isEmpty()) {
        Modifier
    } else {
        Modifier
            .onGloballyPositioned { placed = it }
            .pointerInput(text, menu) {
                // The link pressed, kept from the press going down to the tap or hold it becomes.
                var pressedLink: String? = null
                holdMenuGestures(
                    menu ?: HoldMenuState(),
                    haptics,
                    claim = true,
                    onTap = { pressedLink?.let { openWebLink(context, it) } },
                    open = { pressedAt ->
                        val url = pressedLink
                        if (menu == null || url == null || pressedAt == null) {
                            false
                        } else {
                            menu.show(linkMenuSpec(context, root, pressedAt, url))
                            true
                        }
                    },
                    takes = { position ->
                        pressedLink = layout?.let { webLinkAt(it, position) }
                        pressedLink != null
                    },
                ) { placed }
            }
            .semantics { customActions = linkAccessibilityActions(context, hrefs) }
    }
    val pressed = if (onPress == null) {
        Modifier
    } else {
        // Watched before the row's tap takes it, and never taken here.
        Modifier.pointerInput(text) {
            val mapping = TagPadMapping(text)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                layout?.let { shown -> press?.invoke(mapping.transformedToOriginal(shown.getOffsetForPosition(down.position)).coerceIn(0, text.length)) }
            }
        }
    }
    Text(
        styledTodoText(text, color, colors, paper.accentStrong.takeIf { links }),
        style = style,
        color = color,
        onTextLayout = { layout = it },
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.tagChips({ layout }).then(pressed).then(linked),
    )
}

/** Whether an edit of [before] into [after] is a typed Enter: a lone line break put in, as against a paste. */
internal fun typedEnter(before: String, after: String): Boolean {
    val prefix = before.commonPrefixWith(after).length
    val suffix = before.substring(prefix).commonSuffixWith(after.substring(prefix)).length
    return after.substring(prefix, after.length - suffix) == "\n"
}

/** Known tags offered for the `#tag` being typed, and picking one. */
class TagOffer(
    /** the tag names, without `#` */
    val names: List<String>,
    /** finish the typed tag as `#name` and a space */
    val pick: (String) -> Unit,
)

/** The tags the todo being written offers, which [TagBar] shows on top of the keyboard. */
class TagOffers {
    /** the offer showing, or null */
    var current by mutableStateOf<TagOffer?>(null)
}

/** Where todo editors put their tag offers; the screen showing [TagBar] provides it. */
internal val LocalTagOffers = staticCompositionLocalOf { TagOffers() }

/**
 * A row on top of the keyboard offering the tags of [offers], as latr has it, a tag naming a label
 * in its color; a tap keeps the keyboard up.
 */
@Composable
internal fun TagBar(offers: TagOffers, modifier: Modifier = Modifier) {
    val offer = offers.current ?: return
    val haptics = LocalHapticFeedback.current
    val colors = tagColors()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(paper.raised)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
    ) {
        for (name in offer.names) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxHeight()
                    // Taking focus would close the editor being typed in.
                    .focusProperties { canFocus = false }
                    .clickable(onClickLabel = "Add #$name") {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        offer.pick(name)
                    }
                    .padding(horizontal = 6.dp),
            ) {
                val color = colors.of(name)
                Text(
                    "#$name",
                    style = MaterialTheme.typography.bodyLarge,
                    color = color,
                    modifier = Modifier
                        .background(color.copy(alpha = color.alpha * TAG_CHIP_ALPHA), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/**
 * latr's todo editor: the styled text, edited in place, with known `#tags` offered for the tag at
 * the caret in [LocalTagOffers] (the row on top of the keyboard).
 *
 * Enter (the keyboard's action, or a typed line break) calls [onEnter] rather than adding a line;
 * it returns true when it moved on to another todo (the keyboard stays up), false to put the
 * editor away. When [continues], the keyboard's action key shows Next, as Enter in a todo with text
 * starts another; otherwise it shows Done. [onBlur] gets the text when the field loses focus, the keyboard is put away, or
 * the field leaves the screen while focused. [suggest] lists the tags to offer for a prefix, given
 * the text as typed. The cursor starts at [caret], or else at the end.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TodoEditor(
    initial: String,
    onChange: (String) -> Unit,
    onEnter: (String) -> Boolean,
    onBlur: (String) -> Unit,
    suggest: (text: String, prefix: String) -> List<String>,
    style: TextStyle,
    color: Color,
    label: String,
    modifier: Modifier = Modifier,
    continues: Boolean = false,
    caret: Int? = null,
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange((caret ?: initial.length).coerceIn(0, initial.length)))) }
    var focused by remember { mutableStateOf(false) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val offers = LocalTagOffers.current
    val colors = tagColors()
    val blur by rememberUpdatedState(onBlur)
    val enter = {
        if (!onEnter(value.text)) focusManager.clearFocus()
    }
    // The text field starts typing, keyboard and all, once the window has the focus too.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(Unit) { requester.requestFocus() }
    DisposableEffect(Unit) { onDispose { if (focused) blur(value.text) } }
    // Putting the keyboard away (back, a swipe down) ends the editing, as leaving the field does;
    // the keyboard going as the app goes to the background doesn't, so the edit stays open.
    val imeVisible = WindowInsets.isImeVisible
    var imeShown by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(imeVisible, focused) {
        val inFront = windowInfo.isWindowFocused && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        if (imeVisible && focused) {
            imeShown = true
        } else if (!imeVisible && imeShown && focused && inFront) {
            imeShown = false
            focusManager.clearFocus()
        }
    }
    BasicTextField(
        value = value,
        onValueChange = { next ->
            // A paste keeps every line, as on the web.
            if (typedEnter(value.text, next.text)) {
                enter()
            } else {
                val changed = next.text != value.text
                value = next
                if (changed) onChange(next.text)
            }
        },
        textStyle = style.copy(color = color),
        cursorBrush = SolidColor(paper.accent),
        visualTransformation = InlineStyleTransformation(color, colors),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            // Fixed while typing: a changed action restarts the keyboard's input mid-word.
            imeAction = if (continues) ImeAction.Next else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onAny = { enter() }),
        onTextLayout = { layout = it },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(requester)
            .onFocusChanged { state ->
                if (focused && !state.isFocused) blur(value.text)
                focused = state.isFocused
            }
            .semantics { contentDescription = label }
            .tagChips({ layout }),
    )
    val typed = if (focused && value.selection.collapsed) Tags.typing(value.text, value.selection.end) else null
    val suggestions = typed?.let { suggest(value.text, it.prefix) }.orEmpty()
    // Offered afresh as the caret moves, from one tag to another.
    DisposableEffect(typed, suggestions) {
        val offer = if (suggestions.isEmpty()) {
            null
        } else {
            TagOffer(suggestions) { name ->
                val current = value
                Tags.typing(current.text, current.selection.end)?.let { at ->
                    val (text, caret) = Tags.complete(current.text, at, current.selection.end, name)
                    value = TextFieldValue(text, TextRange(caret))
                    onChange(text)
                }
            }
        }
        if (offer != null) offers.current = offer
        onDispose { if (offer != null && offers.current === offer) offers.current = null }
    }
}
