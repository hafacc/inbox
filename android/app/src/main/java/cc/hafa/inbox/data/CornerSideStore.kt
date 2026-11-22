package cc.hafa.inbox.data

import android.content.Context
import androidx.core.content.edit
import cc.hafa.inbox.core.menu.CornerSide
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

private const val PREFS = "corner"
private const val SIDE_KEY = "side"

/** Which bottom corner the round buttons sit in: this device's choice, for every account; right by default. */
class CornerSideStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val sideState = MutableStateFlow(if (prefs.getString(SIDE_KEY, null) == "left") CornerSide.LEFT else CornerSide.RIGHT)

    /** The corner the round buttons sit in. */
    val side: StateFlow<CornerSide> = sideState

    /** Move the round buttons to [side] and remember it. */
    fun set(side: CornerSide) {
        sideState.value = side
        prefs.edit { if (side == CornerSide.LEFT) putString(SIDE_KEY, "left") else remove(SIDE_KEY) }
    }
}
