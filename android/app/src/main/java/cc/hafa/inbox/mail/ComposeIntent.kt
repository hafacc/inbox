package cc.hafa.inbox.mail

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import cc.hafa.inbox.core.compose.ComposePrefill
import cc.hafa.inbox.core.compose.mergePrefill
import cc.hafa.inbox.core.compose.parseMailtoPrefill
import cc.hafa.inbox.core.compose.prefillAddresses

/** A new message another app asked for: what it starts with, and the files to attach. */
data class ComposeRequest(
    /** recipients, subject and body */
    val prefill: ComposePrefill,
    /** files shared to attach, as content URIs */
    val files: List<Uri> = emptyList(),
)

private const val MAILTO_SCHEME = "mailto"

/**
 * The new message [intent] asks for, or null when it asks for none: a `mailto:` link opened or sent
 * to (`ACTION_VIEW`, `ACTION_SENDTO`), or a share (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`) with its
 * addresses, subject, text and files. Shared text is read as markdown, and shared HTML
 * (`EXTRA_HTML_TEXT`) in its place keeps its formatting. A share's own `mailto:` data, when it has one, is read too.
 * Only files another app hands over are attached, never one of [ownPackage]'s own. An intent
 * delivered again because the app was opened from Recents asks for nothing: it was taken already.
 */
fun composeRequestOf(intent: Intent, ownPackage: String): ComposeRequest? {
    if ((intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return null
    val shared = { uri: Uri -> uri.scheme == "content" && uri.authority?.startsWith(ownPackage) != true }
    val data = intent.data
    val link = data?.takeIf { it.scheme.equals(MAILTO_SCHEME, ignoreCase = true) }?.let { parseMailtoPrefill(it.toString()) }
    return when (intent.action) {
        Intent.ACTION_VIEW, Intent.ACTION_SENDTO -> link?.let { ComposeRequest(mergePrefill(it, sharedFields(intent))) }
        Intent.ACTION_SEND -> ComposeRequest(
            mergePrefill(link, sharedFields(intent)),
            listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)).filter(shared),
        )
        Intent.ACTION_SEND_MULTIPLE -> ComposeRequest(
            mergePrefill(link, sharedFields(intent)),
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty().filter(shared),
        )
        else -> null
    }
}

// Addresses are meant to come as a string array; some apps send a string list, or one string.
private fun addressExtra(intent: Intent, name: String): List<String> =
    intent.getStringArrayExtra(name)?.toList()
        ?: intent.getStringArrayListExtra(name)?.filterNotNull()
        ?: listOfNotNull(intent.getStringExtra(name))

// The fields a share or a mail app's intent carries in its extras.
private fun sharedFields(intent: Intent): ComposePrefill = ComposePrefill(
    to = prefillAddresses(addressExtra(intent, Intent.EXTRA_EMAIL)),
    cc = prefillAddresses(addressExtra(intent, Intent.EXTRA_CC)),
    bcc = prefillAddresses(addressExtra(intent, Intent.EXTRA_BCC)),
    subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty(),
    body = sharedHtml(intent) ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty(),
)

// A share's rich text, when it has some, kept styled; HTML that can't be read leaves the plain text.
private fun sharedHtml(intent: Intent): String? =
    intent.getStringExtra(Intent.EXTRA_HTML_TEXT)
        ?.let { html -> runCatching { htmlToMarkdown(html) }.getOrNull() }
        ?.takeIf { it.isNotBlank() }

/** A file's name and type as its content provider gives them, with a plain fallback for each. */
fun describeFile(context: Context, uri: Uri): Pair<String, String> {
    val resolver = context.contentResolver
    // A provider that refuses the question still leaves the file to be read, or not, when it is added.
    val name = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: "attachment"
    val type = runCatching { resolver.getType(uri) }.getOrNull() ?: "application/octet-stream"
    return name to type
}
