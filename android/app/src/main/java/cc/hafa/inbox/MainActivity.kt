package cc.hafa.inbox

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.webkit.WebSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.inbox.data.ThemeMode
import cc.hafa.inbox.mail.composeRequestOf
import cc.hafa.inbox.mail.launcherShortcutOf
import cc.hafa.inbox.ui.HoldMenuHost
import cc.hafa.inbox.ui.InboxApp
import cc.hafa.inbox.ui.theme.InboxTheme

/**
 * The one activity: sign-in, then the mail screens. Notifications open threads through
 * [cc.hafa.inbox.notify.NotificationOpenActivity]; this one takes other apps' requests for a new
 * message (`mailto:` links and shares), which open the composer filled in, and the launcher's
 * shortcuts (New email, New todo, Search), which wait for sign-in when signed out.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as InboxApplication
        // A recreated activity (a rotation, say) already took its intent's request.
        if (savedInstanceState == null) takeRequests(intent)
        setContent {
            val themeId by app.theme.themeId.collectAsStateWithLifecycle()
            val mode by app.theme.mode.collectAsStateWithLifecycle()
            val dark = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons follow the app's light or dark, not only the phone's.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            InboxTheme(darkTheme = dark, themeId = themeId) {
                HoldMenuHost { InboxApp(authManager = app.authManager, session = app.session, launcher = app.launcher) }
            }
        }
        // The first message view would otherwise load the WebView engine as its thread opens.
        Looper.myQueue().addIdleHandler {
            runCatching { WebSettings.getDefaultUserAgent(this) }
            false
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeRequests(intent)
    }

    // An intent from another app can carry anything; one that can't be read asks for nothing.
    private fun takeRequests(intent: Intent) {
        val app = application as InboxApplication
        launcherShortcutOf(intent)?.let(app.launcher::ask)
        val request = runCatching { composeRequestOf(intent, packageName) }.getOrNull() ?: return
        app.composeFromIntent(request)
    }
}
