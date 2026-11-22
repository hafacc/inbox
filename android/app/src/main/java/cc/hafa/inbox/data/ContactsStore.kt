package cc.hafa.inbox.data

import android.util.Log
import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.contacts.Contact
import cc.hafa.inbox.core.contacts.ContactSource
import cc.hafa.inbox.core.contacts.PeopleClient
import cc.hafa.inbox.core.contacts.SYNC_TOKEN_EXPIRED
import cc.hafa.inbox.core.gmail.GmailException
import cc.hafa.inbox.core.gmail.GmailJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer

private const val TAG = "ContactsStore"
private const val SYNC_EVERY_MS = 6L * 60 * 60 * 1000
private const val MAX_PAGES = 50

/**
 * Google Contacts and "other contacts" on the device for autocomplete, kept current with the
 * People API's sync tokens (only changes come down after the first read).
 */
class ContactsStore(
    private val dao: MailDao,
    private val cache: MailCache,
    private val people: PeopleClient,
    private val onPhotoGone: (url: String) -> Unit = {},
) {
    private val state = MutableStateFlow<List<Contact>>(emptyList())
    private val photoState = MutableStateFlow<Map<String, String>>(emptyMap())
    private val failedState = MutableStateFlow<Set<String>>(emptySet())
    private val lock = Mutex()
    private var lastSync = 0L

    /** Every contact on the device. */
    val contacts: StateFlow<List<Contact>> = state

    /** Photo URLs (without a size) by address key; saved contacts win over other contacts. */
    val photos: StateFlow<Map<String, String>> = photoState

    /** Photo URLs that failed to load; not tried again until the next sync. */
    val failedPhotos: StateFlow<Set<String>> = failedState

    /** Note that a photo failed to load. */
    fun photoFailed(url: String) {
        failedState.value = failedState.value + url
    }

    // A token is only good for the fields it was made with, so asking for more starts afresh.
    private fun tokenKey(source: ContactSource) = "contactsSync:${source.name}:${PeopleClient.FIELDS}"

    private fun prefix(source: ContactSource) = if (source == ContactSource.CONNECTIONS) "people/" else "otherContacts/"

    /** Read what's on the device. */
    suspend fun load() {
        val rows = dao.contacts()
        val (all, photos) = withContext(Dispatchers.Default) {
            val all = rows.mapNotNull { runCatching { GmailJson.decodeFromString(Contact.serializer(), it.json) }.getOrNull() }
            val photos = buildMap {
                // Saved contacts go last so their photo wins.
                for (contact in all.sortedBy { !it.other }) {
                    val url = contact.photoUrl ?: continue
                    for (email in contact.emails) put(addressKey(email), url)
                }
            }
            all to photos
        }
        state.value = all
        photoState.value = photos
    }

    /** Bring the device copy up to date, at most every few hours unless [force]d. Throws when offline. */
    suspend fun sync(force: Boolean = false) = lock.withLock {
        val now = System.currentTimeMillis()
        if (!force && now - lastSync < SYNC_EVERY_MS) return@withLock
        val before = state.value.mapNotNull { it.photoUrl }.toSet()
        for (source in ContactSource.entries) syncSource(source)
        lastSync = now
        load()
        val after = state.value.mapNotNull { it.photoUrl }.toSet()
        (before - after).forEach(onPhotoGone)
        failedState.value = emptySet()
    }

    private suspend fun syncSource(source: ContactSource) {
        val saved = cache.meta(tokenKey(source), String.serializer())
        try {
            readAll(source, saved)
        } catch (error: GmailException) {
            if (error.status != SYNC_TOKEN_EXPIRED || saved == null) throw error
            Log.i(TAG, "contacts sync token expired; reading ${source.name} again")
            readAll(source, null)
        }
    }

    private suspend fun readAll(source: ContactSource, syncToken: String?) {
        var pageToken: String? = null
        var nextSyncToken: String? = null
        val changed = mutableListOf<Contact>()
        val deleted = mutableListOf<String>()
        for (page in 0 until MAX_PAGES) {
            val result = people.page(source, syncToken, pageToken)
            changed += result.changed
            deleted += result.deleted
            nextSyncToken = result.nextSyncToken ?: nextSyncToken
            pageToken = result.nextPageToken ?: break
        }
        if (syncToken == null) dao.clearContacts(prefix(source))
        if (deleted.isNotEmpty()) dao.deleteContacts(deleted)
        if (changed.isNotEmpty()) {
            val rows = withContext(Dispatchers.Default) {
                changed.map { ContactEntity(it.resourceName, GmailJson.encodeToString(Contact.serializer(), it)) }
            }
            dao.putContacts(rows)
        }
        nextSyncToken?.let { cache.putMeta(tokenKey(source), String.serializer(), it) }
    }
}
