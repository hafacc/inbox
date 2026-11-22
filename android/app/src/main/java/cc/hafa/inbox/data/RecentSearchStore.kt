package cc.hafa.inbox.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/** This device's recent searches, newest first; cleared on sign-out. */
object RecentSearchStore {
    private const val PREFS = "recent_searches"
    private const val KEY = "queries"

    /** The saved searches, or none when nothing readable is saved. */
    fun load(context: Context): List<String> = runCatching {
        val saved = JSONArray(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"))
        (0 until saved.length()).mapNotNull { saved.optString(it).takeIf(String::isNotEmpty) }
    }.getOrDefault(emptyList())

    /** Save [queries] in place of the old list. */
    fun save(context: Context, queries: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, JSONArray(queries).toString()) }
    }

    /** Forget every recent search. */
    fun clear(context: Context) {
        context.deleteSharedPreferences(PREFS)
    }
}
