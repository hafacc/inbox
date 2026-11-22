package cc.hafa.inbox.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.Serializable

/** A thread's list summary, as JSON. */
@Entity(tableName = "summaries")
data class SummaryEntity(
    /** Gmail thread id */
    @PrimaryKey val id: String,
    /** the [cc.hafa.inbox.core.mail.ThreadSummary] */
    val json: String,
)

/** One view's loaded thread ids, as a JSON array. */
@Entity(tableName = "lists")
data class ListEntity(
    /** the view's key */
    @PrimaryKey val key: String,
    /** thread ids, in list order */
    val idsJson: String,
    /** Gmail's token for the next page, or null at the end */
    val nextPageToken: String?,
    /** when it was saved, epoch milliseconds */
    val savedAt: Long,
)

/** A thread saved with its bodies, as JSON. */
@Entity(tableName = "threads")
data class ThreadEntity(
    /** Gmail thread id */
    @PrimaryKey val id: String,
    /** the [cc.hafa.inbox.core.mail.ThreadDetail] */
    val json: String,
    /** when it was last saved or opened, epoch milliseconds */
    val savedAt: Long,
    /** subject, addresses and body text, lower-cased, for searching offline */
    @ColumnInfo(defaultValue = "") val searchText: String,
)

/** What the saved copy knows of one saved thread besides its row and bodies. */
@Entity(tableName = "offline")
data class OfflineEntity(
    /** Gmail thread id */
    @PrimaryKey val id: String,
    /** the thread's history id when it was read */
    val historyId: String,
    /** message id to its label ids, as a JSON object */
    val messagesJson: String,
    /** its newest message's date, epoch milliseconds */
    val newestAt: Long,
    /** when it was last opened on this device, epoch milliseconds, or null if never */
    val openedAt: Long?,
    /** whether any of its messages is encrypted, so it is kept at any age */
    @ColumnInfo(defaultValue = "0") val encrypted: Boolean,
)

/** A label change waiting for Gmail, in the order made. */
@Serializable
@Entity(tableName = "queue")
data class QueueEntity(
    /** order made */
    @PrimaryKey val seq: Long,
    /** Gmail thread id */
    val threadId: String,
    /** label ids to add, as a JSON array */
    val addJson: String,
    /** label ids to remove, as a JSON array */
    val removeJson: String,
    /** whether it is a Done tapped on a notification */
    @ColumnInfo(defaultValue = "0") val fromNotification: Boolean,
)

/** A small keyed value, as JSON: labels, bundles holding inbox threads. */
@Entity(tableName = "meta")
data class MetaEntity(
    /** what the value is */
    @PrimaryKey val key: String,
    /** the value */
    val json: String,
)

/** A message being written, as JSON, with what still has to reach Gmail. */
@Serializable
@Entity(tableName = "drafts")
data class DraftEntity(
    /** the draft's id on this device */
    @PrimaryKey val localId: String,
    /** the draft, sealed */
    val json: String,
    /** changed since Gmail last got it */
    val dirty: Boolean,
    /** discarded; Gmail's copy still has to be deleted */
    val deleted: Boolean,
    /** a send waiting for the draft to reach Gmail, as JSON */
    val pendingSendJson: String?,
    /** when it last changed, epoch milliseconds */
    val updatedAt: Long,
)

/** One Google contact for autocomplete, as JSON. */
@Entity(tableName = "contacts")
data class ContactEntity(
    /** People API resource name */
    @PrimaryKey val resourceName: String,
    /** the contact */
    val json: String,
)

/**
 * A recipient's OpenPGP key as last looked up (docs/pgp-contract.md "Looking up recipients' keys"):
 * the armored key, or null for a miss; where it came from (`autocrypt`, `wkd` or `vks`); when it
 * was fetched, and the date of the newest message whose Autocrypt header set it.
 */
@Entity(tableName = "pgp_keys")
data class PgpKeyEntity(
    /** the recipient's address */
    @PrimaryKey val address: String,
    /** the armored key, or null for a miss */
    val armored: String?,
    /** `autocrypt`, `wkd` or `vks` */
    val source: String,
    /** when it was looked up, epoch milliseconds */
    val fetchedAt: Long,
    /** date of the newest message whose Autocrypt header set it */
    val seenAt: Long,
)

