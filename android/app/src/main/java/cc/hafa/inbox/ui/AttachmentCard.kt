package cc.hafa.inbox.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.graphics.createBitmap
import cc.hafa.inbox.core.mail.Attachment
import cc.hafa.inbox.core.mail.decodeBase64Url
import cc.hafa.inbox.core.mail.OPENS_ONLINE
import cc.hafa.inbox.core.mail.formatSize
import cc.hafa.inbox.mail.MailState
import cc.hafa.inbox.mail.isOffline
import cc.hafa.inbox.ui.theme.paper
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val PREVIEW_PX = 720
private const val PDF_TYPE = "application/pdf"

// Larger files aren't fetched just to show a preview.
private const val MAX_PREVIEW_BYTES = 10L * 1024 * 1024
private const val PREVIEW_CACHE_SIZE = 24

// First pages of PDFs already drawn, by message and attachment position.
private val pdfPreviews = LruCache<String, ImageBitmap>(PREVIEW_CACHE_SIZE)

/** Whether an attachment gets a preview: an image or a PDF, small enough to fetch for it. */
private fun previewable(attachment: Attachment): Boolean =
    (attachment.mimeType.startsWith("image/") || attachment.mimeType == PDF_TYPE) && attachment.size <= MAX_PREVIEW_BYTES

/**
 * An attachment of an open message: images and PDFs show a preview (fetched once the card is on
 * screen), then the name and size. A tap opens it in the default app for its type; a long press
 * offers Save and Share. Offline, a fetch that fails leaves the name and size and says it opens
 * once back online; it loads as usual then.
 */
@Composable
fun AttachmentCard(state: MailState, messageId: String, index: Int, attachment: Attachment) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    // A fetch failed for want of a network; cleared once the app is back online, which retries a preview.
    var waitingOnline by remember { mutableStateOf(false) }
    // A preview that couldn't be drawn for any other reason; the card shows without one.
    var previewFailed by remember { mutableStateOf(false) }
    val offline by state.offline.collectAsStateWithLifecycle()
    LaunchedEffect(offline) {
        if (!offline) waitingOnline = false
    }
    val onOffline = { waitingOnline = true }
    val name = attachment.filename.ifEmpty { "attachment" }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(attachment.mimeType.ifEmpty { "application/octet-stream" })) { uri ->
        if (uri != null) {
            scope.launch {
                runWithNotice(state, "Couldn't save $name", onOffline) {
                    val bytes = attachmentBytes(state, messageId, attachment)
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: error("no file")
                    }
                    state.showNotice("Saved $name")
                }
            }
        }
    }

    fun withFile(action: (Uri) -> Unit) {
        loading = true
        scope.launch {
            runWithNotice(state, "Couldn't open $name", onOffline) {
                action(attachmentUri(context, state, messageId, index, attachment))
            }
            loading = false
        }
    }

    val preview = previewable(attachment) && !waitingOnline && !previewFailed
    val shape = if (preview) RoundedCornerShape(16.dp) else CircleShape
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, paper.border, shape)
                .combinedClickable(
                    enabled = !loading,
                    onClickLabel = "Open",
                    onLongClickLabel = "Save or share",
                    onLongClick = { menu = true },
                ) {
                    withFile { uri ->
                        val intent = Intent(Intent.ACTION_VIEW)
                            .setDataAndType(uri, attachment.mimeType.ifEmpty { "application/octet-stream" })
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        try {
                            context.startActivity(intent)
                        } catch (_: ActivityNotFoundException) {
                            state.showNotice("No app opens $name")
                        }
                    }
                },
        ) {
            if (preview) {
                AttachmentPreview(state, messageId, index, attachment, onOffline = onOffline, onFailed = { previewFailed = true })
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().height(if (waitingOnline) 56.dp else 48.dp).padding(horizontal = 16.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(color = paper.accent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(18.dp))
                }
                if (waitingOnline) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, color = paper.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${formatSize(attachment.size)} · $OPENS_ONLINE",
                            style = MaterialTheme.typography.bodySmall,
                            color = paper.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                } else {
                    Text(name, color = paper.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(formatSize(attachment.size), style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = paper.raised) {
            DropdownMenuItem(
                text = { Text("Save", color = paper.text) },
                leadingIcon = { Icon(Icons.Outlined.Download, contentDescription = null, tint = paper.textSecondary) },
                onClick = {
                    menu = false
                    save.launch(name)
                },
            )
            DropdownMenuItem(
                text = { Text("Share", color = paper.text) },
                leadingIcon = { Icon(Icons.Outlined.Share, contentDescription = null, tint = paper.textSecondary) },
                onClick = {
                    menu = false
                    withFile { uri ->
                        val intent = Intent(Intent.ACTION_SEND)
                            .setType(attachment.mimeType.ifEmpty { "application/octet-stream" })
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(intent, null))
                    }
                },
            )
        }
    }
}

