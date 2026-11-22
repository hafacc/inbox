package cc.hafa.inbox.ui

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.inbox.auth.AuthManager
import cc.hafa.inbox.ui.theme.paper
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "SignIn"

/** Signed out: the app mark, one line about it, and Google sign-in. */
@Composable
fun SignInScreen(authManager: AuthManager?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    // Whether the pending Google prompt forced the consent screen.
    var consentAsked by remember { mutableStateOf(false) }
    val errorFlow = authManager?.error ?: remember { MutableStateFlow<String?>(null) }
    val error by errorFlow.collectAsStateWithLifecycle()

    fun fail(cause: Throwable?) {
        if (cause != null) Log.w(TAG, "sign-in failed", cause)
        errorFlow.value = "Sign-in didn't finish. Try again."
        busy = false
    }

    // Set once below: finish, the launcher and request call each other in a cycle.
    @Suppress("CAN_BE_VAL_LATEINIT")
    lateinit var request: (consent: Boolean) -> Unit

    suspend fun finish(result: AuthorizationResult) {
        val code = result.serverAuthCode
        if (code == null) {
            fail(null)
        } else {
            try {
                val linked = authManager?.completeSignIn(code) != false
                when {
                    linked -> busy = false
                    // Google only hands out a refresh token on the consent screen.
                    !consentAsked -> request(true)
                    else -> fail(null)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (cause: Exception) {
                fail(cause)
            }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { outcome ->
        if (outcome.resultCode != Activity.RESULT_OK) {
            busy = false
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val result = runCatching {
                Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(outcome.data)
            }.getOrElse {
                fail(it)
                return@launch
            }
            finish(result)
        }
    }

    request = { consent ->
        val auth = authManager
        if (auth != null) {
            consentAsked = consent
            busy = true
            scope.launch {
                try {
                    val result = Identity.getAuthorizationClient(context).authorize(auth.authorizationRequest(consent)).await()
                    val pending = result.pendingIntent
                    if (result.hasResolution() && pending != null) {
                        launcher.launch(IntentSenderRequest.Builder(pending.intentSender).build())
                    } else {
                        finish(result)
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (cause: Exception) {
                    fail(cause)
                }
            }
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(paper.page)
            .safeDrawingPadding()
            .padding(24.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .background(paper.accent, CircleShape),
            ) {
                Icon(Icons.Outlined.Inbox, contentDescription = null, tint = paper.onAccent, modifier = Modifier.size(30.dp))
            }
            Text("Inbox", style = MaterialTheme.typography.titleLarge, color = paper.text)
            Text(
                "Your Gmail, sorted the Inbox way: pin what matters, snooze the rest, mark it done.",
                style = MaterialTheme.typography.bodyMedium,
                color = paper.textSecondary,
                textAlign = TextAlign.Center,
            )
            if (authManager == null) {
                Text(
                    "This build has no Firebase project yet. Fill in android/app/src/main/java/cc/hafa/inbox/FirebaseConfig.kt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = paper.danger,
                    textAlign = TextAlign.Center,
                )
            } else {
                Button(
                    onClick = { request(!authManager.linkedBefore) },
                    enabled = !busy,
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = paper.accent, contentColor = paper.onAccent),
                    modifier = Modifier.height(44.dp),
                ) {
                    Text(if (busy) "Signing in…" else "Sign in with Google")
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = paper.danger, textAlign = TextAlign.Center)
                }
            }
            ProjectLinks(textStyle = MaterialTheme.typography.bodySmall, showIcons = false)
        }
    }
}
