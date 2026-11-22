package cc.hafa.inbox.data

import android.content.Context
import cc.hafa.inbox.core.compose.DraftContent
import cc.hafa.inbox.core.compose.DraftFile
import cc.hafa.inbox.core.compose.PendingSend
import cc.hafa.inbox.core.compose.readAttachment
import cc.hafa.inbox.core.gmail.GmailJson
import cc.hafa.inbox.pgp.DeviceVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A draft as kept on the device. */
data class StoredDraft(
    /** the draft */
    val content: DraftContent,
    /** changed since Gmail last got it */
    val dirty: Boolean,
    /** discarded; Gmail's copy is still to be deleted */
    val deleted: Boolean,
    /** a send waiting for the draft to reach Gmail */
    val pendingSend: PendingSend?,
    /** when it last changed, epoch milliseconds */
    val updatedAt: Long,
)

private const val SEALED_TEXT = "sealed:"
private val SEALED_FILE = "HAFASEAL".toByteArray(Charsets.US_ASCII)

/**
 * Drafts being written: their text in Room, their files in the app's private storage, both sealed
 * with AES-256-GCM under an Android Keystore key ([DeviceVault.drafts]), since a draft can quote
 * decrypted mail. What's here and not yet in Gmail is sent by the offline queue's flush.
 *
 * While [persists] says no (the account has a key this device hasn't unlocked), composer state is
 * kept in memory only, except a send waiting for its draft to reach Gmail, which is written sealed
 * so it isn't lost (docs/pgp-contract.md "Offline cache and search"). [lock] (the key changed
 * on another device) deletes the key and every draft but those sends.
 */
class DraftStore(context: Context, uid: String, private val dao: MailDao) {
    private val root = directoryOf(context, uid)
    private val mutex = Mutex()
    private val vault = DeviceVault.drafts(uid)
    private val memoryRows = ConcurrentHashMap<String, StoredDraft>()
    private val memoryFiles = ConcurrentHashMap<String, ConcurrentHashMap<String, ByteArray>>()

    /** Whether composer state may be written to the device. */
    var persists: () -> Boolean = { true }

    private fun directory(localId: String) = File(root, safeFileName(localId))

    private fun path(localId: String, fileId: String) = File(directory(localId), safeFileName(fileId))

    private fun openFile(bytes: ByteArray): ByteArray =
        if (bytes.size >= SEALED_FILE.size && bytes.copyOfRange(0, SEALED_FILE.size).contentEquals(SEALED_FILE)) {
            vault.open(bytes.copyOfRange(SEALED_FILE.size, bytes.size))
        } else {
            bytes
        }

    /** A file's bytes. */
    fun read(localId: String, file: DraftFile): ByteArray =
        memoryFiles[localId]?.get(safeFileName(file.id)) ?: openFile(path(localId, file.id).readBytes())

    private fun storeOnDisk(localId: String, fileName: String, bytes: ByteArray) {
        File(directory(localId).apply { mkdirs() }, safeFileName(fileName)).writeBytes(SEALED_FILE + vault.seal(bytes))
    }

    private fun store(localId: String, fileId: String, bytes: ByteArray) {
        if (persists()) {
            storeOnDisk(localId, fileId, bytes)
        } else {
            memoryFiles.getOrPut(localId) { ConcurrentHashMap() }[safeFileName(fileId)] = bytes
        }
    }

    /**
     * Copy the file [open] gives into the draft's storage as [fileId], opened and read off the main
     * thread; returns its size, or null when it's too big for a message.
     */
    suspend fun copyIn(localId: String, fileId: String, open: () -> InputStream): Long? = withContext(Dispatchers.IO) {
        open().use(::readAttachment)?.also { store(localId, fileId, it) }?.size?.toLong()
    }

    /** Write bytes into the draft's storage as [fileId]. */
    suspend fun writeFile(localId: String, fileId: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        store(localId, fileId, bytes)
    }

    private fun openText(stored: String): String =
        if (stored.startsWith(SEALED_TEXT)) {
            String(vault.open(Base64.getDecoder().decode(stored.removePrefix(SEALED_TEXT))), Charsets.UTF_8)
        } else {
            stored
        }

    private fun sealText(text: String): String = SEALED_TEXT + Base64.getEncoder().encodeToString(vault.seal(text.toByteArray(Charsets.UTF_8)))

    private fun decode(entity: DraftEntity): StoredDraft? = runCatching {
        StoredDraft(
            content = GmailJson.decodeFromString(DraftContent.serializer(), openText(entity.json)),
            dirty = entity.dirty,
            deleted = entity.deleted,
            pendingSend = entity.pendingSendJson?.let { GmailJson.decodeFromString(PendingSend.serializer(), it) },
            updatedAt = entity.updatedAt,
        )
    }.getOrNull()

    private fun encode(draft: StoredDraft) = DraftEntity(
        localId = draft.content.localId,
        json = sealText(GmailJson.encodeToString(DraftContent.serializer(), draft.content)),
        dirty = draft.dirty,
        deleted = draft.deleted,
        pendingSendJson = draft.pendingSend?.let { GmailJson.encodeToString(PendingSend.serializer(), it) },
        updatedAt = draft.updatedAt,
    )

    // Rows sealed under a key that is gone (after the key changed on another device) can't be read again: delete them unread.
    private suspend fun decodeOrDrop(entity: DraftEntity): StoredDraft? {
        val decoded = withContext(Dispatchers.IO) { decode(entity) }
        if (decoded == null && entity.json.startsWith(SEALED_TEXT) && !vault.exists()) {
            dao.deleteDraft(entity.localId)
            withContext(Dispatchers.IO) { directory(entity.localId).deleteRecursively() }
        }
        return decoded
    }

