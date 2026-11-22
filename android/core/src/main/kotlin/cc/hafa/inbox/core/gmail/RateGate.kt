package cc.hafa.inbox.core.gmail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlin.random.Random

/** A pause [RateGate] started after a usage limit, as a debug log reports it. */
data class Backoff(
    /** the HTTP status Google answered with */
    val status: Int,
    /** Google's reason, when it gave one */
    val reason: String?,
    /** how many limits in a row this is, 1 for the first */
    val strike: Int,
    /** how long calls now wait, milliseconds */
    val pauseMs: Long,
    /** the urgency of the call that hit the limit */
    val priority: CallPriority,
)

/**
 * Paces every Gmail call made with one token: at most [MAX_CONCURRENT] run at once, the most urgent
 * waiting call goes next ([nextCall], which also yields and paces the saved copy's reads), and after Google says to slow down every call waits out a
 * shared pause ([backoffMs]: `Retry-After` when Google gives one, else 1 s doubling to 32 s, with
 * random jitter), the saved copy's reads longer ([savingHeldUntil]). [onBackoff] hears of each pause.
 */
class RateGate(
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val jitter: () -> Double = { Random.nextDouble() },
    private val onBackoff: (Backoff) -> Unit = {},
) {
    private class Ticket(val id: String, val order: Long, val priority: () -> CallPriority) {
        val granted = CompletableDeferred<Unit>()

        // Whether it started as a saved-copy read, counted against [MAX_SAVING] while it runs.
        var saving = false
    }

    private val until = MutableStateFlow(0L)
    private var strikes = 0
    private var running = 0
    private var runningSaving = 0
    private var savingStartedAt = 0L
    private var savingHeld = 0L
    private var asked = 0L
    private val waiting = mutableListOf<Ticket>()

    // Completed when a pause starts or a call ends, so calls waiting for a slot look again: to wait
    // out the pause, or a clock (the saved copy's spacing or hold) that a call ending left them on.
    private var paused = CompletableDeferred<Unit>()

    /** When calls may go again, epoch milliseconds; 0 when they may go now. */
    val pausedUntil: StateFlow<Long> = until.asStateFlow()

    /** Run [call] once a slot is free for it; [priority] is read each time a slot frees, so it may rise while waiting. */
    suspend fun <Result> withSlot(priority: () -> CallPriority, call: suspend () -> Result): Result {
        val ticket = synchronized(this) {
            asked++
            Ticket(asked.toString(), asked, priority).also {
                waiting += it
                start()
            }
        }
        try {
            awaitTurn(ticket)
        } catch (error: CancellationException) {
            synchronized(this) {
                // Granted just as it was cancelled: hand the slot on.
                if (!waiting.remove(ticket)) finish(ticket)
            }
            throw error
        }
        try {
            return call()
        } finally {
            synchronized(this) { finish(ticket) }
        }
    }

    private suspend fun awaitTurn(ticket: Ticket) {
        while (!ticket.granted.isCompleted) {
            val (wakeAt, pause) = synchronized(this) { start() to paused }
            if (ticket.granted.isCompleted) return
            val left = (wakeAt ?: 0L) - now()
            // A clock wait still ends early when the call is granted or its priority rises ([poke]).
            coroutineScope {
                val timer = if (left > 0) async { sleep(left) } else null
                select {
                    ticket.granted.onAwait {}
                    pause.onAwait {}
                    timer?.onAwait {}
                }
                timer?.cancel()
            }
        }
    }

    /** Look at the waiting calls again, after one's priority rose: it may go now, or wait on a shorter clock. */
    fun poke() {
        synchronized(this) {
            start()
            paused.complete(Unit)
            paused = CompletableDeferred()
        }
    }

    // Callers hold the lock. Starts every call whose turn it is; returns when to look again, if a
    // clock (a pause or the saved copy's spacing) holds one back.
    private fun start(): Long? {
        while (true) {
            val at = now()
            val calls = waiting.map { WaitingCall(it.id, it.priority(), it.order) }
            val turn = nextCall(calls, PaceState(running, runningSaving, until.value, savingStartedAt, savingHeld), at)
            val id = turn.id ?: return turn.wakeAt
            val ticket = waiting.first { it.id == id }
            waiting.remove(ticket)
            running++
            if (calls.first { it.id == id }.priority == CallPriority.SAVING) {
                ticket.saving = true
                runningSaving++
                savingStartedAt = at
            }
            ticket.granted.complete(Unit)
        }
    }

    private fun finish(ticket: Ticket) {
        running--
        if (ticket.saving) runningSaving--
        if (start() != null) {
            paused.complete(Unit)
            paused = CompletableDeferred()
        }
    }

    /**
     * Start the pause after a usage limit ([status], [reason]) hit by a [priority] call; returns when
     * it ends. A limit met while a pause already runs counts as the same strike and leaves it as it is.
     */
    fun slowDown(status: Int, reason: String?, retryAfterSeconds: Long?, priority: CallPriority): Long {
        val backoff = synchronized(this) {
            if (until.value > now()) return until.value
            strikes++
            val pause = backoffMs(strikes, retryAfterSeconds, jitter())
            until.value = now() + pause
            savingHeld = savingHeldUntil(until.value)
            paused.complete(Unit)
            paused = CompletableDeferred()
            Backoff(status, reason, strikes, pause, priority)
        }
        onBackoff(backoff)
        return until.value
    }

    /** Note a call that went through: the next limit starts the backoff over, and the pause reads as ended. */
    fun passed() {
        synchronized(this) {
            strikes = 0
            if (until.value != 0L && until.value <= now()) until.value = 0L
        }
    }
}