/**
 * A decrypted message, encrypted at rest with this device's cache key ([cc.hafa.inbox.pgp.DeviceVault]):
 * [view] is the message as shown, [preview] its thread-list subject and text; both nonce and ciphertext.
 */
@Entity(tableName = "decrypted", indices = [Index("threadId")])
class DecryptedEntity(
    /** Gmail message id */
    @PrimaryKey val messageId: String,
    /** Gmail thread id */
    val threadId: String,
    /** the message's date, epoch milliseconds */
    val date: Long,
    /** the message as shown, sealed */
    val view: ByteArray,
    /** the thread-list subject and text, sealed */
    val preview: ByteArray,
    /** when it was saved, epoch milliseconds */
    val savedAt: Long,
)

/** A decrypted message's thread-list preview, still encrypted, without the body. */
class DecryptedPreviewRow(
    /** Gmail message id */
    val messageId: String,
    /** Gmail thread id */
    val threadId: String,
    /** the message's date, epoch milliseconds */
    val date: Long,
    /** the thread-list subject and text, sealed */
    val preview: ByteArray,
)

/** Reads and writes of the mail cache. */
@Dao
interface MailDao {
    /** summaries by id */
    @Query("SELECT * FROM summaries WHERE id IN (:ids)")
    suspend fun summaries(ids: List<String>): List<SummaryEntity>

    /** every cached summary, for searching offline */
    @Query("SELECT * FROM summaries")
    suspend fun allSummaries(): List<SummaryEntity>

    /** ids of opened threads whose subject, addresses or body text contain [text] (lower-cased), for searching offline */
    @Query("SELECT id FROM threads WHERE searchText LIKE '%' || :text || '%' ESCAPE '\\'")
    suspend fun threadsContaining(text: String): List<String>

    /** save summaries */
    @Upsert
    suspend fun putSummaries(entities: List<SummaryEntity>)

    /** drop summaries by id */
    @Query("DELETE FROM summaries WHERE id IN (:ids)")
    suspend fun deleteSummaries(ids: List<String>)

    /** one view's list */
    @Query("SELECT * FROM lists WHERE `key` = :key")
    suspend fun list(key: String): ListEntity?

    /** save a view's list */
    @Upsert
    suspend fun putList(entity: ListEntity)

    /** an opened thread's [cc.hafa.inbox.core.mail.ThreadDetail] JSON, without its search text */
    @Query("SELECT json FROM threads WHERE id = :id")
    suspend fun threadJson(id: String): String?

    /** save an opened thread */
    @Upsert
    suspend fun putThread(entity: ThreadEntity)

    /** mark a saved thread as opened now */
    @Query("UPDATE threads SET savedAt = :now WHERE id = :id")
    suspend fun touchThread(id: String, now: Long)

    /** drop saved threads' bodies */
    @Query("DELETE FROM threads WHERE id IN (:ids)")
    suspend fun deleteThreads(ids: List<String>)

    /** drop bodies the saved copy doesn't name, saved before a time */
    @Query("DELETE FROM threads WHERE id NOT IN (SELECT id FROM offline) AND savedAt < :before")
    suspend fun pruneUnsavedThreads(before: Long)

    /** every saved thread's record */
    @Query("SELECT * FROM offline")
    suspend fun offlineThreads(): List<OfflineEntity>

    /** save saved threads' records */
    @Upsert
    suspend fun putOffline(entities: List<OfflineEntity>)

    /** drop saved threads' records */
    @Query("DELETE FROM offline WHERE id IN (:ids)")
    suspend fun deleteOffline(ids: List<String>)

    /** every queued change */
    @Query("SELECT * FROM queue ORDER BY seq")
    suspend fun queue(): List<QueueEntity>

    /** queue a change */
    @Upsert
    suspend fun putQueued(entity: QueueEntity)

    /** drop sent changes */
    @Query("DELETE FROM queue WHERE seq IN (:seqs)")
    suspend fun deleteQueued(seqs: List<Long>)

    /** one keyed value */
    @Query("SELECT * FROM meta WHERE `key` = :key")
    suspend fun meta(key: String): MetaEntity?

    /** save a keyed value */
    @Upsert
    suspend fun putMeta(entity: MetaEntity)

