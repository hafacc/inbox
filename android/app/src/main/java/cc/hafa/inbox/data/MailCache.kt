package cc.hafa.inbox.data

import cc.hafa.inbox.core.gmail.GmailJson
import cc.hafa.inbox.core.gmail.GmailLabel
import cc.hafa.inbox.core.mail.AccountChange
import cc.hafa.inbox.core.mail.LabelChange
import cc.hafa.inbox.core.mail.LoadedList
import cc.hafa.inbox.mail.SavedThread
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.mail.ThreadDetail
import cc.hafa.inbox.core.mail.ThreadSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

private val STRINGS = ListSerializer(String.serializer())
private val STRING_SET = SetSerializer(String.serializer())
private val LABELS = ListSerializer(GmailLabel.serializer())
private val ACCOUNT_CHANGES = ListSerializer(AccountChange.serializer())
private val MESSAGE_LABELS = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
private val TAG_RENAMES = ListSerializer(TagRename.serializer())

private const val LABELS_KEY = "labels"
private const val BUNDLES_KEY = "bundles"
private const val ACCOUNT_QUEUE_KEY = "accountQueue"
private const val RETAGS_KEY = "todoRetags"

/** A label rename the todos' tags follow. */
@Serializable
data class TagRename(
    /** the label's name before */
    val from: String,
    /** its new name */
    val to: String,
)

private val STYLE_SCRIPT = Regex("""<(style|script)\b[\s\S]*?</\1\s*>""", RegexOption.IGNORE_CASE)
private val HTML_TAG = Regex("<[^>]*>")
private val SPACES = Regex("""\s+""")
private val LIKE_SPECIAL = Regex("""[\\%_]""")

// Subject, addresses and body text of a thread, lower-cased: what offline search looks in.
private fun searchText(detail: ThreadDetail): String = buildString {
    append(detail.subject)
    for (message in detail.messages) appendMessage(message)
}.replace(SPACES, " ").lowercase()

private fun StringBuilder.appendMessage(message: MessageView) {
    append('\n').append(message.subject)
    for (person in listOf(message.from) + message.to + message.cc + message.bcc) {
        append('\n').append(person.name).append(' ').append(person.email)
    }
    val body = message.body
    append('\n').append(body.text ?: body.html?.replace(STYLE_SCRIPT, " ")?.replace(HTML_TAG, " ").orEmpty())
}

/** The mail cache in the app's terms; values go in and out as JSON, read and written off the main thread. */
class MailCache(private val dao: MailDao) {
    private suspend fun <Value> decoded(serializer: KSerializer<Value>, json: String): Value? =
        withContext(Dispatchers.Default) { runCatching { GmailJson.decodeFromString(serializer, json) }.getOrNull() }

    private suspend fun <Value> encoded(serializer: KSerializer<Value>, value: Value): String =
        withContext(Dispatchers.Default) { GmailJson.encodeToString(serializer, value) }

    /** cached summaries for these ids */
    suspend fun summaries(ids: List<String>): List<ThreadSummary> {
        val rows = dao.summaries(ids)
        return withContext(Dispatchers.Default) {
            rows.mapNotNull { runCatching { GmailJson.decodeFromString(ThreadSummary.serializer(), it.json) }.getOrNull() }
        }
    }

    /** every cached summary */
    suspend fun allSummaries(): List<ThreadSummary> {
        val rows = dao.allSummaries()
        return withContext(Dispatchers.Default) {
            rows.mapNotNull { runCatching { GmailJson.decodeFromString(ThreadSummary.serializer(), it.json) }.getOrNull() }
        }
    }

    /** ids of opened threads whose subject, addresses or body text contain [word] (lower-cased) */
    suspend fun threadsContaining(word: String): Set<String> =
        dao.threadsContaining(word.replace(LIKE_SPECIAL) { "\\" + it.value }).toSet()

    /** save summaries */
    suspend fun putSummaries(threads: List<ThreadSummary>) {
        val rows = withContext(Dispatchers.Default) {
            threads.map { SummaryEntity(it.id, GmailJson.encodeToString(ThreadSummary.serializer(), it)) }
        }
        dao.putSummaries(rows)
    }

    /** drop summaries */
    suspend fun deleteSummaries(ids: List<String>) = dao.deleteSummaries(ids)

    /** a view's cached list */
    suspend fun list(key: String): LoadedList? {
        val row = dao.list(key) ?: return null
        val ids = decoded(STRINGS, row.idsJson) ?: return null
        return LoadedList(ids = ids, nextPageToken = row.nextPageToken, loaded = true)
    }

    /** save a view's list */
    suspend fun putList(key: String, ids: List<String>, nextPageToken: String?) =
        dao.putList(ListEntity(key, encoded(STRINGS, ids), nextPageToken, System.currentTimeMillis()))

    /** a saved thread */
    suspend fun thread(id: String): ThreadDetail? {
        dao.touchThread(id, System.currentTimeMillis())
        return dao.threadJson(id)?.let { decoded(ThreadDetail.serializer(), it) }
    }

