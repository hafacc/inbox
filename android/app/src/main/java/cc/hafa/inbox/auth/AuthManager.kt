package cc.hafa.inbox.auth

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import cc.hafa.inbox.FirebaseConfig
import cc.hafa.inbox.core.gmail.AccessToken
import cc.hafa.inbox.core.gmail.TokenCache
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.common.api.Scope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** Gmail access is gone (revoked, or the stored grant lost); the user has to sign in again. */
class ReauthNeeded : Exception("Gmail access ended")

// Every scope the app asks for once, covering web, Android and the server; the same list as web.
private val GOOGLE_SCOPES = listOf(
    "openid",
    "email",
    "profile",
    "https://www.googleapis.com/auth/gmail.modify",
    "https://www.googleapis.com/auth/gmail.settings.basic",
    "https://www.googleapis.com/auth/contacts.readonly",
    "https://www.googleapis.com/auth/contacts.other.readonly",
    "https://www.googleapis.com/auth/calendar.events",
)

private const val PREFS = "auth"
private const val EMAIL_KEY = "email"
private const val LINKED_KEY = "linked"
private const val TAG = "AuthManager"

/** Whether a function call failed because Gmail access has to be granted again. */
internal fun isReauth(error: Throwable): Boolean =
    error is FirebaseFunctionsException &&
        error.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION &&
        error.message == "reauth"

/** A Firebase callable that got no answer from its server, as a network error. */
class UnreachableException(cause: Throwable) : IOException("Couldn't reach the server.", cause)

/**
 * Whether a Firebase callable failed without an answer from its server: the request itself failed
 * (the SDK reports that as `INTERNAL` caused by an [IOException]), it timed out, or the server was
 * unavailable.
 */
fun isUnreachableCall(error: Throwable): Boolean =
    error is FirebaseFunctionsException &&
        (
            error.code == FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ||
                error.code == FirebaseFunctionsException.Code.UNAVAILABLE ||
                (error.code == FirebaseFunctionsException.Code.INTERNAL && error.cause is IOException)
            )

/**
 * Sign-in and the Gmail token plumbing.
 *
 * Google's AuthorizationClient hands the app an offline server auth code for every scope the app
 * needs; the `signIn` function swaps it for a refresh token it keeps, and returns a Firebase custom
 * token. After that, `gmailToken` mints short-lived access tokens, cached in [tokens].
 */
