package cc.hafa.inbox.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.core.theme.ThemePalette

private val InboxShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// State-layer strengths for the theme-colored ripple; focus is strong enough to read as an outline.
private val FOCUS_RIPPLE = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.24f, hoveredAlpha = 0.08f, pressedAlpha = 0.12f)

// How long colors take to ease over when the theme or light/dark changes.
private const val COLOR_FADE_MS = 200

/** The app's theme: one of the shared color themes (never the phone's dynamic colors) in light or dark, and Geist. */
@Composable
fun InboxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeId: String = ThemePalette.defaultTheme,
    content: @Composable () -> Unit,
) {
    val target = themeTokens(themeId, darkTheme)
    // One fraction eases every color from where it stood when the theme or light/dark changed.
    var from by remember { mutableStateOf(target) }
    var to by remember { mutableStateOf(target) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(target) {
        if (target == to) return@LaunchedEffect
        from = blend(from, to, progress.value)
        to = target
        progress.snapTo(0f)
        progress.animateTo(1f, tween(COLOR_FADE_MS))
    }
    val tokens = if (progress.value >= 1f) to else blend(from, to, progress.value)
    val paperColors = remember(tokens) { paperColors(tokens) }
    val material = remember(tokens, darkTheme) { materialColors(tokens, darkTheme) }
    CompositionLocalProvider(LocalPaper provides paperColors, LocalDarkTheme provides darkTheme, LocalThemeId provides themeId) {
        MaterialTheme(
            colorScheme = material,
            typography = InboxTypography,
            shapes = InboxShapes,
        ) {
            // Buttons, chips and switches show keyboard focus and mouse hover in the theme's focus color.
            val ripple = remember(paperColors.focus) { RippleConfiguration(color = paperColors.focus, rippleAlpha = FOCUS_RIPPLE) }
            CompositionLocalProvider(LocalRippleConfiguration provides ripple, content = content)
        }
    }
}

// Each token a [fraction] of the way from [start] to [end]; every theme has the same token names.
private fun blend(start: Map<String, Color>, end: Map<String, Color>, fraction: Float): Map<String, Color> =
    if (fraction >= 1f) end else end.mapValues { (name, color) -> lerp(start[name] ?: color, color, fraction) }

/** Whether the app shows dark now (the phone's choice, or the one set in Settings). */
val LocalDarkTheme = compositionLocalOf { false }

/** The color theme shown. */
val LocalThemeId = compositionLocalOf { ThemePalette.defaultTheme }

/** Shorthand for the current paper colors. */
val paper: PaperColors
    @Composable get() = LocalPaper.current
