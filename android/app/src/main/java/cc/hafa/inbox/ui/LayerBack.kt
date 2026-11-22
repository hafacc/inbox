package cc.hafa.inbox.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Back for a layer over the screen (the side menu, a selection, an open bundle, search, a todo being
 * edited, a sheet's step): one back stack, as on the web.
 *
 * Android gives Back to the handler added last. A `BackHandler(enabled = …)` is added when it is
 * first composed, not when its layer opens, so one composed early (with the screen) loses to a
 * layer opened after it but drawn under it, and Back skips the layer on top. This one is added
 * only while [open], so the layer that opened last takes Back first, whatever order they are
 * composed in. Use it, not a bare `BackHandler(enabled = …)`, for anything that opens and closes.
 */
@Composable
internal fun LayerBackHandler(open: Boolean = true, onBack: () -> Unit) {
    if (open && !LocalLayerLeaving.current) BackHandler(onBack = onBack)
}

/**
 * Whether the layer around is on its way out (a thread shrinking back into its row, the search bar
 * fading): Back handlers inside it ([LayerBackHandler], [ScreenBackHandler]) are taken away at once,
 * so a quick second Back reaches the layer under it.
 */
internal val LocalLayerLeaving = compositionLocalOf { false }

/**
 * A layer that, while [leaving], takes no Back or taps of its own (as [LocalLayerLeaving]) and lets
 * none reach the screen under it either, until it is gone; for a layer, like the composer sliding
 * down, whose leaving a quick second Back shouldn't follow with another step.
 */
@Composable
internal fun HeldWhileLeaving(leaving: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayerLeaving provides leaving, content = content)
    if (leaving) {
        LayerBackHandler {}
        Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } })
    }
}
