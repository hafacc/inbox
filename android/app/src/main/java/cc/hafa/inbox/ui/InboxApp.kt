package cc.hafa.inbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.inbox.auth.AuthManager
import cc.hafa.inbox.mail.LauncherRequests
import cc.hafa.inbox.mail.MailState
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

// How long starting up may take before the loading indicator shows, so a fast start never flashes it.
private const val STARTING_DELAY_MS = 400L

/**
 * Sign-in while signed out, a loading indicator while a signed-in account starts, then the mail
 * screens, which take the [launcher] shortcut waiting.
 */
@Composable
fun InboxApp(authManager: AuthManager?, session: StateFlow<MailState?>, launcher: LauncherRequests? = null) {
    val state by session.collectAsStateWithLifecycle()
    val current = state
    val user = authManager?.currentUser?.collectAsStateWithLifecycle()?.value
    if (authManager != null && current != null) {
        MailScreen(state = current, authManager = authManager, launcher = launcher)
    } else if (authManager != null && user != null) {
        Starting()
    } else {
        SignInScreen(authManager)
    }
}

// The app's background with a spinner that shows only once starting up takes a moment.
@Composable
private fun Starting() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(STARTING_DELAY_MS)
        shown = true
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize().background(paper.page)) {
        if (shown) CircularProgressIndicator(color = paper.accent, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
    }
}
