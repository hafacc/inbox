package cc.hafa.inbox.ui

import androidx.compose.runtime.saveable.Saver
import cc.hafa.inbox.core.gmail.GmailJson

/**
 * Saver that keeps a serializable value as JSON text across rotation and process death; text
 * that no longer reads (saved by an older build) restores nothing, so the initial value shows.
 */
inline fun <reified T> jsonSaver(): Saver<T, String> = Saver(
    save = { GmailJson.encodeToString(it) },
    restore = { runCatching { GmailJson.decodeFromString<T>(it) }.getOrNull() },
)
