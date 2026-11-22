package cc.hafa.inbox.core.gmail

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A Gmail access token and when it stops working (epoch milliseconds). */
data class AccessToken(
    /** the token */
    val accessToken: String,
    /** when it stops working (epoch milliseconds) */
    val expiresAt: Long,
)

/** Keeps one access token until shortly before it expires, sharing a single fetch between callers. */
class TokenCache(
    private val fetchToken: suspend () -> AccessToken,
    private val clock: () -> Long = System::currentTimeMillis,
) : TokenSource {
    private val mutex = Mutex()

    @Volatile
    private var current: AccessToken? = null

    override suspend fun get(): String {
        fresh()?.let { return it }
        return mutex.withLock {
            fresh() ?: fetchToken().also { current = it }.accessToken
        }
    }

    private fun fresh(): String? =
        current?.takeIf { it.expiresAt - MARGIN_MS > clock() }?.accessToken

    override fun invalidate() {
        current = null
    }

    companion object {
        /** How long before expiry a cached token is replaced. */
        const val MARGIN_MS = 60_000L
    }
}