/**
 * The preview strip at the top of an image or PDF attachment; [onOffline] when its fetch failed for
 * want of a network, [onFailed] when it can't be drawn otherwise.
 */
@Composable
private fun AttachmentPreview(
    state: MailState,
    messageId: String,
    index: Int,
    attachment: Attachment,
    onOffline: () -> Unit,
    onFailed: () -> Unit,
) {
    val context = LocalContext.current
    val key = "$messageId/$index"
    var image by remember(key) { mutableStateOf<ImageRequest?>(null) }
    var page by remember(key) { mutableStateOf(pdfPreviews.get(key)) }
    LaunchedEffect(key) {
        try {
            if (attachment.mimeType == PDF_TYPE) {
                if (page == null) {
                    page = pdfFirstPage(attachmentFile(context, state, messageId, index, attachment))?.also { pdfPreviews.put(key, it) }
                    if (page == null) onFailed()
                }
            } else {
                val bytes = attachmentBytes(state, messageId, attachment)
                image = ImageRequest.Builder(context).data(bytes).size(PREVIEW_PX).memoryCacheKey("attachment:$key").build()
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            if (isOffline(error)) onOffline() else onFailed()
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(140.dp).background(paper.muted)) {
        val shownPage = page
        val shownImage = image
        if (shownPage != null) {
            Image(shownPage, contentDescription = null, contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, modifier = Modifier.fillMaxWidth().height(140.dp))
        } else if (shownImage != null) {
            AsyncImage(model = shownImage, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(140.dp))
        }
    }
}

// Runs [action]; a failure for want of a network goes to [onOffline] rather than a notice.
private suspend fun runWithNotice(state: MailState, failure: String, onOffline: () -> Unit, action: suspend () -> Unit) {
    try {
        action()
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        if (isOffline(error)) onOffline() else state.showNotice("$failure: ${error.message ?: "error"}")
    }
}

private suspend fun attachmentBytes(state: MailState, messageId: String, attachment: Attachment): ByteArray =
    attachment.data?.let { withContext(Dispatchers.Default) { decodeBase64Url(it) } }
        ?: attachment.attachmentId?.let { state.attachmentBytes(messageId, attachment.partId, it) }
        ?: error("no data")

// Kept under the cache by message and position: Gmail's attachment ids change between fetches.
private suspend fun attachmentFile(context: Context, state: MailState, messageId: String, index: Int, attachment: Attachment): File {
    val name = attachment.filename.replace(Regex("[/\\\\]"), "_").ifEmpty { "attachment" }
    val file = File(context.cacheDir, "attachments/$messageId/$index/$name")
    if (withContext(Dispatchers.IO) { file.length() > 0 }) return file
    val bytes = attachmentBytes(state, messageId, attachment)
    // Written aside and moved in whole, so a preview and an Open at once never read half a file.
    return withContext(Dispatchers.IO) {
        val folder = file.parentFile ?: context.cacheDir
        folder.mkdirs()
        val partial = File.createTempFile("download", ".part", folder)
        partial.writeBytes(bytes)
        if (!partial.renameTo(file)) partial.delete()
        file
    }
}

private suspend fun attachmentUri(context: Context, state: MailState, messageId: String, index: Int, attachment: Attachment): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.files", attachmentFile(context, state, messageId, index, attachment))

// A PDF's first page on white, as wide as the preview; null for one that can't be read (a password, say).
private suspend fun pdfFirstPage(file: File): ImageBitmap? = withContext(Dispatchers.IO) {
    runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(0).use { page ->
                    val height = (PREVIEW_PX.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                    val bitmap = createBitmap(PREVIEW_PX, height)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap.asImageBitmap()
                }
            }
        }
    }.getOrNull()
}
