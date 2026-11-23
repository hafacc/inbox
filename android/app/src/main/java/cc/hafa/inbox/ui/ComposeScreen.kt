package cc.hafa.inbox.ui

import androidx.activity.BackEventCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.coerceIn
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.inbox.core.compose.ComposeKind
import cc.hafa.inbox.core.compose.DraftFile
import cc.hafa.inbox.core.compose.MAX_MESSAGE_BYTES
import cc.hafa.inbox.core.compose.QuotedMessage
import cc.hafa.inbox.core.compose.SIGNATURE_SEPARATOR
import cc.hafa.inbox.core.compose.addressKey
import cc.hafa.inbox.core.compose.addressProblem
import cc.hafa.inbox.core.compose.encodedSize
import cc.hafa.inbox.core.compose.imageMarkdown
import cc.hafa.inbox.core.compose.isEmailAddress
import cc.hafa.inbox.core.compose.quoteText
import cc.hafa.inbox.core.compose.quotedContentIds
import cc.hafa.inbox.core.contacts.Contact
import cc.hafa.inbox.core.contacts.suggestContacts
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.formatSize
import cc.hafa.inbox.core.mail.parseAddress
import cc.hafa.inbox.core.pgp.EncryptionReason
import cc.hafa.inbox.core.pgp.encryptionDecision
import cc.hafa.inbox.core.send.OutboxDoc
import cc.hafa.inbox.core.send.OutboxStatus
import cc.hafa.inbox.core.send.SendSuggestions
import cc.hafa.inbox.core.send.inFlight
import cc.hafa.inbox.core.send.retryingDetail
import cc.hafa.inbox.core.send.retryingText
import cc.hafa.inbox.mail.ComposeField
import cc.hafa.inbox.mail.ComposeSession
import cc.hafa.inbox.mail.RecipientField
import cc.hafa.inbox.mail.SendNotice
import cc.hafa.inbox.mail.describeFile
import cc.hafa.inbox.mail.Sending
import cc.hafa.inbox.pgp.EncryptionStatus
import cc.hafa.inbox.ui.theme.paper
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val AUTOSAVE_DELAY_MS = 1_500L
private const val THUMBNAIL_PX = 256
private val ADDRESS_SEPARATORS = charArrayOf(',', ';', ' ', '\n')

// Held by an empty address field after its chips (a zero-width space), for backspace to delete.
internal const val ADDRESS_SENTINEL = "\u200B"

/** What an edit of an address field does. */
internal sealed interface AddressEdit {
    /** backspace in the empty field: take off the last recipient */
    data object RemoveLast : AddressEdit

    /** a separator typed after [text]: make it a recipient */
    data class Finish(
        /** the address typed, separator and all */
        val text: String,
    ) : AddressEdit

    /** ordinary typing, leaving [value] in the field */
    data class Type(
        /** the field's text and cursor, without the sentinel */
        val value: TextFieldValue,
    ) : AddressEdit
}

/** What [next] does to an address field that showed [shown] (the sentinel alone when empty after chips). */
internal fun addressEdit(shown: String, next: TextFieldValue): AddressEdit {
    val raw = next.text.replace(ADDRESS_SENTINEL, "")
    // Taking the sentinel off the front shifts the cursor and what the keyboard is composing.
    val shift = if (next.text.startsWith(ADDRESS_SENTINEL)) ADDRESS_SENTINEL.length else 0
    return when {
        shown == ADDRESS_SENTINEL && next.text.isEmpty() -> AddressEdit.RemoveLast
        raw.isNotEmpty() && raw.last() in ADDRESS_SEPARATORS -> AddressEdit.Finish(raw)
        else -> {
            val cursor = (next.selection.start - shift).coerceIn(0, raw.length)
            val composing = next.composition?.let { TextRange((it.start - shift).coerceIn(0, raw.length), (it.end - shift).coerceIn(0, raw.length)) }
            AddressEdit.Type(TextFieldValue(raw, TextRange(cursor), composing))
        }
    }
}



