package cc.hafa.inbox.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS = "focus"
private const val ON_KEY = "on"

/** Focus mode: the inbox shows only pinned threads and todos. This device's choice, for every account. */
class FocusStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val onState = MutableStateFlow(prefs.getBoolean(ON_KEY, false))

    /** Whether focus mode is on. */
    val on: StateFlow<Boolean> = onState

    /** Turn focus mode on or off and remember it. */
    fun set(on: Boolean) {
        onState.value = on
        prefs.edit { if (on) putBoolean(ON_KEY, true) else remove(ON_KEY) }
    }

    /** Switch focus mode over. */
    fun toggle() = set(!onState.value)
}
