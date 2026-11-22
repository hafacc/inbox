package cc.hafa.inbox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import cc.hafa.inbox.core.mail.ListEvent
import cc.hafa.inbox.mail.ListCommands

/** The list event a hardware key asks for, as on web: j/k and the arrows, Enter or o, Escape, x and the action keys. */
fun listKeyEvent(event: KeyEvent): ListEvent<Nothing, Nothing>? {
    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return null
    return when (event.key) {
        Key.DirectionDown -> ListEvent.Next
        Key.DirectionUp -> ListEvent.Previous
        Key.Enter, Key.NumPadEnter -> ListEvent.Enter
        Key.Escape -> ListEvent.Back
        else -> when (event.utf16CodePoint.toChar()) {
            'j' -> ListEvent.Next
            'k' -> ListEvent.Previous
            'o' -> ListEvent.Enter
            'x' -> ListEvent.Select
            'e' -> ListEvent.Command(ListCommands.DONE)
            'b' -> ListEvent.Command(ListCommands.SNOOZE)
            'p' -> ListEvent.Command(ListCommands.PIN)
            'l' -> ListEvent.Command(ListCommands.LABEL)
            '!' -> ListEvent.Command(ListCommands.SPAM)
            'I' -> ListEvent.Command(ListCommands.READ)
            'U' -> ListEvent.Command(ListCommands.UNREAD)
            else -> null
        }
    }
}

/**
 * Hardware keys for the list and the open thread in [content]: keys a focused field or row doesn't
 * use reach [onEvent]. It holds the keyboard's focus itself whenever nothing inside does, asked
 * again each time [refocus] changes, so the keys work before any row has been focused.
 */
@Composable
fun ListKeys(refocus: Any?, onEvent: (ListEvent<Nothing, Nothing>) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val requester = remember { FocusRequester() }
    var inside by remember { mutableStateOf(false) }
    LaunchedEffect(refocus) { if (!inside) requester.requestFocus() }
    Box(
        modifier
            .onFocusChanged { inside = it.hasFocus }
            .onKeyEvent { key -> listKeyEvent(key)?.let(onEvent) != null }
            .focusRequester(requester)
            .focusTarget(),
    ) {
        CompositionLocalProvider(LocalListEvents provides onEvent) { content() }
    }
}

/** Where list events go from inside [ListKeys]. */
val LocalListEvents = staticCompositionLocalOf<(ListEvent<Nothing, Nothing>) -> Unit> { {} }

/**
 * Tell the list while the menu, dropdown or sheet named [id] is [open], so Escape and a tap outside
 * close it before the open thread or bundle.
 */
@Composable
fun ListMenu(id: String, open: Boolean = true, onEvent: (ListEvent<Nothing, Nothing>) -> Unit = LocalListEvents.current) {
    val send by rememberUpdatedState(onEvent)
    DisposableEffect(id, open) {
        if (open) send(ListEvent.OpenMenu(id))
        onDispose { if (open) send(ListEvent.CloseMenu(id)) }
    }
}
