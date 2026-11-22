package cc.hafa.inbox.core.gmail

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.math.roundToLong

// How a device paces its Gmail calls (design.md, "Google access"), and when it shows itself
// offline from how its Gmail calls went, the same as shared/src/gmail-pace.ts; both run
// shared/testdata/gmail-pace-fixtures.json.

/** Most Gmail calls one device runs at once. */
const val MAX_CONCURRENT = 4

/** The pause after the first usage limit in a row, before jitter. */
const val FIRST_BACKOFF_MS = 1_000L

/** The longest pause, before jitter, however many limits in a row. */
const val MAX_BACKOFF_MS = 32_000L

/** The longest `Retry-After` heeded, in seconds: a longer one would hold every call, sends too. */
const val MAX_RETRY_AFTER_SECONDS = 60L

/** Times a read is tried again after a usage limit before its error reaches the caller. */
const val READ_RETRIES = 4

/** Most saved-copy reads one device runs at once. */
const val MAX_SAVING = 2

/**
 * The least time between the starts of two saved-copy reads: at most 4 a second, about 40 of
 * Gmail's 250 quota units a second for each user (shared by all their devices), so the rest stay
 * free for what the user asks.
 */
const val SAVING_SPACING_MS = 250L

/**
 * How long saved-copy reads stay held after a usage limit's pause ends, so what the user asks
 * goes alone while Gmail's quota refills.
 */
const val SAVING_HOLD_MS = 30_000L

private const val HTTP_TOO_MANY = 429
private const val HTTP_FORBIDDEN = 403
private const val MS_PER_SECOND = 1_000L
private const val MAX_DOUBLINGS = 16

// Reasons Google gives a 403 for limits that lift on their own, unlike a refusal.
private val LIMIT_REASONS = setOf("rateLimitExceeded", "userRateLimitExceeded", "RATE_LIMIT_EXCEEDED")

/** How urgent a Gmail call is, most urgent first. */
enum class CallPriority {
    /** a user's action: a change, a send, a draft */
    ACTION,

    /** what is on screen: the open thread, the list's first page, an open bundle's next page */
    VISIBLE,

    /** a prefetch, a refetch, or summaries off screen */
    BACKGROUND,

    /** a read that fills the saved copy: it yields to every other call and is paced */
    SAVING,
}

/**
 * The urgency of the Gmail reads made in a coroutine; writes are always [CallPriority.ACTION]. It
 * may [raise] while they wait (a refresh running in the background that the user now waits for);
 * reads already waiting go at the new urgency once [RateGate.poke] looks again.
 */
class GmailPriority(
    initial: CallPriority,
) : AbstractCoroutineContextElement(Key) {
    @Volatile private var current = initial

    /** the urgency now */
    val priority: CallPriority get() = current

    /** Make it at least as urgent as [to]; whether it rose. */
    @Synchronized
    fun raise(to: CallPriority): Boolean = (to < current).also { if (it) current = to }

    /** The key [GmailPriority] is found by in a coroutine context. */
    companion object Key : CoroutineContext.Key<GmailPriority>
}

/** A call waiting for its turn. */
data class WaitingCall(
    /** the caller's handle for it */
    val id: String,
    /** how urgent it is */
    val priority: CallPriority,
    /** when it asked, as a running count; earlier goes first among equals */
    val order: Long,
)

/** Whether a Google API error status and reason are a usage limit, which passes if tried later. */
fun isUsageLimit(status: Int, reason: String?): Boolean =
    status == HTTP_TOO_MANY || (status == HTTP_FORBIDDEN && reason in LIMIT_REASONS)

/**
 * The pause after the [strike]-th usage limit in a row (1 for the first): Google's `Retry-After`
 * when it gives one (at most [MAX_RETRY_AFTER_SECONDS]), else [FIRST_BACKOFF_MS] doubling up to [MAX_BACKOFF_MS]. Either is stretched
 * by [jitter] (0 up to 1, random in the apps) by up to half.
 */
