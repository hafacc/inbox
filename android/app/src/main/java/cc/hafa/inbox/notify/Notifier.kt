package cc.hafa.inbox.notify

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import cc.hafa.inbox.InboxApplication
import cc.hafa.inbox.MainActivity
import cc.hafa.inbox.R
import cc.hafa.inbox.core.notify.NotificationContent
import cc.hafa.inbox.core.notify.PushPayload
import cc.hafa.inbox.core.notify.notificationContent
import cc.hafa.inbox.core.notify.notificationSnoozeTime
import cc.hafa.inbox.core.notify.notificationTag
import cc.hafa.inbox.core.notify.snoozeActionLabel
import cc.hafa.inbox.core.send.SendSuggestions
import cc.hafa.inbox.core.snooze.SnoozeStatsSnapshot
import cc.hafa.inbox.core.snooze.SnoozeStatsWire
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.ZoneId

private const val CHANNEL_ID = "mail"
// Snooze ends, of threads and todos: the phone's own reminders, apart from new mail.
private const val REMINDER_CHANNEL_ID = "reminders"
// Every notification uses this id; its tag tells threads apart.
private const val NOTIFICATION_ID = 1
// Each group's summary uses this id, tagged with the group.
private const val SUMMARY_ID = 2
private const val MAIL_GROUP = "cc.hafa.inbox.MAIL"
private const val REMINDER_GROUP = "cc.hafa.inbox.REMINDERS"
private const val THREAD_TAG_PREFIX = "thread:"
private const val TAG = "Notifier"
// How long a notification waits for the account's learned snooze picks.
private const val STATS_WAIT_MS = 3_000L

/** Builds and posts mail notifications and snooze reminders, each on its own channel and in its own group. */
object Notifier {
    /** Intent extra naming the thread to open. */
    const val EXTRA_THREAD_ID = "threadId"

    /** Intent extra asking for the reply box. */
    const val EXTRA_REPLY = "reply"

    /** Intent extra with text the reply box starts with. */
    const val EXTRA_REPLY_TEXT = "replyText"

    /** Intent extra naming the message a notification is about. */
    const val EXTRA_MESSAGE_ID = "messageId"

    /** Intent extra with the time Snooze snoozes to, epoch milliseconds. */
    const val EXTRA_SNOOZE_AT = "snoozeAt"

    /** Intent extra naming the outbox doc of a send to take back. */
    const val EXTRA_UNDO_OUTBOX = "undoOutboxId"

    /** Intent extra naming the draft (on this device) of a send to take back. */
    const val EXTRA_UNDO_LOCAL = "undoLocalId"

    /** The inline reply's text in a Reply action's results. */
    const val KEY_REPLY_TEXT = "replyText"

    /** Whether the user lets the app post notifications. */
    fun allowed(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "New mail", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(
            NotificationChannel(REMINDER_CHANNEL_ID, "Snooze reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "When a snoozed thread or todo comes back"
            },
        )
    }