    /** count saved threads as opened now */
    suspend fun touchThreads(ids: List<String>) {
        val now = System.currentTimeMillis()
        for (id in ids) dao.touchThread(id, now)
    }

    /** a saved thread, without counting as opening it */
    suspend fun storedThread(id: String): ThreadDetail? = dao.threadJson(id)?.let { decoded(ThreadDetail.serializer(), it) }

    /** save a thread with its bodies; the saved copy ([offlineThreads]) decides how long it stays */
    suspend fun putThread(detail: ThreadDetail) {
        val now = System.currentTimeMillis()
        val entity = withContext(Dispatchers.Default) {
            ThreadEntity(detail.id, GmailJson.encodeToString(ThreadDetail.serializer(), detail), now, searchText(detail))
        }
        dao.putThread(entity)
    }

    /** every saved thread's record: message id to labels, newest date, when opened, history id */
    suspend fun offlineThreads(): List<Pair<String, SavedThread>> {
        val rows = dao.offlineThreads()
        return withContext(Dispatchers.Default) {
            rows.mapNotNull { row ->
                runCatching { GmailJson.decodeFromString(MESSAGE_LABELS, row.messagesJson) }.getOrNull()?.let { messages ->
                    row.id to SavedThread(row.historyId, messages, row.newestAt, row.openedAt, row.encrypted)
                }
            }
        }
    }

    /** save saved threads' records */
    suspend fun putOfflineThreads(entries: List<Pair<String, SavedThread>>) {
        val rows = withContext(Dispatchers.Default) {
            entries.map { (id, saved) ->
                OfflineEntity(
                    id,
                    saved.historyId,
                    GmailJson.encodeToString(MESSAGE_LABELS, saved.messages),
                    saved.newestAt,
                    saved.openedAt,
                    saved.encrypted,
                )
            }
        }
        dao.putOffline(rows)
    }

    /** drop saved threads' records, bodies and decrypted messages; their rows stay */
    suspend fun deleteOfflineThreads(ids: List<String>) {
        dao.deleteOffline(ids)
        dao.deleteThreads(ids)
        dao.deleteDecrypted(ids)
    }

    /** drop bodies and decrypted messages the saved copy doesn't name, saved before [before] */
    suspend fun pruneUnsavedThreads(before: Long) {
        dao.pruneUnsavedThreads(before)
        dao.pruneUnsavedDecrypted(before)
    }

    /** queued label changes */
    suspend fun queue(): List<LabelChange> = dao.queue().map {
        LabelChange(
            it.seq,
            it.threadId,
            GmailJson.decodeFromString(STRINGS, it.addJson),
            GmailJson.decodeFromString(STRINGS, it.removeJson),
            it.fromNotification,
        )
    }

    /** queue a change */
    suspend fun putQueued(change: LabelChange) = dao.putQueued(
        QueueEntity(
            change.seq,
            change.threadId,
            GmailJson.encodeToString(STRINGS, change.add),
            GmailJson.encodeToString(STRINGS, change.remove),
            change.fromNotification,
        ),
    )

    /** drop sent changes */
    suspend fun deleteQueued(seqs: List<Long>) = dao.deleteQueued(seqs)

    /** the account's labels */
    suspend fun labels(): List<GmailLabel>? = meta(LABELS_KEY, LABELS)

    /** save the account's labels */
    suspend fun putLabels(labels: List<GmailLabel>) = putMeta(LABELS_KEY, LABELS, labels)

    /** queued label and filter changes; null when the saved copy can't be read */
    suspend fun accountQueue(): List<AccountChange>? {
        val saved = dao.meta(ACCOUNT_QUEUE_KEY) ?: return emptyList()
        return decoded(ACCOUNT_CHANGES, saved.json)
    }

    /** save the queued label and filter changes */
    suspend fun putAccountQueue(queue: List<AccountChange>) = putMeta(ACCOUNT_QUEUE_KEY, ACCOUNT_CHANGES, queue)

    /** bundle labels whose own list last held inbox threads */
    suspend fun bundles(): Set<String> = meta(BUNDLES_KEY, STRING_SET) ?: emptySet()

    /** label renames the todos' tags haven't followed yet, oldest first */
    suspend fun retags(): List<TagRename> = meta(RETAGS_KEY, TAG_RENAMES).orEmpty()

    /** save the label renames the todos' tags haven't followed yet */
    suspend fun putRetags(retags: List<TagRename>) = putMeta(RETAGS_KEY, TAG_RENAMES, retags)

    /** save the bundle labels whose own list holds inbox threads */
    suspend fun putBundles(labelIds: Set<String>) = putMeta(BUNDLES_KEY, STRING_SET, labelIds)

    /** a keyed value of the given type, or null when missing or unreadable */
    suspend fun <Value> meta(key: String, serializer: KSerializer<Value>): Value? =
        dao.meta(key)?.let { decoded(serializer, it.json) }

    /** save a keyed value */
    suspend fun <Value> putMeta(key: String, serializer: KSerializer<Value>, value: Value) =
        dao.putMeta(MetaEntity(key, encoded(serializer, value)))
}
