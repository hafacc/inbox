package cc.hafa.inbox.notify

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import cc.hafa.inbox.core.notify.SnoozeEnd
import cc.hafa.inbox.core.runSuspendCatching
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "Devices"
private const val PREFS = "devices"
// FCM's latest token for this phone, from PushService.onRegistered.
private const val TOKEN_KEY = "token"
// A sign-out's cleanup not finished yet: the account, its saved token, and whether FCM let go of it.
private const val PENDING_UID_KEY = "pendingUid"
private const val PENDING_TOKEN_KEY = "pendingToken"
private const val PENDING_UNREGISTERED_KEY = "pendingUnregistered"
private const val DELETE_WAIT_MS = 5_000L
// How long a snooze wait write waits for the server to take it.
private const val SNOOZE_WAIT_MS = 10_000L

/**
 * This phone's FCM token in `users/{uid}/devices/{token}`, so the server can notify it. [firestore]
 * gives the current instance, which sign-out replaces when it clears Firestore's offline cache.
 */
class Devices(context: Context, private val firestore: () -> FirebaseFirestore) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun savedKey(uid: String) = "saved:$uid"

    private fun device(uid: String, token: String) =
        firestore().collection("users").document(uid).collection("devices").document(token)

    private fun waits(uid: String) = firestore().collection("users").document(uid).collection("meta").document("snoozeWaits")

    // Writes only this phone's entry of the waits map, whole, leaving other devices' alone.
    private fun waitEntry(token: String, value: Any): Pair<Map<String, Any>, SetOptions> =
        mapOf("waits" to mapOf(token to value)) to SetOptions.mergeFieldPaths(listOf(FieldPath.of("waits", token)))

    /**
     * Delete [token]'s device doc and its snooze wait, as separate writes so neither failing holds
     * back the other; returns whether the device doc went within [DELETE_WAIT_MS].
     */
    private suspend fun removeDevice(uid: String, token: String): Boolean = coroutineScope {
        val wait = async {
            val (data, options) = waitEntry(token, FieldValue.delete())
            cleanupWrite("snooze wait delete") { waits(uid).set(data, options).await() }
        }
        val deleted = cleanupWrite("device delete") { device(uid, token).delete().await() }
        wait.await()
        deleted
    }

    // One cleanup write, waiting at most DELETE_WAIT_MS for the server (offline it never answers); false when it didn't finish.
    private suspend fun cleanupWrite(what: String, write: suspend () -> Unit): Boolean = try {
        withTimeoutOrNull(DELETE_WAIT_MS) {
            write()
            true
        } ?: false
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Log.w(TAG, "$what failed", error)
        false
    }

    /**
     * Ask FCM for a token and save the one already known; a new one arrives through [tokenIssued].
     * A sign-out's cleanup cut short is finished first, so it can't give up the new token.
     */
    suspend fun register(uid: String) {
        finishForget(uid)
        FirebaseMessaging.getInstance().register().await()
        prefs.getString(TOKEN_KEY, null)?.let { save(uid, it) }
    }

    /** Remember a token FCM issued and save it for [uid] when signed in. */
    suspend fun tokenIssued(uid: String?, token: String) {
        prefs.edit { putString(TOKEN_KEY, token) }
        if (uid != null) save(uid, token)
    }

    private suspend fun save(uid: String, token: String) {
        val previous = prefs.getString(savedKey(uid), null)
        if (previous != null && previous != token) removeDevice(uid, previous)
        device(uid, token).set(mapOf("platform" to "android", "lastSeen" to FieldValue.serverTimestamp())).await()
        prefs.edit { putString(savedKey(uid), token) }
    }

    /** Whether this phone's push token is saved for [uid], so it can record a snooze wait. */
    fun hasSavedToken(uid: String): Boolean = prefs.getString(savedKey(uid), null) != null

    /**
     * Record under this phone's token, in `users/{uid}/meta/snoozeWaits`, the snooze it waits on
     * next ([wait], null when none), stamped with the server's time, so the server pings it only for
     * snooze changes that move its alarm. Returns whether the server took it within [SNOOZE_WAIT_MS]
     * (false before the token is saved, offline, or on a failed write).
     */
    suspend fun setSnoozeWait(uid: String, wait: SnoozeEnd?): Boolean {
        val token = prefs.getString(savedKey(uid), null) ?: return false
        val entry = mutableMapOf<String, Any>("updatedAt" to FieldValue.serverTimestamp())
        if (wait != null) {
            entry["itemId"] = wait.itemId
            entry["snoozeUntil"] = wait.until
        }
        val (data, options) = waitEntry(token, entry)
        return try {
            withTimeoutOrNull(SNOOZE_WAIT_MS) {
                waits(uid).set(data, options).await()
                true
            } ?: false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "snooze wait write failed", error)
            false
        }
    }

    /**
     * Remove this phone from the account and give up its token, as on sign-out. What doesn't
     * finish (offline, or cut off by the sign-out) is retried by [finishForget] on the next start.
     */
    suspend fun forget(uid: String) {
        val saved = prefs.getString(savedKey(uid), null)
        prefs.edit {
            clear()
            putString(PENDING_UID_KEY, uid)
            putString(PENDING_TOKEN_KEY, saved)
        }
        finishForget(uid)
    }

    /**
     * Finish a sign-out's cleanup: give up the token with FCM, then delete the account's device doc
     * when [signedInUid] is that account (the rules allow it only then). Signed out or in to
     * another account, the doc is left for the server, which drops it once FCM refuses the token.
     * Throws while FCM can't be reached, keeping it for the next try; a device doc delete that fails
     * is logged and kept for the next try too.
     */
    suspend fun finishForget(signedInUid: String?) {
        val uid = prefs.getString(PENDING_UID_KEY, null) ?: return
        if (!prefs.getBoolean(PENDING_UNREGISTERED_KEY, false)) {
            FirebaseMessaging.getInstance().unregister().await()
            prefs.edit { putBoolean(PENDING_UNREGISTERED_KEY, true) }
        }
        val token = prefs.getString(PENDING_TOKEN_KEY, null)
        // Offline, the delete never hears back from the server: kept for the next try.
        if (token != null && signedInUid == uid && !removeDevice(uid, token)) return
        if (token == null || signedInUid != null) {
            prefs.edit {
                remove(PENDING_UID_KEY)
                remove(PENDING_TOKEN_KEY)
                remove(PENDING_UNREGISTERED_KEY)
            }
        }
    }

    /** Give up this phone's token and forget every saved one, leaving the server alone (the account is gone). */
    suspend fun release() {
        prefs.edit { clear() }
        runSuspendCatching { FirebaseMessaging.getInstance().unregister().await() }
    }
}
