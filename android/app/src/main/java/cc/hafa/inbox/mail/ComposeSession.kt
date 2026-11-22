package cc.hafa.inbox.mail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.TextRange
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import cc.hafa.inbox.core.compose.ComposeKind
import cc.hafa.inbox.core.compose.DraftContent
import cc.hafa.inbox.core.compose.DraftFile
import cc.hafa.inbox.core.mail.Person

/** Which recipient field. */
enum class RecipientField {
    /** To */
    TO,

    /** Cc */
    CC,

    /** Bcc */
    BCC,
}

/** A composer field that can hold the cursor. */
enum class ComposeField {
    /** To */
    TO,

    /** Cc */
    CC,

    /** Bcc */
    BCC,

    /** Subject */
    SUBJECT,

    /** the message */
    BODY,
}

/**
 * One open composer: what's typed, as Compose state, around the draft it saves to. The fields a
 * reply fixes (thread, threading headers, signature) don't change while it's open.
 */
class ComposeSession(
    initial: DraftContent,
    /** how the composer started */
    val kind: ComposeKind,
) {
    /** the draft's id on this device */
    val localId: String = initial.localId

    private val fixed = initial

    /** sender */
    var from by mutableStateOf(initial.from)

    /** `To` */
    val to = mutableStateListOf<Person>().apply { addAll(initial.to) }

    /** `Cc` */
    val cc = mutableStateListOf<Person>().apply { addAll(initial.cc) }

    /** `Bcc` */
    val bcc = mutableStateListOf<Person>().apply { addAll(initial.bcc) }

    /** whether the Cc and Bcc rows show */
    var showCopies by mutableStateOf(initial.cc.isNotEmpty() || initial.bcc.isNotEmpty())

    /** subject */
    var subject by mutableStateOf(initial.subject)

    /** body, rich text held as markdown; the caret starts at its top, above a forward's quote */
    val body = RichBody(initial.markdown)

    /** attachments and inline images */
    val files = mutableStateListOf<DraftFile>().apply { addAll(initial.files) }

    /** files still being fetched (a forward's attachments, a reopened draft's files) */
    var fetching by mutableIntStateOf(0)

    /** Gmail draft id, once known */
    var draftId by mutableStateOf(initial.draftId)

    /** the outbox doc that schedules this draft, while one does */
    var outboxId by mutableStateOf(initial.outboxId)

    /** the message a reply quotes below the signature; null once removed */
    var quote by mutableStateOf(initial.quote)

    /** the user chose "Send without encryption"; lasts while this composer is open, never saved with the draft */
    var sendUnencrypted by mutableStateOf(false)

    /** files fetched in rather than added by the user (a forward's attachments, a reopened draft's files) */
    val fetched = mutableSetOf<String>()

    /** whether the user changed anything since it opened */
    var edited by mutableStateOf(false)

    /** what is typed in each address field and not yet a recipient; kept here so a turn of the phone keeps it */
    val typedAddresses = mutableStateMapOf<RecipientField, String>()

    /** the field that last held the cursor, which gets it back when the composer is drawn again; null until one has */
    var cursorField by mutableStateOf<ComposeField?>(null)

    /** the subject's caret and selection, kept for the composer drawn again (parked, or a turn of the phone) */
    var subjectSelection by mutableStateOf(TextRange(initial.subject.length))

    /** whether it opened from the saved copy, which may be older than Gmail's draft */
    var fromSavedCopy = false

    /** the outbox doc's send time when reopened from the outbox */
    var scheduledAt by mutableStateOf<Long?>(null)

    /** the Gmail thread of a reply */
    val threadId: String? get() = fixed.threadId

    /** the signature added below the body */
    val signature: String? get() = fixed.signature

    /** One recipient field's list. */
    fun recipients(field: RecipientField) = when (field) {
        RecipientField.TO -> to
        RecipientField.CC -> cc
        RecipientField.BCC -> bcc
    }

    /** What's typed now, as a draft. */
    fun content(): DraftContent = content(body.markdown, body.html)

    /** What's typed now, as a draft, its body's markdown and HTML worked out off the main thread. */
    suspend fun renderedContent(): DraftContent {
        val (markdown, html) = body.rendered()
        return content(markdown, html)
    }

    /** Everything a draft holds as it changes: the fields, and the body as the editor's styled text. */
    fun changes(): Pair<DraftContent, AnnotatedString> = content("", "") to body.state.annotatedString

    private fun content(markdown: String, html: String): DraftContent = fixed.copy(
        draftId = draftId,
        from = from,
        to = to.toList(),
        cc = cc.toList(),
        bcc = bcc.toList(),
        subject = subject,
        markdown = markdown,
        html = html,
        files = files.toList(),
        outboxId = outboxId,
        quote = quote,
    )

    /** What the user can change, to tell whether they did: files arriving from a fetch don't count, removing one does. */
    fun typed(): Pair<DraftContent, Set<String>> {
        val ids = files.map { it.id }.toSet()
        return content().copy(draftId = null, outboxId = null, files = files.filter { it.id !in fetched }) to (fetched - ids)
    }
}
