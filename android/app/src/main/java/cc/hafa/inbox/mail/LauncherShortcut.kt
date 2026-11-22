package cc.hafa.inbox.mail

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Log
import cc.hafa.inbox.MainActivity
import cc.hafa.inbox.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What holding the app's icon offers (res/xml/shortcuts.xml), by its intent's action. */
enum class LauncherShortcut(
    /** the shortcut intent's action */
    val action: String,
) {
    /** a new message in the composer */
    NEW_EMAIL("cc.hafa.inbox.action.NEW_EMAIL"),

    /** a new todo at the top of the inbox, open to edit */
    NEW_TODO("cc.hafa.inbox.action.NEW_TODO"),

    /** the search bar, open to type */
    SEARCH("cc.hafa.inbox.action.SEARCH"),
}

/**
 * The launcher shortcut [intent] asks for, or null when it asks for none. An intent delivered again
 * because the app was opened from Recents asks for nothing: it was taken already.
 */
fun launcherShortcutOf(intent: Intent): LauncherShortcut? =
    if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
        null
    } else {
        LauncherShortcut.entries.firstOrNull { it.action == intent.action }
    }

/** The launcher shortcut waiting for the mail screens: kept across sign-in, taken once. */
class LauncherRequests {
    private val pending = MutableStateFlow<LauncherShortcut?>(null)

    /** The shortcut waiting, or null. */
    val requests: StateFlow<LauncherShortcut?> = pending.asStateFlow()

    /** Ask for [shortcut], in place of any still waiting. */
    fun ask(shortcut: LauncherShortcut) {
        pending.value = shortcut
    }

    /** Take [shortcut] if it is still the one waiting; false when it was taken already. */
    fun take(shortcut: LauncherShortcut): Boolean = pending.compareAndSet(shortcut, null)
}

private const val NEW_TODO_ID = "new_todo"
private const val SHORTCUT_TAG = "LauncherShortcut"

/**
 * Offer New todo on the app's icon while [todosOn], and take it away (a copy pinned to the home
 * screen greyed out, saying todos are off) while they are off. It is the one shortcut that comes
 * and goes, so it isn't in res/xml/shortcuts.xml, whose shortcuts can't be turned off.
 */
fun offerTodoShortcut(context: Context, todosOn: Boolean) {
    val manager = context.getSystemService(ShortcutManager::class.java) ?: return
    // A launcher refusing a shortcut is no reason to stop the app.
    runCatching {
        if (todosOn) {
            val label = context.getString(R.string.shortcut_new_todo)
            val shortcut = ShortcutInfo.Builder(context, NEW_TODO_ID)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_todo))
                .setIntent(Intent(LauncherShortcut.NEW_TODO.action).setClass(context, MainActivity::class.java))
                .setRank(1)
                .build()
            manager.enableShortcuts(listOf(NEW_TODO_ID))
            manager.pushDynamicShortcut(shortcut)
        } else {
            manager.removeDynamicShortcuts(listOf(NEW_TODO_ID))
            manager.disableShortcuts(listOf(NEW_TODO_ID), context.getString(R.string.shortcut_todos_off))
        }
    }.onFailure { Log.w(SHORTCUT_TAG, "todo shortcut not updated", it) }
}