    private fun openIntent(context: Context, threadId: String, reply: Boolean, text: String = ""): PendingIntent {
        val intent = Intent(context, NotificationOpenActivity::class.java)
            .setAction(if (reply) "reply:$threadId" else "open:$threadId")
            .putExtra(EXTRA_THREAD_ID, threadId)
            .putExtra(EXTRA_REPLY, reply)
            .putExtra(EXTRA_REPLY_TEXT, text)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun base(context: Context, content: NotificationContent, open: PendingIntent, channel: String = CHANNEL_ID) =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .apply { content.timestamp?.let { setWhen(it).setShowWhen(true) } }

    /**
     * The time a notification's Snooze picks now: the first time the snooze sheet shows, from the
     * account's learned picks (read from Firestore when the app hasn't loaded them yet).
     */
    suspend fun snoozeTime(context: Context): Long {
        val app = context.applicationContext as? InboxApplication
        val loaded = app?.session?.value?.snoozeStats?.stats?.value?.takeIf { it.sets.isNotEmpty() }
        val stats = loaded ?: app?.authManager?.currentUser?.value?.uid?.let { uid -> storedStats(uid) } ?: SnoozeStatsSnapshot()
        return notificationSnoozeTime(stats, Instant.now(), ZoneId.systemDefault())
    }

    private suspend fun storedStats(uid: String): SnoozeStatsSnapshot? =
        try {
            withTimeoutOrNull(STATS_WAIT_MS) {
                val document = FirebaseFirestore.getInstance().collection("users").document(uid).collection("meta").document("snoozeStats")
                SnoozeStatsWire.sharedFromWire(document.get().await().data)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "snooze picks read failed", error)
            null
        }

    /** Post the notification for a push, with Done, Snooze (until [snoozeAt], when there is one) and Reply. */
    fun show(context: Context, payload: PushPayload, snoozeAt: Long?) =
        showThread(context, payload.threadId, payload.messageId, notificationContent(payload), CHANNEL_ID, MAIL_GROUP, snoozeAt)

    /**
     * Post the phone's own notification for a snooze that ended, with Done, Snooze and Reply. Done
     * goes through the app's queue, as a push's does, so it needs no token from the server.
     */
    fun showSnoozeEnded(context: Context, payload: PushPayload, snoozeAt: Long?) =
        showThread(
            context,
            payload.threadId,
            payload.messageId,
            notificationContent(payload).copy(canDone = true),
            REMINDER_CHANNEL_ID,
            REMINDER_GROUP,
            snoozeAt,
        )

    private fun showThread(
        context: Context,
        threadId: String,
        messageId: String,
        content: NotificationContent,
        channel: String,
        group: String,
        snoozeAt: Long?,
    ) {
        if (!allowed(context)) return
        ensureChannel(context)
        val builder = base(context, content, openIntent(context, threadId, reply = false), channel)
        if (content.canDone) {
            builder.addAction(0, "Done", NotificationActions.donePendingIntent(context, threadId))
        }
        snoozeAt?.let { at ->
            builder.addAction(0, snoozeActionLabel(at, Instant.now(), ZoneId.systemDefault()), NotificationActions.snoozePendingIntent(context, threadId, at))
        }
        val replyInput = RemoteInput.Builder(KEY_REPLY_TEXT).setLabel("Reply").build()
        builder.addAction(
            NotificationCompat.Action.Builder(0, "Reply", NotificationActions.replyPendingIntent(context, threadId, messageId))
                .addRemoteInput(replyInput)
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build(),
        )
        post(context, content.tag, builder, group)
    }

    /** Replace a notification with one saying Done didn't go through; tapping it opens the thread. */
    fun showDoneFailed(context: Context, threadId: String, tag: String) = showFailed(context, threadId, tag, "Couldn't mark as done")

    /** Replace a notification with one saying Snooze didn't go through; tapping it opens the thread. */
    fun showSnoozeFailed(context: Context, threadId: String, tag: String) = showFailed(context, threadId, tag, "Couldn't snooze")

    private fun showFailed(context: Context, threadId: String, tag: String, title: String) {
        if (!allowed(context)) return
        ensureChannel(context)
        val content = NotificationContent(title, "Tap to open the thread.", tag, null, canDone = false)
        post(context, tag, base(context, content, openIntent(context, threadId, reply = false)).setSilent(true), MAIL_GROUP)
    }

    /** Show a thread's reply on its way while it is saved, in place of its notification, quietly. */
    fun showReplySaving(context: Context, threadId: String, text: String) {
        if (!allowed(context)) return
        ensureChannel(context)
        val content = NotificationContent("Sending your reply…", text, notificationTag(threadId), null, canDone = false)
        post(context, content.tag, base(context, content, openIntent(context, threadId, reply = false)).setSilent(true), null)
    }

    /**
     * Show a reply going out, with Undo until it goes; Undo takes the send back and opens the draft
     * in the app. It goes by itself when the undo time is up.
     */
    fun showReplySending(context: Context, threadId: String, text: String, outboxId: String, localId: String) {
        if (!allowed(context)) return
        ensureChannel(context)
        val tag = notificationTag(threadId)
        val content = NotificationContent("Sending your reply…", text, tag, null, canDone = false)
        val undo = Intent(context, NotificationOpenActivity::class.java)
            .setAction("undo:$outboxId")
            .putExtra(EXTRA_THREAD_ID, threadId)
            .putExtra(EXTRA_UNDO_OUTBOX, outboxId)
            .putExtra(EXTRA_UNDO_LOCAL, localId)
        val undoIntent = PendingIntent.getActivity(context, 0, undo, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = base(context, content, openIntent(context, threadId, reply = false))
            .setSilent(true)
            .setTimeoutAfter(SendSuggestions.UNDO_SEND_DELAY_MS)
            .addAction(0, "Undo", undoIntent)
        post(context, tag, builder, null)
    }

    /** Show that a reply from a notification couldn't go; Open starts a reply in the app with [text]. */
    fun showReplyFailed(context: Context, threadId: String, text: String) {
        if (!allowed(context)) return
        ensureChannel(context)
        val tag = notificationTag(threadId)
        val content = NotificationContent("Couldn't send your reply", text, tag, null, canDone = false)
        val open = openIntent(context, threadId, reply = true, text = text)
        post(context, tag, base(context, content, open).setSilent(true).addAction(0, "Open", open), MAIL_GROUP)
    }

    /**
     * Post the phone's own notification for a todo whose snooze ended, with Done; a tap opens the
     * Todos view at it. Its tag is the todo's item id, which also travels as the thread extra.
     */
    fun showTodoSnoozeEnded(context: Context, content: NotificationContent) {
        if (!allowed(context)) return
        ensureChannel(context)
        val builder = base(context, content, openIntent(context, content.tag, reply = false), REMINDER_CHANNEL_ID)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "Done", NotificationActions.donePendingIntent(context, content.tag))
        post(context, content.tag, builder, REMINDER_GROUP)
    }

    /** Replace a todo's reminder with one saying Done didn't go through; tapping it opens the todo. */
    fun showTodoDoneFailed(context: Context, tag: String) {
        if (!allowed(context)) return
        ensureChannel(context)
        val content = NotificationContent("Couldn't mark as done", "Tap to open the todo.", tag, null, canDone = false)
        val builder = base(context, content, openIntent(context, tag, reply = false), REMINDER_CHANNEL_ID).setSilent(true)
        post(context, tag, builder, REMINDER_GROUP)
    }

    /** Remove a notification by its tag. */
    fun cancel(context: Context, tag: String) {
        NotificationManagerCompat.from(context).cancel(tag, NOTIFICATION_ID)
        refreshSummaries(context, removed = setOf(tag))
    }

    /** Remove the notifications about these threads, new mail and reminders alike. */
    fun cancelThreads(context: Context, threadIds: Collection<String>) {
        val tags = threadIds.map(::notificationTag).toSet()
        val shown = shownTags(context)
        val present = tags.filter { it in shown }
        val manager = NotificationManagerCompat.from(context)
        for (tag in present) manager.cancel(tag, NOTIFICATION_ID)
        // Also when none was shown: one tapped open went by itself, and its group's summary may be all that's left.
        refreshSummaries(context, removed = present.toSet())
    }

    /**
     * Remove the new-mail notifications [stale] says no longer apply, given each one's thread id and
     * when its mail arrived (epoch ms). Reminders and notes about actions are left alone.
     */
    fun clearStale(context: Context, stale: (threadId: String, arrivedAt: Long) -> Boolean) {
        val gone = active(context).filter { entry ->
            val notification = entry.notification
            val tag = entry.tag.orEmpty()
            notification.channelId == CHANNEL_ID && notification.group == MAIL_GROUP && (notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 &&
                tag.startsWith(THREAD_TAG_PREFIX) && stale(tag.removePrefix(THREAD_TAG_PREFIX), notification.`when`)
        }.mapNotNull { it.tag }
        if (gone.isEmpty()) return
        val manager = NotificationManagerCompat.from(context)
        for (tag in gone) manager.cancel(tag, NOTIFICATION_ID)
        refreshSummaries(context, removed = gone.toSet())
    }

    private fun active(context: Context) =
        context.getSystemService(NotificationManager::class.java).activeNotifications.filter { it.id == NOTIFICATION_ID }

    private fun shownTags(context: Context): Set<String> = active(context).mapNotNull { it.tag }.toSet()

    private fun post(context: Context, tag: String, builder: NotificationCompat.Builder, group: String?) {
        if (group != null) builder.setGroup(group)
        try {
            NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, builder.build())
        } catch (_: SecurityException) {
            // Permission was withdrawn between the check and the post.
            return
        }
        refreshSummaries(context, posted = if (group == null) emptyMap() else mapOf(tag to group), removed = if (group == null) setOf(tag) else emptySet())
    }

    // Posts each group's summary while it has notifications, and takes it away once it has none. The
    // system's list can lag a post or a cancel just made, so [posted] and [removed] say what changed.
    private fun refreshSummaries(context: Context, posted: Map<String, String> = emptyMap(), removed: Set<String> = emptySet()) {
        val members = active(context)
            .filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 && it.tag !in removed && it.tag !in posted }
            .mapNotNull { entry -> entry.notification.group?.let { group -> entry.tag to group } }
            .toMap() + posted
        for ((group, groupChannel) in listOf(MAIL_GROUP to CHANNEL_ID, REMINDER_GROUP to REMINDER_CHANNEL_ID)) {
            val count = members.values.count { it == group }
            if (count == 0) {
                NotificationManagerCompat.from(context).cancel(group, SUMMARY_ID)
            } else {
                postSummary(context, group, groupChannel, count)
            }
        }
    }

    private fun postSummary(context: Context, group: String, channel: String, count: Int) {
        val text = when {
            group == REMINDER_GROUP -> if (count == 1) "1 reminder" else "$count reminders"
            else -> if (count == 1) "1 new message" else "$count new messages"
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction("summary:$group"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setCategory(if (group == REMINDER_GROUP) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_EMAIL)
            .setGroup(group)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setSilent(true)
            .setAutoCancel(true)
            .setContentIntent(open)
        try {
            NotificationManagerCompat.from(context).notify(group, SUMMARY_ID, builder.build())
        } catch (_: SecurityException) {
            // Permission was withdrawn meanwhile.
        }
    }
}
