package cc.hafa.inbox.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import cc.hafa.inbox.InboxApplication
import cc.hafa.inbox.core.mail.epochToIso
import cc.hafa.inbox.core.mail.isLocalIso
import cc.hafa.inbox.core.notify.LastCheck
import cc.hafa.inbox.core.notify.SnoozeEnd
import cc.hafa.inbox.core.notify.checkSnoozes
import cc.hafa.inbox.core.notify.snoozeEndedPayload
import cc.hafa.inbox.core.notify.todoIdOf
import cc.hafa.inbox.core.notify.todoSnoozeEnd
import cc.hafa.inbox.core.notify.todoSnoozeEndedContent
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId

private const val TAG = "SnoozeAlarm"
private const val PREFS = "snooze-alarm"
// When the last check ran, epoch milliseconds, and the zone it ran in: snoozes ending after it
// are notified at the next.
private const val CHECKED_KEY = "checkedAt"
private const val CHECKED_ZONE_KEY = "checkedZone"
// When the alarm is set to go off, epoch milliseconds; a failed check retries no later.
private const val NEXT_KEY = "nextAt"
private const val CHECK_ACTION = "cc.hafa.inbox.SNOOZE_CHECK"
// How long a check waits for the snoozes (Firestore answers from its cache when offline).
private const val READ_WAIT_MS = 15_000L
// When a check can't read the snoozes, it runs again this much later at most.
private const val RETRY_MS = 15 * 60_000L
// Snoozes that ended longer ago than this before a check aren't read, nor notified.
private const val LAPSED_READ_MS = 24 * 60 * 60_000L
// How many times a check records its wait at most, when a snooze changed between its reads.
private const val SETTLE_ROUNDS = 3
private val CHECK_ACTIONS = setOf(
    CHECK_ACTION,
    Intent.ACTION_BOOT_COMPLETED,
    Intent.ACTION_MY_PACKAGE_REPLACED,
    Intent.ACTION_TIMEZONE_CHANGED,
    Intent.ACTION_TIME_CHANGED,
)

/** The snoozes a reminder check waits on: threads' and, while todos show, todos'. */
data class ReminderSnoozes(
    /** each snooze's end */
    val ends: List<SnoozeEnd>,
    /** each todo's text, by its item id */
    val todoTexts: Map<String, String>,
)

/**
 * Notifies on the phone when a snooze ends (a thread's or a todo's), app closed or not. A [check]
 * reads the account's pending snoozes (from the open app's listeners while they are current, else
 * from Firestore), notifies what ended since the last check, sets one inexact alarm for the next end, and
 * records what it waits on in `users/{uid}/meta/snoozeWaits` ([Devices.setSnoozeWait]), reading
 * again after the record so none written meanwhile is missed, so the server pings the phone only
 * for a change that moves the alarm. Checks run when the alarm goes off,
 * on that ping, at boot and on clock changes, and whenever the open app sees the snoozes change.
 */