// How far a back gesture draws the composer down, and how long it takes to go back up: as long as the composer's
// slide out, so a close that the gesture let go of ends where it would have.
private val BACK_REACH = 64.dp
private const val BACK_SETTLE_MS = 220

/** The composer following a back gesture: down by up to [reach] pixels of [drop], and back up once let go or called off. */
private class ComposerBackDrag(
    private val scope: CoroutineScope,
    private val drop: Animatable<Float, AnimationVector1D>,
    private val reach: Float,
) : BackFollower {
    override suspend fun follow(event: BackEventCompat) = drop.snapTo(reach * event.progress)

    override fun released() {
        scope.launch { drop.animateTo(0f, tween(BACK_SETTLE_MS, easing = OpenEasing)) }
    }

    override fun canceled() {
        scope.launch { drop.animateTo(0f, tween(BACK_SETTLE_MS, easing = OpenEasing)) }
    }
}

/** An address typed into a recipient field, if it is one. */
private fun typedPerson(text: String): Person? {
    val trimmed = text.trim().trim(*ADDRESS_SEPARATORS)
    return if (trimmed.isEmpty()) {
        null
    } else {
        parseAddress(trimmed).takeIf { isEmailAddress(it.email) }
    }
}

/**
 * The full-screen composer: a plain top bar (close, minimize, attach, Send with schedule), From,
 * To/Cc/Bcc with contact suggestions, subject, the markdown body with its files, and a light
 * formatting row above the keyboard. What's typed autosaves as a Gmail draft. A failed send's View
 * closes it and calls [onViewOutbox]. Minimize, or dragging the top bar down (moving the composer
 * by [drop]), closes the keyboard and calls [onPark].
 */