    /** Every draft on the device, oldest change first. */
    suspend fun all(): List<StoredDraft> {
        val stored = dao.drafts().mapNotNull { decodeOrDrop(it) }.filterNot { memoryRows.containsKey(it.content.localId) }
        return (stored + memoryRows.values).sortedBy { it.updatedAt }
    }

    private suspend fun current(localId: String): StoredDraft? =
        memoryRows[localId] ?: dao.draft(localId)?.let { decodeOrDrop(it) }

    /** One draft, or null. */
    suspend fun get(localId: String): StoredDraft? = current(localId)

    // A draft goes to disk when composer state may be written, or when it is a send that mustn't be lost.
    private suspend fun put(next: StoredDraft) {
        val localId = next.content.localId
        if (persists() || next.pendingSend != null) {
            dao.putDraft(withContext(Dispatchers.IO) { encode(next) })
            memoryRows.remove(localId)
            memoryFiles.remove(localId)?.let { files ->
                withContext(Dispatchers.IO) { files.forEach { (name, bytes) -> storeOnDisk(localId, name, bytes) } }
            }
        } else {
            memoryRows[localId] = next
            if (dao.draft(localId) != null) {
                dao.deleteDraft(localId)
                withContext(Dispatchers.IO) {
                    val files = memoryFiles.getOrPut(localId) { ConcurrentHashMap() }
                    directory(localId).listFiles()?.forEach { file ->
                        runCatching { files.putIfAbsent(file.name, openFile(file.readBytes())) }
                    }
                    directory(localId).deleteRecursively()
                }
            }
        }
    }

    /** Change a draft (or create it from null) in one step, so concurrent saves can't interleave. */
    suspend fun update(localId: String, change: (StoredDraft?) -> StoredDraft?): StoredDraft? = mutex.withLock {
        val next = change(current(localId))
        if (next != null) put(next)
        next
    }

    /**
     * Run [block] with the draft as it is now, while no other change to a draft can run: for
     * acting on what a draft says (an outbox write) only while it still says it. [block] returns
     * the draft to store in its place, or null to leave it, and a result.
     */
    suspend fun <T> whileHeld(localId: String, block: suspend (StoredDraft?) -> Pair<StoredDraft?, T>): T = mutex.withLock {
        val (next, result) = block(current(localId))
        if (next != null) put(next)
        result
    }

    /** Save typed changes; Gmail gets them at the next flush. */
    suspend fun save(content: DraftContent): StoredDraft? = update(content.localId) { current ->
        StoredDraft(
            content = content.copy(
                draftId = current?.content?.draftId ?: content.draftId,
                messageId = current?.content?.messageId ?: content.messageId,
            ),
            dirty = true,
            deleted = false,
            pendingSend = current?.pendingSend,
            updatedAt = System.currentTimeMillis(),
        )
    }

    /** Forget a draft and its files on this device. */
    suspend fun remove(localId: String) {
        mutex.withLock {
            dao.deleteDraft(localId)
            memoryRows.remove(localId)
            memoryFiles.remove(localId)
        }
        withContext(Dispatchers.IO) { directory(localId).deleteRecursively() }
    }

    /** Remove files the draft no longer lists. */
    suspend fun pruneFiles(content: DraftContent) = withContext(Dispatchers.IO) {
        val keep = content.files.map { safeFileName(it.id) }.toSet()
        directory(content.localId).listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        memoryFiles[content.localId]?.keys?.retainAll(keep)
    }

    /** Write down what was kept in memory, once composer state may be written again (after an unlock). */
    suspend fun persistMemory() {
        if (!persists()) return
        mutex.withLock {
            for (draft in memoryRows.values.toList()) put(draft)
        }
    }

    /**
     * The key changed on another device: delete the key drafts were sealed with and every draft on the device, keeping
     * only sends waiting for their draft to reach Gmail, sealed again under a new key.
     */
    suspend fun lock() = mutex.withLock {
        val entities = dao.drafts()
        val pending = entities.mapNotNull { entity ->
            withContext(Dispatchers.IO) {
                val draft = decode(entity)?.takeIf { it.pendingSend != null } ?: return@withContext null
                val files = directory(entity.localId).listFiles().orEmpty().mapNotNull { file ->
                    runCatching { file.name to openFile(file.readBytes()) }.getOrNull()
                }
                draft to files
            }
        }
        for (entity in entities) dao.deleteDraft(entity.localId)
        withContext(Dispatchers.IO) {
            root.deleteRecursively()
            vault.delete()
        }
        memoryRows.clear()
        memoryFiles.clear()
        for ((draft, files) in pending) {
            dao.putDraft(withContext(Dispatchers.IO) { encode(draft) })
            withContext(Dispatchers.IO) { files.forEach { (name, bytes) -> storeOnDisk(draft.content.localId, name, bytes) } }
        }
    }

    /** Stop for good (sign-out): later writes can't make the key again. */
    fun close() {
        vault.close()
        memoryRows.clear()
        memoryFiles.clear()
    }

    companion object {
        /** Where an account's draft files live. */
        fun directoryOf(context: Context, uid: String): File = File(context.filesDir, "drafts/${safeFileName(uid)}")

        /** Delete every draft file of an account and, unless [keepKey] (parked work needs it), the key they were sealed with. */
        fun clear(context: Context, uid: String, keepKey: Boolean = false) {
            directoryOf(context, uid).deleteRecursively()
            if (!keepKey) DeviceVault.drafts(uid).delete()
        }
    }
}
