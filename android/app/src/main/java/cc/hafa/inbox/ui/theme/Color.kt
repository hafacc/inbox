package cc.hafa.inbox.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import cc.hafa.inbox.core.theme.ColorMode
import cc.hafa.inbox.core.theme.ThemePalette
import java.util.concurrent.ConcurrentHashMap

/** The theme's named colors, the same tokens as web's CSS variables (`shared/themes/themes.json`). */
@Immutable
data class PaperColors(
    /** page background */
    val page: Color,
    /** the menu drawer's background */
    val sidebar: Color,
    /** card background */
    val card: Color,
    /** menus and sheets above a card */
    val raised: Color,
    /** muted fill: chips, tiles */
    val muted: Color,
    /** a pressed or hovered row */
    val hover: Color,
    /** search and text fields */
    val field: Color,
    /** card borders */
    val border: Color,
    /** stronger borders: switches off, dividers that must show */
    val borderStrong: Color,
    /** lines between rows inside a card */
    val separator: Color,
    /** body text */
    val text: Color,
    /** secondary text */
    val textSecondary: Color,
    /** faint text and icons */
    val faint: Color,
    /** the theme's color: compose, links, the current mailbox */
    val accent: Color,
    /** the accent as text on cards and on [accentSoft] */
    val accentStrong: Color,
    /** text on [accent] */
    val onAccent: Color,
    /** a light fill of the accent */
    val accentSoft: Color,
    /** focus outlines */
    val focus: Color,
    /** snooze */
    val snooze: Color,
    /** the snoozed marker's fill */
    val snoozeSoft: Color,
    /** text on [snoozeSoft] */
    val onSnoozeSoft: Color,
    /** done */
    val done: Color,
    /** done's fill */
    val doneSoft: Color,
    /** delete and errors */
    val danger: Color,
    /** the undo bar */
    val snackbar: Color,
    /** text on [snackbar] */
    val onSnackbar: Color,
    /** the undo bar's button */
    val snackbarAccent: Color,
    /** encrypted mail: locks and the "Encrypted" label */
    val lock: Color,
    /** avatar fills and their initials' colors, picked by `avatarIndex` */
    val avatars: List<Pair<Color, Color>>,
    /** label colors by tone (`red`, `blue`, …), picked by `labelTone` */
    val labelTones: Map<String, Color>,
)

private val tokenCache = ConcurrentHashMap<Pair<String, Boolean>, Map<String, Color>>()

/** The colors of a theme in a mode as Compose colors, by token name; made once per theme and mode. */
fun themeTokens(themeId: String, dark: Boolean): Map<String, Color> = tokenCache.getOrPut(themeId to dark) {
    ThemePalette.colors(themeId, if (dark) ColorMode.DARK else ColorMode.LIGHT).mapValues { (_, argb) -> Color(argb) }
}

/** The paper colors from a theme's tokens. */
fun paperColors(tokens: Map<String, Color>): PaperColors {
    fun token(name: String): Color = tokens.getValue(name)
    return PaperColors(
        page = token("bg"),
        sidebar = token("sidebar"),
        card = token("surface"),
        raised = token("surface-raised"),
        muted = token("surface-muted"),
        hover = token("surface-hover"),
        field = token("field"),
        border = token("border"),
        borderStrong = token("border-strong"),
        separator = token("separator"),
        text = token("text"),
        textSecondary = token("text-secondary"),
        faint = token("muted"),
        accent = token("accent"),
        accentStrong = token("accent-strong"),
        onAccent = token("on-accent"),
        accentSoft = token("accent-soft"),
        focus = token("focus"),
        snooze = token("snooze"),
        snoozeSoft = token("snooze-soft"),
        onSnoozeSoft = token("on-snooze-soft"),
        done = token("done"),
        doneSoft = token("done-soft"),
        danger = token("danger"),
        snackbar = token("snackbar"),
        onSnackbar = token("on-snackbar"),
        snackbarAccent = token("snackbar-accent"),
        lock = token("done"),
        avatars = (0 until ThemePalette.avatarCount).map { token("avatar-$it") to token("on-avatar-$it") },
        labelTones = tokens.filterKeys { it.startsWith("label-") }.mapKeys { (name, _) -> name.removePrefix("label-") },
    )
}

/** Material's roles from a theme's tokens: primary is the accent, so stock components (switches, fields, selection) match. */
fun materialColors(tokens: Map<String, Color>, dark: Boolean): ColorScheme {
    fun token(name: String): Color = tokens.getValue(name)
    return if (dark) {
        darkColorScheme(
            primary = token("accent"),
            onPrimary = token("on-accent"),
            primaryContainer = token("accent-soft"),
            onPrimaryContainer = token("accent-strong"),
            inversePrimary = token("snackbar-accent"),
            secondary = token("done"),
            onSecondary = token("on-done"),
            secondaryContainer = token("done-soft"),
            onSecondaryContainer = token("done"),
            tertiary = token("snooze"),
            onTertiary = token("on-snooze"),
            tertiaryContainer = token("snooze-soft"),
            onTertiaryContainer = token("on-snooze-soft"),
            background = token("bg"),
            onBackground = token("text"),
            surface = token("bg"),
            onSurface = token("text"),
            surfaceVariant = token("surface-muted"),
            onSurfaceVariant = token("text-secondary"),
            surfaceTint = token("accent"),
            inverseSurface = token("snackbar"),
            inverseOnSurface = token("on-snackbar"),
            error = token("danger"),
            onError = token("on-danger"),
            errorContainer = token("danger-soft"),
            onErrorContainer = token("danger"),
            outline = token("border-strong"),
            outlineVariant = token("border"),
            scrim = token("scrim"),
            surfaceBright = token("surface"),
            surfaceContainer = token("surface-raised"),
            surfaceContainerHigh = token("surface-raised"),
            surfaceContainerHighest = token("surface-muted"),
            surfaceContainerLow = token("surface"),
            surfaceContainerLowest = token("surface"),
            surfaceDim = token("bg"),
        )
    } else {
        lightColorScheme(
            primary = token("accent"),
            onPrimary = token("on-accent"),
            primaryContainer = token("accent-soft"),
            onPrimaryContainer = token("accent-strong"),
            inversePrimary = token("snackbar-accent"),
            secondary = token("done"),
            onSecondary = token("on-done"),
            secondaryContainer = token("done-soft"),
            onSecondaryContainer = token("done"),
            tertiary = token("snooze"),
            onTertiary = token("on-snooze"),
            tertiaryContainer = token("snooze-soft"),
            onTertiaryContainer = token("on-snooze-soft"),
            background = token("bg"),
            onBackground = token("text"),
            surface = token("bg"),
            onSurface = token("text"),
            surfaceVariant = token("surface-muted"),
            onSurfaceVariant = token("text-secondary"),
            surfaceTint = token("accent"),
            inverseSurface = token("snackbar"),
            inverseOnSurface = token("on-snackbar"),
            error = token("danger"),
            onError = token("on-danger"),
            errorContainer = token("danger-soft"),
            onErrorContainer = token("danger"),
            outline = token("border-strong"),
            outlineVariant = token("border"),
            scrim = token("scrim"),
            surfaceBright = token("surface"),
            surfaceContainer = token("surface-raised"),
            surfaceContainerHigh = token("surface-raised"),
            surfaceContainerHighest = token("surface-muted"),
            surfaceContainerLow = token("surface"),
            surfaceContainerLowest = token("surface"),
            surfaceDim = token("bg"),
        )
    }
}

/** The current paper colors. */
val LocalPaper = compositionLocalOf { paperColors(themeTokens(ThemePalette.defaultTheme, dark = false)) }
