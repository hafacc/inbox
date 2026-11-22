package cc.hafa.inbox.data

import android.content.Context
import cc.hafa.inbox.core.gmail.GmailJson
import cc.hafa.inbox.core.mail.AccountChange
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
private data class ParkedRecord(
    val uid: String,
    val drafts: List<DraftEntity>,
    val queue: List<QueueEntity>,
    val accountQueue: List<AccountChange>,
    val retags: List<TagRename> = emptyList(),
)

/** Unsent work given back to the account it was kept for. */
data class Unparked(
    /** drafts, as stored (sealed) */
    val drafts: List<DraftEntity>,
    /** queued label changes */
    val queue: List<QueueEntity>,
    /** queued label and filter edits, in the order made */
    val accountQueue: List<AccountChange>,
    /** label renames the todos' tags haven't followed yet, oldest first */
    val retags: List<TagRename>,
)

/** Whether a draft is unsent work: changed since Gmail got it, waiting to send, or discarded with Gmail's copy still there. */
private fun isUnsent(draft: DraftEntity): Boolean = draft.dirty || draft.deleted || draft.pendingSendJson != null

private const val RECORD = "record.json"
private const val FILES = "files"

/**
 * Unsent work kept through a sign-out forced by lost Gmail access (docs/compose-contract.md,
 * `blockedOn: "sign-in"`): queued label changes, queued label and filter edits, label renames the
 * todos' tags haven't followed yet, and drafts not yet in Gmail or waiting to send, with their files, as stored (sealed under the account's drafts key, which is kept with them).
 * Everything else of the account is cleared as on any sign-out. Signing back in to the same
 * account puts the work back; another account, or a deliberate sign-out, drops it. One account's
 * work at most is kept, in [root].
 */
class ParkedWork(private val root: File) {
    /** The store in the app's private, never backed up, storage. */
    constructor(context: Context) : this(File(context.noBackupFilesDir, "parked"))

    private val record = File(root, RECORD)

    private fun read(): ParkedRecord? =
        runCatching { GmailJson.decodeFromString(ParkedRecord.serializer(), record.readText()) }.getOrNull()

    /** The account whose work is kept, or null. */
    fun parkedUid(): String? = read()?.uid

    /**
     * Keep [uid]'s unsent [drafts] (with their files from [draftsRoot]), [queue], [accountQueue] and
     * [retags], replacing any work kept before. Returns whether anything was kept.
     */
    fun park(
        uid: String,
        drafts: List<DraftEntity>,
        queue: List<QueueEntity>,
        accountQueue: List<AccountChange>,
        retags: List<TagRename>,
        draftsRoot: File,
    ): Boolean {
        delete()
        val unsent = drafts.filter(::isUnsent)
        if (unsent.isEmpty() && queue.isEmpty() && accountQueue.isEmpty() && retags.isEmpty()) return false
        val files = File(root, FILES)
        for (draft in unsent) {
            File(draftsRoot, draft.localId).takeIf { it.isDirectory }?.copyRecursively(File(files, draft.localId), overwrite = true)
        }
        val kept = ParkedRecord(uid, unsent, queue, accountQueue, retags)
        root.mkdirs()
        // Written last, so a park cut short leaves no record pointing at missing files.
        record.writeText(GmailJson.encodeToString(ParkedRecord.serializer(), kept))
        return true
    }

    /**
     * Give back the work kept for [uid], its files copied into [draftsRoot]; the caller [delete]s
     * the store once the work is written back. Work kept for another account is dropped and null
     * returned.
     */
    fun unpark(uid: String, draftsRoot: File): Unparked? {
        val kept = read()
        if (kept == null || kept.uid != uid) {
            delete()
            return null
        }
        File(root, FILES).takeIf { it.isDirectory }?.copyRecursively(draftsRoot, overwrite = true)
        return Unparked(kept.drafts, kept.queue, kept.accountQueue, kept.retags)
    }

    /** Drop whatever is kept. */
    fun delete() {
        root.deleteRecursively()
    }
}