@Composable
fun ComposeScreen(
    session: ComposeSession,
    sending: Sending,
    onViewOutbox: () -> Unit,
    drop: Animatable<Float, AnimationVector1D>,
    onPark: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val senders by sending.senders.collectAsStateWithLifecycle()
    val contacts by sending.contacts.contacts.collectAsStateWithLifecycle()
    val sendStats by sending.sendStats.stats.collectAsStateWithLifecycle()
    val settings by sending.account.settings.collectAsStateWithLifecycle()
    val outboxDocs by sending.outbox.docs.collectAsStateWithLifecycle()
    val knownKeys by sending.encryption.directory.known.collectAsStateWithLifecycle()
    val encryptionStatus by sending.encryption.status.collectAsStateWithLifecycle()
    val hasKey = settings.pgp != null
    val recipients by remember(session) { derivedStateOf { session.to + session.cc + session.bcc } }
    val recipientEmails = remember(recipients) { recipients.map { addressKey(it.email) } }
    LaunchedEffect(hasKey, recipientEmails) {
        if (hasKey) sending.encryption.directory.request(recipientEmails)
    }
    val keys = if (hasKey) knownKeys else emptyMap()
    val decision = remember(session.from.email, recipientEmails, keys) { encryptionDecision(session.from.email, recipientEmails, keys) }
    val encryptable = hasKey && decision.encrypt
    val deviceLocked = encryptionStatus != EncryptionStatus.UNLOCKED
    val encrypted = encryptable && !(deviceLocked && session.sendUnencrypted)
    // Looked up and found to have no key; still-pending lookups don't count yet.
    val noKey = remember(hasKey, decision, recipients, keys) {
        if (hasKey && decision.reason == EncryptionReason.MISSING_KEYS) {
            recipients.distinctBy { addressKey(it.email) }
                .filter { addressKey(it.email) in keys && keys[addressKey(it.email)] == null }
        } else {
            emptyList()
        }
    }
    // What waits for an unlock: a send, a schedule or saving an edited scheduled send; null for the composer's own Unlock.
    var unlockThen by remember { mutableStateOf<(() -> Unit)?>(null) }
    var unlockLabel by remember { mutableStateOf("Unlock") }
    var unlocking by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var notSent by remember(session) { mutableStateOf(false) }

    // Encrypted mail never goes unsigned or quietly in the clear: once every lookup is in, a locked phone asks first.
    fun whenReady(label: String, action: () -> Unit) {
        if (checking) return
        checking = true
        scope.launch {
            try {
                if (sending.needsUnlock(session)) {
                    unlockThen = action
                    unlockLabel = label
                    unlocking = true
                } else {
                    action()
                }
            } finally {
                checking = false
            }
        }
    }
    // While its send is going out or being retried, the draft is shown read-only (docs/compose-contract.md).
    val lockedBy = outboxDocs.firstOrNull { it.id == session.outboxId }?.takeIf { it.inFlight }
    val locked = lockedBy != null
    val typed = session.typedAddresses
    var scheduling by rememberSaveable { mutableStateOf(false) }

    // Closes the composer, then runs [then]; an edited scheduled send asks to unlock first, and nothing happens if that's called off.
    fun requestClose(then: () -> Unit = {}) {
        val close = {
            sending.close(session)
            then()
        }
        if (!locked && sending.editsSchedule(session)) whenReady("Unlock and save", close) else close()
    }

    fun sendNow() {
        sending.send(session, System.currentTimeMillis() + SendSuggestions.UNDO_SEND_DELAY_MS, null, undoable = true)
    }

    LaunchedEffect(session) {
        snapshotFlow { session.changes() }.drop(1).collectLatest {
            delay(AUTOSAVE_DELAY_MS)
            sending.autosave(session, session.renderedContent())
        }
    }
    LaunchedEffect(session) {
        val start = session.typed()
        snapshotFlow { session.typed() }.first { it != start }
        session.edited = true
    }

    fun commitTyped(): Boolean {
        var ok = true
        for (field in RecipientField.entries) {
            val text = typed[field].orEmpty()
            if (text.isBlank()) continue
            val person = typedPerson(text)
            if (person == null) {
                ok = false
            } else {
                session.recipients(field) += person
                typed[field] = ""
            }
        }
        return ok
    }

    fun ready(): Boolean {
        val message = when {
            locked -> "This message is on its way. Cancel the send to change it."
            !commitTyped() -> "Check the addresses: one isn't valid."
            session.to.isEmpty() && session.cc.isEmpty() && session.bcc.isEmpty() -> "Add someone to send it to."
            session.fetching > 0 -> "Wait for the attachments to finish loading."
            session.files.sumOf { encodedSize(it.size) } > MAX_MESSAGE_BYTES -> "Attachments are over Gmail's 35 MB limit."
            else -> addressProblem(listOf(session.from) + session.to + session.cc + session.bcc)
        }
        if (message != null) sending.showNotice(message)
        return message == null
    }

    val attach = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        for (uri in uris) {
            scope.launch {
                val (name, type) = withContext(Dispatchers.IO) { describeFile(context, uri) }
                sending.addFile(session, name, type, null) { context.contentResolver.openInputStream(uri) }
            }
        }
    }
    val insertImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val (name, type) = withContext(Dispatchers.IO) { describeFile(context, uri) }
            val contentId = sending.newContentId()
            if (sending.addFile(session, name, type, contentId) { context.contentResolver.openInputStream(uri) } != null) {
                session.body.insertLine(imageMarkdown(name, contentId))
            }
        }
    }

    val focusers = remember { ComposeField.entries.associateWith { FocusRequester() } }
    val keyboard = LocalSoftwareKeyboardController.current
    // A new message starts in To; a reply, a forward or a draft with recipients starts in the body.
    // Drawn again (a turn of the phone), the cursor goes back to the field that had it.
    LaunchedEffect(session) {
        if (!locked) {
            val first = if (session.kind == ComposeKind.NEW && session.to.isEmpty()) ComposeField.TO else ComposeField.BODY
            val field = session.cursorField?.takeIf { session.showCopies || (it != ComposeField.CC && it != ComposeField.BCC) } ?: first
            focusers.getValue(field).requestFocus()
            keyboard?.show()
        }
    }
    val notice by sending.notice.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    fun park() {
        keyboard?.hide()
        focusManager.clearFocus()
        onPark()
    }
    val parkDrag = rememberParkDrag(drop, ::park)

    // A back gesture draws the composer down a little; let go, it goes the rest of the way as it closes.
    val backReach = with(LocalDensity.current) { BACK_REACH.toPx() }
    val backDrag = remember(drop, backReach) { ComposerBackDrag(scope, drop, backReach) }
    CompositionLocalProvider(LocalBackFollower provides backDrag) { ScreenBackHandler { requestClose() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(paper.card)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .parkDrag(parkDrag)
                    .padding(start = 4.dp, end = 12.dp),
            ) {
                IconButton(onClick = { requestClose() }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close", tint = paper.text)
                }
                IconButton(onClick = ::park) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = "Minimize", tint = paper.text)
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { attach.launch(arrayOf("*/*")) }, enabled = !locked) {
                    Icon(Icons.Outlined.AttachFile, contentDescription = "Attach file", tint = if (locked) paper.faint else paper.text)
                }
                SendButton(
                    onSend = { if (ready()) whenReady("Unlock and send", ::sendNow) },
                    onSchedule = { if (ready()) whenReady("Unlock and schedule") { scheduling = true } },
                    enabled = !locked && !checking,
                    encrypted = encrypted,
                )
            }
            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (lockedBy != null) {
                    LockedNote(lockedBy) { sending.cancelScheduled(lockedBy) }
                } else if (session.scheduledAt != null) {
                    Text(
                        "Scheduled. Send again to change the time; closing keeps it as scheduled.",
                        style = MaterialTheme.typography.bodySmall,
                        color = paper.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                FromRow(session, senders, enabled = !locked)
                RecipientRow(session, RecipientField.TO, "To", typed, contacts, keys, focusers.getValue(ComposeField.TO), enabled = !locked) {
                    if (!session.showCopies && !locked) {
                        TextButton(onClick = { session.showCopies = true }) {
                            Text("Cc Bcc", color = paper.textSecondary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                if (session.showCopies) {
                    RecipientRow(session, RecipientField.CC, "Cc", typed, contacts, keys, focusers.getValue(ComposeField.CC), enabled = !locked)
                    RecipientRow(session, RecipientField.BCC, "Bcc", typed, contacts, keys, focusers.getValue(ComposeField.BCC), enabled = !locked)
                }
                if (!locked) {
                    EncryptionLine(
                        lockedChoice = if (encryptable && deviceLocked) session.sendUnencrypted else null,
                        noKey = noKey,
                        onUnlock = {
                            unlockThen = null
                            unlockLabel = "Unlock"
                            unlocking = true
                        },
                        onSendUnencrypted = {
                            if (ready()) {
                                session.sendUnencrypted = true
                                notSent = false
                                sendNow()
                            }
                        },
                        onEncrypt = { session.sendUnencrypted = false },
                    )
                }
                if (notSent && encryptable && deviceLocked && !session.sendUnencrypted) {
                    Text(
                        "Not sent. Unlock encrypted mail, or choose Send without encryption.",
                        style = MaterialTheme.typography.bodySmall,
                        color = paper.snooze,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                val subjectFocus = focusers.getValue(ComposeField.SUBJECT)
                FieldRow("Subject", onTap = { subjectFocus.requestFocus() }) {
                    val subjectValue = TextFieldValue(session.subject, session.subjectSelection.coerceIn(0, session.subject.length))
                    BasicTextField(
                        value = subjectValue,
                        onValueChange = {
                            session.subject = it.text.replace('\n', ' ')
                            session.subjectSelection = it.selection
                        },
                        readOnly = locked,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = paper.text, fontWeight = FontWeight.Medium),
                        cursorBrush = SolidColor(paper.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(subjectFocus)
                            .onFocusChanged { if (it.isFocused) session.cursorField = ComposeField.SUBJECT }
                            .semantics { contentDescription = "Subject" },
                    )
                }
                BodyField(session, locked, focusers.getValue(ComposeField.BODY))
                session.signature?.let { signature ->
                    Text(
                        "$SIGNATURE_SEPARATOR\n$signature",
                        style = MaterialTheme.typography.bodyMedium,
                        color = paper.textSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                session.quote?.let { quote ->
                    QuoteFold(quote, removable = !locked) { session.quote = null }
                }
                FileList(session, sending, locked)
            }
            if (!locked) {
                FormatRow(session, sending) { insertImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
            }
        }
        // The app's pill sits under the composer, so what it says while the composer is open shows here.
        (notice as? SendNotice.Info ?: notice as? SendNotice.Failed)?.let { shown ->
            SendBar(
                notice = shown,
                onUndo = null,
                onView = { requestClose(onViewOutbox) },
                onOpen = {},
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 64.dp),
            )
        }
    }

    if (scheduling) {
        ScheduleSheet(
            stats = sendStats,
            vary = settings.vary,
            onDismiss = { scheduling = false },
            onPick = { pick ->
                sending.sendStats.commit(System.currentTimeMillis(), pick.learnAt, pick.source, pick.keyId)
                sending.send(session, pick.sendAt, pick.chosenAt, undoable = false)
            },
        )
    }
    if (unlocking) {
        val action = unlockThen
        UnlockToSendDialog(
            encryption = sending.encryption,
            hasPasskeys = !settings.pgp?.sealed.isNullOrEmpty(),
            confirmLabel = unlockLabel,
            onUnlocked = {
                unlocking = false
                unlockThen = null
                notSent = false
                action?.invoke()
            },
            onDismiss = {
                unlocking = false
                unlockThen = null
                // Nothing was sent or rescheduled; the composer stays open and says so.
                if (action != null) notSent = true
            },
        )
    }
}

@Composable
private fun FormatRow(session: ComposeSession, sending: Sending, onInsertImage: () -> Unit) {
    Column {
        HorizontalDivider(color = paper.separator)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().height(48.dp).background(paper.card).padding(horizontal = 8.dp),
        ) {
            FormatButton(Icons.Outlined.FormatBold, "Bold") { session.body.toggleBold() }
            FormatButton(Icons.Outlined.FormatItalic, "Italic") { session.body.toggleItalic() }
            FormatButton(Icons.Outlined.Link, "Link") { session.body.requestLink() }
            FormatButton(Icons.Outlined.Image, "Insert image", onInsertImage)
            Spacer(Modifier.weight(1f))
            FormatButton(Icons.Outlined.Delete, "Discard draft") { sending.discard(session) }
        }
    }
}

/** Why the draft can't be edited now: its send is under way or being retried, with a way to stop it. */
@Composable
private fun LockedNote(doc: OutboxDoc, onCancel: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .fillMaxWidth()
            .background(paper.muted, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            val retrying = doc.status == OutboxStatus.RETRYING
            Text(
                if (retrying) retryingText(doc.nextAttemptAt) else "Sending…",
                style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                color = paper.text,
            )
            val detail = if (retrying) retryingDetail(doc.lastErrorCode, doc.lastErrorMessage) else null
            Text(
                listOfNotNull(detail, "To change it, cancel the send first.").joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = paper.textSecondary,
            )
        }
        TextButton(onClick = onCancel) { Text("Cancel send", color = paper.accent) }
    }
}

/** A reply's quote, folded to a quiet "…" that opens it read-only, with a way to leave it out. */
@Composable
private fun QuoteFold(quote: QuotedMessage, removable: Boolean, onRemove: () -> Unit) {
    var open by remember(quote) { mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        QuoteToggle(open) { open = !open }
        if (open) {
            val text = remember(quote) { quoteText(quote) }
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = paper.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (removable) {
                TextButton(onClick = onRemove) { Text("Remove quoted text", color = paper.textSecondary) }
            }
        }
    }
}

/** The composer body's text style. */
@Composable
internal fun bodyStyle() = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp)

private fun firstName(person: Person): String =
    person.name.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotEmpty() } ?: person.email

/**
 * The line under the recipients when a message from @hafa.cc isn't plainly encrypted, as on web:
 * [lockedChoice] is non-null when everyone has a key but this phone is locked (true once the user
 * chose to send without encryption); [noKey] are recipients found to have no key.
 */
@Composable
private fun EncryptionLine(
    lockedChoice: Boolean?,
    noKey: List<Person>,
    onUnlock: () -> Unit,
    onSendUnencrypted: () -> Unit,
    onEncrypt: () -> Unit,
) {
    val text = when {
        lockedChoice == false -> "Encrypted once you unlock this phone; Send asks"
        lockedChoice == true -> "Not encrypted — you chose to send without encryption"
        noKey.isEmpty() -> return
        noKey.size == 1 -> "Not encrypted — ${firstName(noKey[0])} has no key"
        noKey.size == 2 -> "Not encrypted — ${firstName(noKey[0])} and ${firstName(noKey[1])} have no key"
        else -> "Not encrypted — ${noKey.size} people have no key"
    }
    FlowRow(
        verticalArrangement = Arrangement.Center,
        itemVerticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp),
    ) {
        Icon(
            if (lockedChoice == false) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
            contentDescription = null,
            tint = if (lockedChoice == false) paper.lock else paper.textSecondary,
            modifier = Modifier.size(14.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
        when (lockedChoice) {
            false -> {
                LineButton("Unlock", onUnlock)
                LineButton("Send without encryption", onSendUnencrypted)
            }
            true -> LineButton("Encrypt instead", onEncrypt)
            null -> Unit
        }
    }
}

@Composable
private fun LineButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = paper.accent)
    }
}

