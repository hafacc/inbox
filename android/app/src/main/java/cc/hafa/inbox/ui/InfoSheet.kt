package cc.hafa.inbox.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import cc.hafa.inbox.ui.theme.paper

/**
 * A page bundled with the app (privacy or support) in a sheet, so reading it doesn't leave the app,
 * with a button by its title that opens its web copy. Its links open as they do on the web, except
 * that one to the other bundled page switches the sheet to it ([onPage]); [onNoApp] says when no app
 * opens a link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(page: InfoPage, onPage: (InfoPage) -> Unit, onNoApp: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember(page) {
        parseInfoPage(context.assets.open(page.asset).bufferedReader().use { it.readText() })
    }
    val title = blocks.firstOrNull { it is InfoBlock.Heading && it.level == 1 } as InfoBlock.Heading?
    val linkStyles = TextLinkStyles(SpanStyle(color = paper.accent))
    val open = { url: String ->
        val bundled = infoPageAt(url)
        when {
            bundled != null -> onPage(bundled)
            url.startsWith("http") -> openWebPage(context, url, onNoApp)
            else -> try {
                context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
            } catch (_: ActivityNotFoundException) {
                onNoApp("No app opens this link")
            }
        }
    }
    fun text(spans: List<InfoSpan>): AnnotatedString = buildAnnotatedString {
        for (span in spans) {
            val weight = if (span.bold) SpanStyle(fontWeight = FontWeight.SemiBold) else SpanStyle()
            withStyle(weight) {
                if (span.href == null) {
                    append(span.text)
                } else {
                    withLink(LinkAnnotation.Clickable(span.href, linkStyles) { open(span.href) }) { append(span.text) }
                }
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = paper.card,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp)) {
            Text(
                title?.spans?.joinToString("") { it.text }.orEmpty(),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = paper.text,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { openWebPage(context, page.url, onNoApp) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open on the web", tint = paper.textSecondary)
            }
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(remember(page) { ScrollState(0) })
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 32.dp),
        ) {
            for (block in blocks) {
                when (block) {
                    is InfoBlock.Heading -> if (block !== title) {
                        Text(
                            text(block.spans),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = paper.text,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    is InfoBlock.Paragraph -> Text(text(block.spans), style = MaterialTheme.typography.bodyMedium, color = paper.textSecondary)
                    is InfoBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (item in block.items) {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("•", style = MaterialTheme.typography.bodyMedium, color = paper.textSecondary)
                                Text(text(item), style = MaterialTheme.typography.bodyMedium, color = paper.textSecondary)
                            }
                        }
                    }
                }
            }
        }
    }
}
