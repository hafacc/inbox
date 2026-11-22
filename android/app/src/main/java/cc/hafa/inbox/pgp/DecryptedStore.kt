package cc.hafa.inbox.pgp

import android.util.Log
import cc.hafa.inbox.core.gmail.GmailJson
import cc.hafa.inbox.core.mail.MessageView
import cc.hafa.inbox.core.pgp.DecryptedPreview
import cc.hafa.inbox.core.runSuspendCatching
import cc.hafa.inbox.data.DecryptedEntity
import cc.hafa.inbox.data.MailDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "DecryptedStore"
private const val QUERY_CHUNK = 500

/** A decrypted message from the cache, with its thread. */
class StoredMessage(
    /** id of its thread */
    val threadId: String,
    /** the decrypted message */
    val view: MessageView,
)

/**
 * Decrypted messages in the offline cache, encrypted at rest (docs/pgp-contract.md "Offline cache
 * and search").
 *
 * Each message (as shown, JSON) and its thread-list preview are sealed with AES-256-GCM under a key
 * in Android Keystore ([DeviceVault.mailCache]) and kept in the account's Room database beside the
 * rest of its cache. The key never leaves Keystore and needs no screen unlock, the same standard as
 * the unlocked private key; sign-out and a key changed on another device delete the key with the rows, so nothing left
 * on disk can be read again. Chosen over SQLCipher (a second database engine and a passphrase to
 * keep) and Jetpack's EncryptedFile (deprecated) as the simplest standard option. Rows stay as long
 * as their thread stays in the saved copy ([cc.hafa.inbox.mail.OfflineCopy]).
 */
class DecryptedStore(private val dao: MailDao, private val vault: DeviceVault) {
    private fun open(sealed: ByteArray): String? =
        runCatching { String(vault.open(sealed), Charsets.UTF_8) }.onFailure { Log.w(TAG, "cache entry unreadable", it) }.getOrNull()

    private fun seal(text: String): ByteArray = vault.seal(text.toByteArray(Charsets.UTF_8))

    /** Decrypted messages kept for these ids. */
    suspend fun get(messageIds: List<String>): Map<String, MessageView> {
        if (messageIds.isEmpty()) return emptyMap()
        val rows = runSuspendCatching { messageIds.chunked(QUERY_CHUNK).flatMap { dao.decryptedMessages(it) } }.getOrDefault(emptyList())
        return withContext(Dispatchers.IO) {
            rows.mapNotNull { row ->
                val json = open(row.view) ?: return@mapNotNull null
                runCatching { row.messageId to GmailJson.decodeFromString(MessageView.serializer(), json) }.getOrNull()
            }.toMap()
        }
    }

    /** Keep a decrypted message. */
    suspend fun put(threadId: String, view: MessageView) {
        val now = System.currentTimeMillis()
        val entity = withContext(Dispatchers.IO) {
            DecryptedEntity(
                messageId = view.id,
                threadId = threadId,
                date = view.date,
                view = seal(GmailJson.encodeToString(MessageView.serializer(), view)),
                preview = seal(GmailJson.encodeToString(DecryptedPreview.serializer(), DecryptedPreview(view.subject, view.snippet, view.date))),
                savedAt = now,
            )
        }
        runSuspendCatching { dao.putDecrypted(entity) }.onFailure { Log.w(TAG, "cache write failed", it) }
    }

    /** Each thread's preview: its first decrypted message's subject and its newest one's text. */
    suspend fun previews(): Map<String, DecryptedPreview> {
        val rows = runSuspendCatching { dao.decryptedPreviews() }.getOrDefault(emptyList())
        val opened = withContext(Dispatchers.IO) {
            rows.mapNotNull { row ->
                val json = open(row.preview) ?: return@mapNotNull null
                runCatching { row.threadId to GmailJson.decodeFromString(DecryptedPreview.serializer(), json) }.getOrNull()
            }
        }
        return opened.groupBy({ it.first }, { it.second }).mapValues { (_, previews) -> threadPreview(previews) }
    }

    /** Every decrypted message kept, for searching offline. */
    suspend fun all(): List<StoredMessage> {
        val rows = runSuspendCatching { dao.allDecrypted() }.getOrDefault(emptyList())
        return withContext(Dispatchers.IO) {
            rows.mapNotNull { row ->
                val json = open(row.view) ?: return@mapNotNull null
                runCatching { StoredMessage(row.threadId, GmailJson.decodeFromString(MessageView.serializer(), json)) }.getOrNull()
            }
        }
    }

    /** Stop for good (sign-out): later writes can't make the key again. */
    fun close() {
        vault.close()
    }

    /** Forget every decrypted message and the key they were sealed with. */
    suspend fun clear() {
        runSuspendCatching { dao.clearDecrypted() }
        vault.delete()
    }

    companion object {
        /** A thread's preview from its messages' previews. */
        fun threadPreview(previews: List<DecryptedPreview>): DecryptedPreview {
            val first = previews.minBy { it.date }
            val newest = previews.maxBy { it.date }
            return DecryptedPreview(first.subject, newest.snippet, newest.date)
        }
    }
}