fun backoffMs(strike: Int, retryAfterSeconds: Long?, jitter: Double): Long {
    val base = if (retryAfterSeconds != null && retryAfterSeconds >= 0) {
        minOf(retryAfterSeconds, MAX_RETRY_AFTER_SECONDS) * MS_PER_SECOND
    } else {
        minOf(MAX_BACKOFF_MS, FIRST_BACKOFF_MS shl (strike - 1).coerceIn(0, MAX_DOUBLINGS))
    }
    val spread = jitter.coerceIn(0.0, 1.0)
    return (base * (1 + spread / 2)).roundToLong()
}

/** Whether a call that hit a usage limit on try [attempt] (0 for the first) is tried again: only a read, up to [READ_RETRIES] times. */
fun retriesAfterLimit(isRead: Boolean, attempt: Int): Boolean = isRead && attempt < READ_RETRIES

/** What the device's calls are doing now, as the choice of the next one needs it. */
data class PaceState(
    /** calls running */
    val running: Int,
    /** of those, saved-copy reads (started as [CallPriority.SAVING]) */
    val runningSaving: Int,
    /** when calls may go again after a usage limit, epoch milliseconds; 0 or past when they may go now */
    val pausedUntil: Long,
    /** when the last saved-copy read started, epoch milliseconds; 0 for never */
    val savingStartedAt: Long,
    /** when saved-copy reads may go again after a usage limit (see [savingHeldUntil]); 0 or past when they may */
    val savingHeldUntil: Long = 0L,
)

/** When saved-copy reads may go again after a usage limit whose pause ends at [pausedUntil]. */
fun savingHeldUntil(pausedUntil: Long): Long = pausedUntil + SAVING_HOLD_MS

/** The next turn: the waiting call to start now, if any, and else when to look again, if a clock (not a call ending) frees one. */
data class Turn(
    /** the id of the call to start now, or null */
    val id: String?,
    /** when a waiting call may start though none can now, epoch milliseconds, or null */
    val wakeAt: Long?,
)

/**
 * The next turn. None while [MAX_CONCURRENT] run, or until a pause ends ([PaceState.pausedUntil]
 * after [now]); else the most urgent waiting call, the earliest among equals. A saved-copy read
 * ([CallPriority.SAVING]) yields to every other call, waiting or running, runs at most [MAX_SAVING]
 * at once, starts at least [SAVING_SPACING_MS] after the one before, and waits out the hold after a
 * usage limit ([PaceState.savingHeldUntil]).
 */
fun nextCall(waiting: List<WaitingCall>, state: PaceState, now: Long): Turn {
    val best = waiting.minWithOrNull(compareBy<WaitingCall> { it.priority.ordinal }.thenBy { it.order })
    val ready = maxOf(state.savingStartedAt + SAVING_SPACING_MS, state.savingHeldUntil)
    return when {
        best == null || state.running >= MAX_CONCURRENT -> Turn(null, null)
        state.pausedUntil > now -> Turn(null, state.pausedUntil)
        best.priority != CallPriority.SAVING -> Turn(best.id, null)
        state.running > state.runningSaving || state.runningSaving >= MAX_SAVING -> Turn(null, null)
        ready > now -> Turn(null, ready)
        else -> Turn(best.id, null)
    }
}

/** Something that tells the app whether Gmail can be reached. */
sealed interface ConnectionEvent {
    /** A Gmail call came back with an HTTP status, error or not. */
    data class Answered(
        /** HTTP status */
        val status: Int,
    ) : ConnectionEvent

    /** A Gmail call got no answer: no network, the host couldn't be found or connected to. */
    data object Unanswered : ConnectionEvent

    /** A Gmail call was given up after its time limit, which a slow link does too. */
    data object TimedOut : ConnectionEvent

    /** The device gained a network. */
    data object NetworkUp : ConnectionEvent

    /** The device has no network left. */
    data object NetworkDown : ConnectionEvent
}

/** Whether the app shows itself offline after [event], given whether it did before ([offline]). */
fun offlineAfter(offline: Boolean, event: ConnectionEvent): Boolean = when (event) {
    is ConnectionEvent.Answered, ConnectionEvent.NetworkUp -> false
    ConnectionEvent.Unanswered, ConnectionEvent.NetworkDown -> true
    ConnectionEvent.TimedOut -> offline
}