    /** every draft on the device */
    @Query("SELECT * FROM drafts ORDER BY updatedAt")
    suspend fun drafts(): List<DraftEntity>

    /** one draft */
    @Query("SELECT * FROM drafts WHERE localId = :localId")
    suspend fun draft(localId: String): DraftEntity?

    /** save a draft */
    @Upsert
    suspend fun putDraft(entity: DraftEntity)

    /** forget a draft */
    @Query("DELETE FROM drafts WHERE localId = :localId")
    suspend fun deleteDraft(localId: String)

    /** every contact */
    @Query("SELECT * FROM contacts")
    suspend fun contacts(): List<ContactEntity>

    /** save contacts */
    @Upsert
    suspend fun putContacts(entities: List<ContactEntity>)

    /** forget contacts */
    @Query("DELETE FROM contacts WHERE resourceName IN (:names)")
    suspend fun deleteContacts(names: List<String>)

    /** cached keys for these addresses */
    @Query("SELECT * FROM pgp_keys WHERE address IN (:addresses)")
    suspend fun pgpKeys(addresses: List<String>): List<PgpKeyEntity>

    /** save a looked-up key or miss */
    @Upsert
    suspend fun putPgpKey(entity: PgpKeyEntity)

    /** decrypted messages of these ids */
    @Query("SELECT * FROM decrypted WHERE messageId IN (:messageIds)")
    suspend fun decryptedMessages(messageIds: List<String>): List<DecryptedEntity>

    /** every decrypted message, for searching offline */
    @Query("SELECT * FROM decrypted")
    suspend fun allDecrypted(): List<DecryptedEntity>

    /** every decrypted message's preview */
    @Query("SELECT messageId, threadId, date, preview FROM decrypted")
    suspend fun decryptedPreviews(): List<DecryptedPreviewRow>

    /** save a decrypted message */
    @Upsert
    suspend fun putDecrypted(entity: DecryptedEntity)

    /** drop decrypted messages of these threads */
    @Query("DELETE FROM decrypted WHERE threadId IN (:threadIds)")
    suspend fun deleteDecrypted(threadIds: List<String>)

    /** drop decrypted messages of threads the saved copy doesn't name, saved before a time */
    @Query("DELETE FROM decrypted WHERE threadId NOT IN (SELECT id FROM offline) AND savedAt < :before")
    suspend fun pruneUnsavedDecrypted(before: Long)

    /** forget every decrypted message (the device was locked) */
    @Query("DELETE FROM decrypted")
    suspend fun clearDecrypted()

    /** forget every contact of one list, before reading it whole again */
    @Query("DELETE FROM contacts WHERE resourceName LIKE :prefix || '%'")
    suspend fun clearContacts(prefix: String)
}

/** One account's mail cache, for reading offline. */
@Database(
    entities = [
        SummaryEntity::class,
        ListEntity::class,
        ThreadEntity::class,
        QueueEntity::class,
        MetaEntity::class,
        DraftEntity::class,
        ContactEntity::class,
        PgpKeyEntity::class,
        DecryptedEntity::class,
        OfflineEntity::class,
    ],
    version = MailDatabase.VERSION,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class MailDatabase : RoomDatabase() {
    /** the cache's queries */
    abstract fun mailDao(): MailDao

    companion object {
        /**
         * The schema version. Each one's schema is exported to app/schemas; a bump needs an explicit
         * migration, since drafts and queued changes not yet in Gmail live here too.
         */
        const val VERSION = 3

        // Saved-copy records from before encrypted mail was kept at any age don't say which threads
        // are encrypted: the copy starts over and fills again, as on a first start.
        private val SAVED_COPY_ANEW = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE offline ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("DELETE FROM offline")
                db.execSQL("DELETE FROM meta WHERE `key` IN ('offlineHistoryId', 'offlineFilled', 'offlineRetry')")
            }
        }

        /** The file one account's cache lives in. */
        fun fileName(uid: String): String = "mail-${safeFileName(uid)}.db"

        /** Open an account's cache. */
        fun open(context: Context, uid: String): MailDatabase =
            Room.databaseBuilder(context.applicationContext, MailDatabase::class.java, fileName(uid))
                .addMigrations(SAVED_COPY_ANEW)
                .build()
    }
}
