package cc.hafa.inbox.data

import android.content.Context
import androidx.core.content.edit
import cc.hafa.inbox.core.theme.ThemePalette
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS = "theme"
private const val THEME_KEY = "colorTheme"
private const val MODE_KEY = "mode"

/** Light, dark, or whichever the phone uses. */
enum class ThemeMode {
    /** follow the phone */
    SYSTEM,

    /** always light */
    LIGHT,

    /** always dark */
    DARK,
}

/**
 * The look on this device: the color theme shown (the account's, kept here so the first frame has it
 * before the account loads) and the light/dark choice, which is this device's alone, as on web.
 */
class ThemeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val themeState = MutableStateFlow(ThemePalette.themeIdFrom(prefs.getString(THEME_KEY, null)))
    private val modeState = MutableStateFlow(
        ThemeMode.entries.firstOrNull { it.name == prefs.getString(MODE_KEY, null) } ?: ThemeMode.SYSTEM,
    )

    /** The theme's id. */
    val themeId: StateFlow<String> = themeState

    /** The light/dark choice. */
    val mode: StateFlow<ThemeMode> = modeState

    /** Show a theme and remember it on this device. */
    fun show(id: String) {
        val known = ThemePalette.themeIdFrom(id)
        if (known == themeState.value) return
        themeState.value = known
        prefs.edit { putString(THEME_KEY, known) }
    }

    /** Choose light, dark or the phone's, and remember it on this device. */
    fun setMode(mode: ThemeMode) {
        modeState.value = mode
        prefs.edit { if (mode == ThemeMode.SYSTEM) remove(MODE_KEY) else putString(MODE_KEY, mode.name) }
    }
}