object SnoozeAlarm {
    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun alarmIntent(context: Context): PendingIntent {
        val intent = Intent(context, SnoozeAlarmReceiver::class.java).setAction(CHECK_ACTION)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun schedule(context: Context, at: Long?) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        if (at == null) {
            alarms.cancel(alarmIntent(context))
            prefs(context).edit { remove(NEXT_KEY) }
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(context))
            prefs(context).edit { putLong(NEXT_KEY, at) }
        }
    }

    // A check that couldn't finish runs again in RETRY_MS, or at the alarm already set if sooner.
    private fun scheduleRetry(context: Context, now: Long) {
        val next = prefs(context).getLong(NEXT_KEY, Long.MAX_VALUE)
        schedule(context, minOf(next, now + RETRY_MS))
    }

    private fun lastCheck(context: Context): LastCheck? {
        val prefs = prefs(context)
        val at = prefs.getLong(CHECKED_KEY, -1L)
        val zone = prefs.getString(CHECKED_ZONE_KEY, null)?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()
        return if (at >= 0) LastCheck(at, zone) else null
    }

    // The threads and, while the Show todos setting is on, the live todos not done, whose snooze
    // ends no earlier than LAPSED_READ_MS before now: from the open session's listeners while they
    // are all current, else read from Firestore. Null when they can't be read (offline past the wait,
    // signed out meanwhile, refused).
    private suspend fun pendingSnoozes(app: InboxApplication, uid: String, now: Long, zone: ZoneId): ReminderSnoozes? {
        val live = withContext(Dispatchers.Main) { app.session.value?.takeIf { it.uid == uid }?.reminderSnoozes(now - LAPSED_READ_MS, zone) }
        return live ?: readSnoozes(uid, now, zone)
    }

    private suspend fun readSnoozes(uid: String, now: Long, zone: ZoneId): ReminderSnoozes? =
        try {
            withTimeoutOrNull(READ_WAIT_MS) {
                val from = epochToIso(now - LAPSED_READ_MS, zone)
                val user = FirebaseFirestore.getInstance().collection("users").document(uid)
                val threads = user.collection("threads").whereGreaterThanOrEqualTo("snoozeUntil", from).get().await().documents.mapNotNull { entry ->
                    entry.getString("snoozeUntil")?.takeIf(::isLocalIso)?.let { SnoozeEnd(entry.id, it) }
                }
                val showTodos = (user.get().await().get("settings") as? Map<*, *>)?.get("showTodos") != false
                val todos = if (showTodos) {
                    user.collection("todos")
                        .whereEqualTo("deleted", false)
                        .whereEqualTo("state", "ACTIVE")
                        .whereGreaterThanOrEqualTo("snoozeUntil", from)
                        .get().await().documents
                } else {
                    emptyList()
                }
                // A todo on an email has no snooze of its own, so no reminder.
                val todoEnds = todos.mapNotNull { entry -> todoSnoozeEnd(entry.id, entry.data.orEmpty())?.let { it to entry.getString("text").orEmpty() } }
                ReminderSnoozes(threads + todoEnds.map { it.first }, todoEnds.associate { (end, text) -> end.itemId to text })
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "snooze read failed", error)
            null
        }

    private suspend fun onScreen(): Boolean = withContext(Dispatchers.Main) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    /**
     * Read the pending snoozes of threads and todos, notify each that ended since the last check
     * (nothing while the app is on screen, where the inbox shows it back, nor for a thread newer
     * mail already woke), and wait on the next. Does nothing while signed out; when the snoozes
     * can't be read, tries again in [RETRY_MS] at most.
     */
    suspend fun check(context: Context) = lock.withLock {
        val app = context.applicationContext as? InboxApplication ?: return@withLock
        val uid = app.authManager?.currentUser?.value?.uid ?: return@withLock
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val pending = pendingSnoozes(app, uid, now, zone)
        if (pending == null) {
            scheduleRetry(context, now)
        } else {
            val result = checkSnoozes(pending.ends, lastCheck(context), now, zone)
            prefs(context).edit {
                putLong(CHECKED_KEY, now)
                putString(CHECKED_ZONE_KEY, zone.id)
            }
            if (result.ended.isNotEmpty() && !onScreen()) {
                val session = app.session.value
                val snoozeAt = if (result.ended.any { todoIdOf(it.itemId) == null }) Notifier.snoozeTime(context) else null
                for (snooze in result.ended) {
                    if (todoIdOf(snooze.itemId) != null) {
                        Notifier.showTodoSnoozeEnded(context, todoSnoozeEndedContent(snooze, pending.todoTexts[snooze.itemId].orEmpty(), zone))
                    } else if (session?.wokenByMail(snooze.itemId) != true) {
                        Notifier.showSnoozeEnded(context, snoozeEndedPayload(snooze, session?.summary(snooze.itemId), zone), snoozeAt)
                    }
                }
            }
            schedule(context, result.next?.endsAt(zone))
            val devices = app.devices
            if (devices != null && devices.hasSavedToken(uid)) recordWait(context, devices, uid, result.next, zone, SETTLE_ROUNDS)
        }
    }

    // Records the snooze waited on, then reads again: the server skips pinging for a snooze written
    // before the record's time, so one written after the first read but before the record must be
    // seen here. That read goes to Firestore, as another device's write may not have reached this
    // one's listeners yet. Repeats, up to `rounds` records, while what to wait on keeps changing.
    private suspend fun recordWait(context: Context, devices: Devices, uid: String, next: SnoozeEnd?, zone: ZoneId, rounds: Int) {
        if (!devices.setSnoozeWait(uid, next)) {
            scheduleRetry(context, System.currentTimeMillis())
        } else {
            val now = System.currentTimeMillis()
            val again = readSnoozes(uid, now, zone)
            val latest = again?.let { checkSnoozes(it.ends, null, now, zone).next }
            if (again == null) {
                scheduleRetry(context, now)
            } else if (latest != next) {
                schedule(context, latest?.endsAt(zone))
                if (rounds > 1) recordWait(context, devices, uid, latest, zone, rounds - 1)
            }
        }
    }

    /** Run a [check] in the background, calling [done] after it. */
    fun checkSoon(context: Context, done: () -> Unit = {}) {
        scope.launch {
            try {
                check(context)
            } finally {
                done()
            }
        }
    }

    /** Forget the last check and cancel the alarm, as on sign-out. */
    fun clear(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        context.deleteSharedPreferences(PREFS)
    }
}

/**
 * Goes off at a snooze's end, and on boot, app update and clock or time zone change (an alarm
 * doesn't survive a reboot, and the ends are wall-clock times): runs a [SnoozeAlarm.check].
 */
class SnoozeAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in CHECK_ACTIONS) return
        val pending = goAsync()
        SnoozeAlarm.checkSoon(context) { pending.finish() }
    }
}