class AuthManager(context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val auth = FirebaseAuth.getInstance()
    private val functions = FirebaseFunctions.getInstance(FirebaseConfig.FUNCTIONS_REGION)

    /** The Firebase user, or null when signed out. */
    val currentUser: StateFlow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }.stateIn(scope, SharingStarted.Eagerly, auth.currentUser)

    /** Why the last sign-in ended or failed, for the sign-in screen. */
    val error = MutableStateFlow<String?>(null)

    /** Gmail access tokens from the `gmailToken` function. */
    val tokens = TokenCache(::fetchToken)

    /** The linked Gmail address. */
    val email: String
        get() = auth.currentUser?.email ?: prefs.getString(EMAIL_KEY, null) ?: ""

    /** Whether this device linked Gmail before, so Google needn't show the consent screen again. */
    val linkedBefore: Boolean
        get() = prefs.getBoolean(LINKED_KEY, false)

    /** What to ask Google for; [consent] forces the consent screen, which is when Google hands out a refresh token. */
    fun authorizationRequest(consent: Boolean): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(GOOGLE_SCOPES.map(::Scope))
            .requestOfflineAccess(FirebaseConfig.WEB_CLIENT_ID)
            .apply { if (consent) setPrompt(AuthorizationRequest.Prompt.CONSENT) }
            .build()

    /**
     * Trade a server auth code for a Firebase session through `signIn`.
     *
     * Returns false when the server has no refresh token for the account, so the caller must ask
     * again with the consent screen.
     */
    suspend fun completeSignIn(serverAuthCode: String): Boolean {
        val result = try {
            functions.getHttpsCallable("signIn")
                .call(mapOf("code" to serverAuthCode, "redirectUri" to ""))
                .await()
        } catch (error: Exception) {
            if (isReauth(error)) return false
            throw error
        }
        val data = result.getData() as? Map<*, *> ?: error("signIn returned no data")
        val customToken = data["customToken"] as? String ?: error("signIn returned no token")
        val email = data["email"] as? String ?: ""
        prefs.edit {
            putString(EMAIL_KEY, email)
            putBoolean(LINKED_KEY, true)
        }
        error.value = null
        auth.signInWithCustomToken(customToken).await()
        return true
    }

    private suspend fun fetchToken(): AccessToken {
        try {
            val result = functions.getHttpsCallable("gmailToken").call().await()
            val data = result.getData() as? Map<*, *> ?: error("gmailToken returned no data")
            val accessToken = data["accessToken"] as? String ?: error("gmailToken returned no token")
            val expiresAt = (data["expiresAt"] as? Number)?.toLong() ?: 0L
            return AccessToken(accessToken, expiresAt)
        } catch (error: Exception) {
            if (isReauth(error)) {
                reauth()
                throw ReauthNeeded()
            } else if (isUnreachableCall(error)) {
                throw UnreachableException(error)
            } else {
                throw error
            }
        }
    }

    /** Runs while still signed in, before a sign-out, for cleanup Firestore rules only allow then. */
    var beforeSignOut: (suspend (uid: String) -> Unit)? = null

    /** Runs after the account is deleted on the server, for device cleanup that needs no server. */
    var afterAccountDeleted: (suspend () -> Unit)? = null

    // Whether the sign-out under way was forced by lost Gmail access, so unsent work is kept.
    @Volatile
    private var forced = false
    @Volatile
    private var signingOut = false

    /** Whether the sign-out that just happened was forced by lost Gmail access; reading it clears it. */
    fun takeForcedSignOut(): Boolean {
        val was = forced
        forced = false
        return was
    }

    /** Sign out of Firebase on the user's say; the caller clears the device's mail cache, unsent work included. */
    fun signOut() {
        forced = false
        signOutNow()
    }

    private fun signOutNow() {
        if (signingOut) return
        signingOut = true
        val uid = auth.currentUser?.uid
        val cleanup = beforeSignOut
        scope.launch {
            if (uid != null && cleanup != null) {
                try {
                    withTimeout(SIGN_OUT_CLEANUP_MS) { cleanup(uid) }
                } catch (error: Exception) {
                    Log.w(TAG, "sign-out cleanup failed", error)
                }
            }
            endSession()
            signingOut = false
        }
    }

    private fun endSession() {
        tokens.invalidate()
        // The "linked" flag goes too: the next sign-in shows Google's consent screen again.
        prefs.edit { clear() }
        auth.signOut()
    }

    /**
     * Delete the account on the server (`deleteAccount`), then sign out; the caller clears the
     * device's mail cache as on any sign-out. Throws when the server couldn't delete it.
     */
    suspend fun deleteAccount() {
        val result = functions.getHttpsCallable("deleteAccount").call().await()
        val data = result.getData() as? Map<*, *>
        if (data?.get("deleted") != true) error("deleteAccount didn't confirm")
        afterAccountDeleted?.let { cleanup ->
            try {
                cleanup()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "device cleanup after deletion failed", error)
            }
        }
        endSession()
    }

    // Gmail access was lost: sign out and ask for consent again next time. The caller clears the
    // device as on any sign-out but keeps the account's unsent work for its next sign-in.
    private fun reauth() {
        if (signingOut) return
        Log.w(TAG, "Gmail access ended; signing out")
        error.value = "Gmail access ended. Sign in again to reconnect."
        forced = true
        signOutNow()
    }

    private companion object {
        const val SIGN_OUT_CLEANUP_MS = 5_000L
    }
}
