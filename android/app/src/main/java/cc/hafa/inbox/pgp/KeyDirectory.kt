package cc.hafa.inbox.pgp

import android.util.Log
import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.pgp.keyFromAnswer
import cc.hafa.inbox.core.pgp.vksUrl
import cc.hafa.inbox.core.pgp.wkdUrls
import cc.hafa.inbox.core.runSuspendCatching
import cc.hafa.inbox.data.MailDao
import cc.hafa.inbox.data.PgpKeyEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

private const val TAG = "KeyDirectory"
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val FOUND_KEEP_MS = 7 * DAY_MS
private const val MISS_KEEP_MS = DAY_MS
private const val TIMEOUT_MS = 5_000
private const val MAX_ANSWER_BYTES = 256 * 1024

// Where a cached key came from, as the cache stores it.
private enum class KeySource(val wire: String) {
    AUTOCRYPT("autocrypt"),
    WKD("wkd"),
    VKS("vks"),
}

// What a lookup of one URL gave: a key and where from, a definite "none", or no answer (offline, server trouble).
private sealed interface Answer {
    data class Found(val key: String, val source: KeySource) : Answer
    data object None : Answer
    data object Unreachable : Answer
}

/**
 * Recipients' OpenPGP keys (docs/pgp-contract.md "Looking up recipients' keys"): the device's
 * cache first, then WKD (advanced, then direct), then keys.openpgp.org. A found key is reused for
 * 7 days and a miss for 1 day; Autocrypt headers on incoming mail set a sender's key directly.
 * [known] holds what is known so far, address → armored key or null, for the composer's locks.
 */
class KeyDirectory(private val dao: MailDao, private val scope: CoroutineScope) {
    private val knownState = MutableStateFlow<Map<String, String?>>(emptyMap())
    private val pending = mutableMapOf<String, Job>()
    private val locks = mutableMapOf<String, Mutex>()

    /** Lower-cased address → armored key (or null for none), for addresses looked up so far. */
    val known: StateFlow<Map<String, String?>> = knownState

    private fun remember(address: String, armored: String?) {
        knownState.update { it + (address to armored) }
    }

    private fun lockFor(address: String): Mutex = synchronized(locks) { locks.getOrPut(address) { Mutex() } }

    /** The cached key for [address] whatever its age, without the network. */
    suspend fun cached(address: String): String? {
        val wanted = addressKey(address)
        val entity = runSuspendCatching { dao.pgpKeys(listOf(wanted)) }.getOrDefault(emptyList()).firstOrNull()
        entity?.let { remember(wanted, it.armored) }
        return entity?.armored
    }

    /** Look up [address]'s key: the cache while fresh, else the network; null when none was found. */
    suspend fun lookup(address: String): String? {
        val wanted = addressKey(address)
        if (wanted.isEmpty() || !wanted.contains('@')) return null
        return lockFor(wanted).withLock {
            val now = System.currentTimeMillis()
            val entity = runSuspendCatching { dao.pgpKeys(listOf(wanted)) }.getOrDefault(emptyList()).firstOrNull()
            val fresh = entity != null && now - entity.fetchedAt < if (entity.armored != null) FOUND_KEEP_MS else MISS_KEEP_MS
            if (entity != null && fresh) {
                remember(wanted, entity.armored)
                entity.armored
            } else {
                when (val answer = fetch(wanted)) {
                    is Answer.Found -> {
                        save(PgpKeyEntity(wanted, answer.key, answer.source.wire, now, entity?.seenAt ?: 0L))
                        answer.key
                    }
                    Answer.None -> {
                        // An Autocrypt key stays until a lookup finds a newer one; a miss doesn't erase it.
                        val kept = entity?.armored?.takeIf { entity.source == KeySource.AUTOCRYPT.wire }
                        save(PgpKeyEntity(wanted, kept, entity?.source ?: KeySource.WKD.wire, now, entity?.seenAt ?: 0L))
                        kept
                    }
                    Answer.Unreachable -> entity?.armored.also { remember(wanted, it) }
                }
            }
        }
    }

    /** Look up these addresses in the background, never blocking the caller (composing). */
    fun request(addresses: Collection<String>) {
        for (address in addresses.map(::addressKey).filter { it.contains('@') }.distinct()) {
            if (pending[address]?.isActive == true) continue
            pending[address] = scope.launch {
                try {
                    lookup(address)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "lookup failed", error)
                }
            }
        }
    }

    /** Keys for every address (cache or network), lower-cased address → key or null. */
    suspend fun keysFor(addresses: Collection<String>): Map<String, String?> =
        addresses.map(::addressKey).distinct().associateWith { lookup(it) }

    /**
     * The key an incoming message's Autocrypt header gave for its sender, kept when that message is
     * newer than the one that set the cached key.
     */
    suspend fun noteAutocrypt(address: String, armored: String, messageDate: Long) {
        val wanted = addressKey(address)
        lockFor(wanted).withLock {
            val entity = runSuspendCatching { dao.pgpKeys(listOf(wanted)) }.getOrDefault(emptyList()).firstOrNull()
            if (entity != null && entity.seenAt >= messageDate) return@withLock
            save(PgpKeyEntity(wanted, armored, KeySource.AUTOCRYPT.wire, System.currentTimeMillis(), messageDate))
        }
    }

    private suspend fun save(entity: PgpKeyEntity) {
        remember(entity.address, entity.armored)
        runSuspendCatching { dao.putPgpKey(entity) }.onFailure { Log.w(TAG, "key cache write failed", it) }
    }

    private suspend fun fetch(address: String): Answer {
        val urls = runCatching { wkdUrls(address) }.getOrNull() ?: return Answer.None
        for (url in listOf(urls.advanced, urls.direct)) {
            (get(url, address, KeySource.WKD) as? Answer.Found)?.let { return it }
        }
        // Most domains have no WKD host at all, so only keys.openpgp.org's answer tells offline from "none".
        return get(vksUrl(address), address, KeySource.VKS)
    }

    private suspend fun get(url: String, address: String, source: KeySource): Answer = withContext(Dispatchers.IO) {
        val connection = try {
            URI(url).toURL().openConnection() as HttpURLConnection
        } catch (_: Exception) {
            return@withContext Answer.None
        }
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = true
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val bytes = connection.inputStream.use { it.readNBytes(MAX_ANSWER_BYTES + 1) }
                    val key = if (bytes.size > MAX_ANSWER_BYTES) null else keyFromAnswer(bytes, address)
                    key?.let { Answer.Found(it, source) } ?: Answer.None
                }
                HttpURLConnection.HTTP_NOT_FOUND -> Answer.None
                else -> Answer.Unreachable
            }
        } catch (_: IOException) {
            Answer.Unreachable
        } finally {
            connection.disconnect()
        }
    }
}
