package cc.hafa.inbox.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import cc.hafa.inbox.ui.theme.paper

/** The project's issue tracker, where feedback goes. */
const val FEEDBACK_URL = "https://github.com/hafacc/inbox/issues"

/** The project's source code. */
const val SOURCE_URL = "https://github.com/hafacc/inbox"

/** A page about Inbox: its label, icon and web address, and the copy bundled with the app, if any. */
private data class ProjectLink(val label: String, val icon: ImageVector, val url: String, val page: InfoPage? = null)

private val projectLinks = listOf(
    ProjectLink("Privacy", Icons.Outlined.PrivacyTip, InfoPage.PRIVACY.url, InfoPage.PRIVACY),
    ProjectLink("Support", Icons.AutoMirrored.Outlined.HelpOutline, InfoPage.SUPPORT.url, InfoPage.SUPPORT),
    ProjectLink("Feedback", Icons.Outlined.ChatBubbleOutline, FEEDBACK_URL),
    ProjectLink("Source code", Icons.Outlined.Code, SOURCE_URL),
)

/**
 * Quiet Privacy, Support, Feedback and Source code links, wrapping onto more lines when narrow.
 * Privacy and Support open the app's own copy ([InfoSheet]); the others open the web.
 */
@Composable
fun ProjectLinks(
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    showIcons: Boolean = true,
    onNoApp: ((String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val noApp = onNoApp ?: { message -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
    var reading by rememberSaveable { mutableStateOf<InfoPage?>(null) }
    reading?.let { page -> InfoSheet(page, onPage = { reading = it }, onNoApp = noApp) { reading = null } }
    FlowRow(horizontalArrangement = Arrangement.Center, modifier = modifier) {
        for (link in projectLinks) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button) {
                        if (link.page != null) reading = link.page else openWebPage(context, link.url, noApp)
                    }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 12.dp),
            ) {
                if (showIcons) {
                    Icon(link.icon, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(16.dp))
                }
                Text(link.label, style = textStyle, color = paper.textSecondary)
            }
        }
    }
}