/** The body; on its own so typing redraws only this field. */
@Composable
private fun BodyField(session: ComposeSession, locked: Boolean, focusRequester: FocusRequester) {
    RichBodyField(
        session.body,
        readOnly = locked,
        focusRequester = focusRequester,
        modifier = Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .onFocusChanged { if (it.hasFocus) session.cursorField = ComposeField.BODY },
    )
}

/** The files the user added, and a note while attachments are still being fetched. */
@Composable
private fun FileList(session: ComposeSession, sending: Sending, locked: Boolean) {
    val quotedImages = remember(session.quote) { quotedContentIds(session.quote?.html).toSet() }
    // The quote's own images go with it unseen; only what the user added is listed.
    val shown by remember(session, quotedImages) {
        derivedStateOf {
            session.files.filter { it.contentId == null || it.contentId !in quotedImages || session.body.markdown.contains("cid:${it.contentId}") }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
        for (file in shown) {
            FileCard(file, { sending.drafts.read(session.localId, file) }, removable = !locked) {
                session.files.remove(file)
                file.contentId?.let { id ->
                    session.body.removeText(Regex("""!\[[^\]]*]\(cid:${Regex.escape(id)}\)"""))
                }
            }
        }
        if (session.fetching > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(color = paper.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text("Getting attachments…", style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
            }
        }
    }
}

@Composable
private fun SendButton(onSend: () -> Unit, onSchedule: () -> Unit, enabled: Boolean, encrypted: Boolean) {
    Row(
        modifier = Modifier
            .padding(start = 4.dp)
            .clip(CircleShape)
            .height(48.dp)
            .alpha(if (enabled) 1f else 0.4f),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxHeight()
                .background(paper.accent)
                .clickable(enabled = enabled, onClickLabel = "Send", role = Role.Button, onClick = onSend)
                .padding(start = 18.dp, end = 14.dp),
        ) {
            Icon(
                if (encrypted) Icons.Outlined.Lock else Icons.AutoMirrored.Outlined.Send,
                contentDescription = null,
                tint = paper.onAccent,
                modifier = Modifier.size(16.dp),
            )
            Text(if (encrypted) "Encrypted" else "Send", color = paper.onAccent, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        }
        Box(Modifier.width(1.dp).fillMaxHeight().background(paper.onAccent.copy(alpha = 0.3f)))
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .width(48.dp)
                .fillMaxHeight()
                .background(paper.accent)
                .clickable(enabled = enabled, onClickLabel = "Schedule send", role = Role.Button, onClick = onSchedule)
                .semantics { contentDescription = "Schedule send" },
        ) {
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = paper.onAccent, modifier = Modifier.size(18.dp))
        }
    }
}

/** A labeled line of the composer; with [onTap], a tap anywhere on it outside its controls (its label too) puts the cursor in its field. */
@Composable
private fun FieldRow(label: String, trailing: @Composable () -> Unit = {}, onTap: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    // Focusing a field that already has focus doesn't bring back a keyboard the user put away.
    val keyboard = LocalSoftwareKeyboardController.current
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .then(if (onTap == null) Modifier else Modifier.pointerInput(Unit) { detectTapGestures { tap?.invoke(); keyboard?.show() } })
                .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = paper.textSecondary, modifier = Modifier.width(56.dp))
            Box(modifier = Modifier.weight(1f)) { content() }
            trailing()
        }
        HorizontalDivider(color = paper.separator)
    }
}

@Composable
private fun FromRow(session: ComposeSession, senders: List<Person>, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    FieldRow("From") {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = enabled && senders.size > 1, onClickLabel = "Change sender") { open = true }
                    .padding(horizontal = 10.dp, vertical = 10.dp)
                    .semantics { contentDescription = "From: ${session.from.email}" },
            ) {
                Text(session.from.email, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = paper.text)
                if (enabled && senders.size > 1) {
                    Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(16.dp))
                }
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                containerColor = paper.raised,
                shape = RoundedCornerShape(22.dp),
            ) {
                for (sender in senders) {
                    val chosen = sender.email.equals(session.from.email, ignoreCase = true)
                    DropdownMenuItem(
                        leadingIcon = { Avatar(sender, size = 32.dp, single = true) },
                        trailingIcon = {
                            if (chosen) Icon(Icons.Outlined.Check, contentDescription = "Selected", tint = paper.accent, modifier = Modifier.size(18.dp))
                        },
                        text = {
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                Text(sender.email, color = paper.text, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                                if (sender.name.isNotEmpty()) Text(sender.name, style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
                            }
                        },
                        onClick = {
                            session.from = sender
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecipientRow(
    session: ComposeSession,
    field: RecipientField,
    label: String,
    typed: MutableMap<RecipientField, String>,
    contacts: List<Contact>,
    keys: Map<String, String?>,
    focusRequester: FocusRequester,
    enabled: Boolean = true,
    trailing: @Composable () -> Unit = {},
) {
    val people = session.recipients(field)
    var focused by remember { mutableStateOf(false) }
    val text = typed[field].orEmpty()
    // An empty field after chips holds ADDRESS_SENTINEL, so a soft keyboard's backspace there has
    // something to delete, which takes off the last recipient (as on the web).
    val shown = if (text.isEmpty() && people.isNotEmpty() && enabled) ADDRESS_SENTINEL else text
    var fieldValue by remember { mutableStateOf(TextFieldValue(shown, TextRange(shown.length))) }
    val value = if (fieldValue.text == shown) fieldValue else TextFieldValue(shown, TextRange(shown.length))
    val chosen by remember(session) { derivedStateOf { (session.to + session.cc + session.bcc).map { addressKey(it.email) }.toSet() } }
    val suggestions = remember(text, contacts, chosen) { if (text.isBlank()) emptyList() else suggestContacts(contacts, text, chosen) }

    fun add(person: Person) {
        if (people.none { addressKey(it.email) == addressKey(person.email) }) people += person
        typed[field] = ""
    }

    FieldRow(label, trailing, onTap = { focusRequester.requestFocus() }) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            for (person in people) {
                RecipientChip(person, hasKey = keys[addressKey(person.email)] != null, onRemove = if (enabled) ({ people.remove(person) }) else null)
            }
            BasicTextField(
                value = value,
                onValueChange = { next ->
                    when (val edit = addressEdit(shown, next)) {
                        AddressEdit.RemoveLast -> {
                            people.removeAt(people.lastIndex)
                            fieldValue = TextFieldValue()
                        }
                        is AddressEdit.Finish -> {
                            val person = typedPerson(edit.text)
                            if (person != null) add(person) else typed[field] = edit.text.trimStart()
                            fieldValue = TextFieldValue()
                        }
                        is AddressEdit.Type -> {
                            typed[field] = edit.value.text
                            fieldValue = edit.value
                        }
                    }
                },
                readOnly = !enabled,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = paper.text),
                cursorBrush = SolidColor(paper.accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = {
                    val best = suggestions.firstOrNull() ?: typedPerson(text)
                    if (best != null) add(best)
                }),
                // Fills what the line has left beside the chips, onto a new line only below this.
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 48.dp)
                    .padding(vertical = 8.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (focused && !state.isFocused) typedPerson(text)?.let(::add)
                        focused = state.isFocused
                        if (state.isFocused) session.cursorField = ComposeField.valueOf(field.name)
                    }
                    .semantics { contentDescription = label },
            )
        }
    }
    if (focused && suggestions.isNotEmpty()) {
        Column(modifier = Modifier.fillMaxWidth().background(paper.page)) {
            for (person in suggestions) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { add(person) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Avatar(person, size = 32.dp)
                    Column(modifier = Modifier.weight(1f)) {
                        if (person.name.isNotEmpty()) {
                            Text(person.name, color = paper.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(person.email, style = MaterialTheme.typography.bodySmall, color = paper.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            HorizontalDivider(color = paper.separator)
        }
    }
}

@Composable
private fun RecipientChip(person: Person, hasKey: Boolean, onRemove: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .height(32.dp)
            .border(1.dp, paper.border, RoundedCornerShape(16.dp))
            .padding(start = 4.dp, end = 2.dp),
    ) {
        Avatar(person, size = 24.dp, single = true)
        Text(
            person.name.ifEmpty { person.email },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = paper.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 200.dp),
        )
        if (hasKey) {
            Icon(Icons.Outlined.Lock, contentDescription = "Has a key", tint = paper.lock, modifier = Modifier.size(14.dp))
        }
        if (onRemove != null) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .tapArea(28.dp)
                    .clickable(
                        interactionSource = null,
                        indication = ripple(bounded = false, radius = 14.dp),
                        onClickLabel = "Remove ${person.email}",
                        onClick = onRemove,
                    ),
            ) {
                Icon(Icons.Outlined.Close, contentDescription = "Remove ${person.email}", tint = paper.textSecondary, modifier = Modifier.size(14.dp))
            }
        } else {
            Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
private fun FormatButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = paper.textSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun FileCard(file: DraftFile, read: () -> ByteArray, removable: Boolean, onRemove: () -> Unit) {
    val context = LocalContext.current
    // Draft files are sealed on disk, so the image loader gets the opened bytes.
    val thumbnail by produceState<ImageRequest?>(null, file.id, file.mimeType) {
        if (file.mimeType.startsWith("image/")) {
            val bytes = withContext(Dispatchers.IO) { runCatching(read).getOrNull() }
            if (bytes != null) {
                value = ImageRequest.Builder(context).data(bytes).size(THUMBNAIL_PX).memoryCacheKey("draft-file:${file.id}").build()
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .border(1.dp, paper.border, RoundedCornerShape(10.dp))
            .padding(start = 12.dp, end = 4.dp),
    ) {
        val image = thumbnail
        if (image != null) {
            AsyncImage(
                model = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)),
            )
        } else {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(32.dp).background(paper.muted, RoundedCornerShape(8.dp))) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(file.filename, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = paper.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val kind = if (file.contentId != null) "In the message" else file.mimeType.substringAfter('/').uppercase()
            Text("${formatSize(file.size)} · $kind", style = MaterialTheme.typography.bodySmall, color = paper.textSecondary, maxLines = 1)
        }
        IconButton(onClick = onRemove, enabled = removable) {
            Icon(
                Icons.Outlined.Close,
                contentDescription = "Remove ${file.filename}",
                tint = if (removable) paper.textSecondary else paper.faint,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
