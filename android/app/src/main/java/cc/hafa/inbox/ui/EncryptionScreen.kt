package cc.hafa.inbox.ui

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.inbox.core.pgp.KeyPair
import cc.hafa.inbox.core.pgp.PHRASE_WORDS
import cc.hafa.inbox.core.pgp.SealException
import cc.hafa.inbox.core.pgp.generatePhrase
import cc.hafa.inbox.core.pgp.parsePhrase
import cc.hafa.inbox.mail.MailState
import cc.hafa.inbox.pgp.Encryption
import cc.hafa.inbox.pgp.EncryptionStatus
import cc.hafa.inbox.pgp.PasskeyException
import cc.hafa.inbox.pgp.PasskeyProblem
import cc.hafa.inbox.pgp.PgpRecord
import cc.hafa.inbox.pgp.SealedCopy
import cc.hafa.inbox.ui.theme.fieldFrame
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.random.Random

private const val NO_PRF_TEXT = "This password manager can't unlock mail. Use Google Password Manager or iCloud Keychain."

/** Where making a new key (setup or reset) stands. */
private sealed interface Making {
    /** about to make a passkey */
    data object Start : Making

    /** the passkey step is done; [phrase] is shown and confirmed next */
    class Phrase(val pair: KeyPair, val sealed: List<SealedCopy>, val phrase: String) : Making
}

private fun passkeyText(error: PasskeyException): String? = when (error.problem) {
    PasskeyProblem.CANCELED -> null
    PasskeyProblem.NO_PRF -> NO_PRF_TEXT
    PasskeyProblem.NONE_FOUND -> "No passkey for your mail is on this phone. Use your recovery phrase."
    PasskeyProblem.ALREADY_HERE -> "This password manager already has a passkey for your mail."
    PasskeyProblem.FAILED -> "The passkey didn't work. Try again."
}

/**
 * Settings › Encryption: turn on encrypted mail (key, passkey, recovery phrase), unlock it on
 * this phone, and manage passkeys, the recovery phrase and reset.
 */
@Composable
fun EncryptionScreen(state: MailState, onBack: () -> Unit, onAddress: () -> Unit) {
    val encryption = state.encryption
    val status by encryption.status.collectAsStateWithLifecycle()
    val settings by state.sending.account.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var making by remember { mutableStateOf<Making?>(null) }
    var newPhrase by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var noPrf by remember { mutableStateOf(false) }
    var resetting by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SealedCopy?>(null) }
    var unlockedByPhrase by remember { mutableStateOf(false) }
    ScreenBackHandler(onBack = onBack)

    fun run(action: suspend () -> Unit) {
        working = true
        error = null
        scope.launch {
            try {
                action()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: PasskeyException) {
                noPrf = failure.problem == PasskeyProblem.NO_PRF
                error = passkeyText(failure)
            } catch (failure: SealException) {
                error = "That phrase doesn't unlock your mail."
            } catch (failure: Exception) {
                error = "Something went wrong. Try again."
            } finally {
                working = false
            }
        }
    }

    fun startMaking(withPasskey: Boolean) = run {
        val pair = encryption.newKeyPair(state.sending.defaultFrom().name)
        val sealed = if (withPasskey) listOf(encryption.createPasskey(context, pair.privateKey, emptyList())) else emptyList()
        making = Making.Phrase(pair, sealed, generatePhrase())
        noPrf = false
    }

    val record = settings.pgp
    val currentMaking = making
    val shownPhrase = newPhrase
    Column(modifier = Modifier.fillMaxSize().background(paper.page).imePadding()) {
        PlainTopBar("Encryption", onBack)
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            when {
                currentMaking is Making.Phrase -> PhraseStep(currentMaking.phrase, working, error, finishLabel = "Finish") {
                    run {
                        encryption.finishSetup(currentMaking.pair, currentMaking.sealed, currentMaking.phrase)
                        making = null
                    }
                }
                currentMaking == Making.Start -> {
                    Heading(if (record != null) "Reset encryption" else "Turn on encrypted mail")
                    Body("Mail you send from ${settings.address.orEmpty()} is encrypted on its own when every recipient has a key.")
                    Body("Your private key is stored locked on our server. A passkey unlocks it on this phone with your fingerprint or screen lock.")
                    PrimaryButton("Create passkey", Icons.Outlined.Fingerprint, working) { startMaking(withPasskey = true) }
                    ErrorLine(error)
                    if (noPrf) {
                        TextButton(onClick = { startMaking(withPasskey = false) }, enabled = !working) {
                            Text("Continue with only a recovery phrase", color = paper.accent)
                        }
                    }
                    TextButton(onClick = { making = null }) { Text("Cancel", color = paper.textSecondary) }
                }
                shownPhrase != null -> PhraseStep(shownPhrase, working, error, finishLabel = "Save new phrase") {
                    run {
                        encryption.replaceRecoveryPhrase(shownPhrase)
                        newPhrase = null
                    }
                }
                status == EncryptionStatus.LOADING -> CircularProgressIndicator(color = paper.accent, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                status == EncryptionStatus.NO_ADDRESS -> {
                    Heading("Encrypted mail")
                    Body("Encrypted mail works with your @hafa.cc address. Set one up first.")
                    PrimaryButton("Set up address", null, false, onAddress)
                }
                status == EncryptionStatus.OFF -> {
                    Heading("Encrypted mail")
                    Body("Mail you send from ${settings.address.orEmpty()} is encrypted on its own when every recipient has a key, and mail encrypted to you opens here.")
                    PrimaryButton("Turn on", Icons.Outlined.Lock, false) {
                        error = null
                        making = Making.Start
                    }
                }
                status == EncryptionStatus.LOCKED && record != null -> UnlockStep(
                    hasPasskeys = record.sealed.isNotEmpty(),
                    working = working,
                    error = error,
                    onPasskey = { run { encryption.unlockWithPasskey(context) } },
                    onPhrase = { phrase ->
                        run {
                            encryption.unlockWithPhrase(phrase)
                            unlockedByPhrase = true
                        }
                    },
                    onReset = { resetting = true },
                )
                record != null -> {
                    Heading("Encrypted mail is on")
                    Body("Mail you send from ${settings.address.orEmpty()} is encrypted when every recipient has a key.")
                    if (unlockedByPhrase) {
                        Body("Add a passkey for this phone so it unlocks without the phrase next time.")
                    }
                    SectionLabel("Passkeys")
                    Column(modifier = Modifier.fillMaxWidth().background(paper.card, RoundedCornerShape(12.dp)).border(1.dp, paper.border, RoundedCornerShape(12.dp))) {
                        if (record.sealed.isEmpty()) {
                            Text("No passkeys. Only your recovery phrase unlocks your mail.", color = paper.textSecondary, modifier = Modifier.padding(16.dp))
                        }
                        record.sealed.forEachIndexed { index, copy ->
                            if (index > 0) HorizontalDivider(color = paper.separator)
                            PasskeyRow(copy, enabled = !working) { removing = copy }
                        }
                    }
                    if (record.sealed.size == 1) {
                        Text("Only one passkey unlocks your mail. Keep your recovery phrase safe.", style = MaterialTheme.typography.bodySmall, color = paper.snooze)
                    }
                    ErrorLine(error)
                    if (record.sealed.size < PgpRecord.MAX_PASSKEYS) {
                        TextButton(onClick = { run { encryption.addPasskey(context).also { unlockedByPhrase = false } } }, enabled = !working) {
                            Text("Add a passkey", color = paper.accent)
                        }
                    }
                    if (noPrf) Text(NO_PRF_TEXT, style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
                    SectionLabel("Recovery phrase")
                    Body("If you lose every passkey, your 8-word recovery phrase unlocks your mail.")
                    TextButton(onClick = { newPhrase = generatePhrase() }, enabled = !working) { Text("New recovery phrase", color = paper.accent) }
                    TextButton(onClick = { resetting = true }, enabled = !working, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Reset encryption", color = paper.snooze)
                    }
                }
            }
        }
    }
    removing?.let { copy ->
        val last = record?.sealed?.size == 1
        AlertDialog(
            onDismissRequest = { removing = null },
            containerColor = paper.card,
            title = { Text("Remove ${copy.name.ifBlank { "this passkey" }}?", color = paper.text) },
            text = {
                Text(
                    if (last) "Then only your recovery phrase unlocks your mail." else "It won't unlock your mail anymore.",
                    color = paper.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    run { encryption.removePasskey(copy.credentialId) }
                }) { Text("Remove", color = paper.snooze) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel", color = paper.textSecondary) } },
        )
    }
    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            containerColor = paper.card,
            title = { Text("Reset encryption?", color = paper.text) },
            text = {
                Text(
                    "This makes a new key. Mail encrypted to your current key can't be opened again, here or anywhere.",
                    color = paper.textSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    resetting = false
                    error = null
                    making = Making.Start
                }) { Text("Reset", color = paper.snooze) }
            },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text("Cancel", color = paper.textSecondary) } },
        )
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = paper.text)
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = paper.textSecondary)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = paper.textSecondary, modifier = Modifier.padding(top = 12.dp))
}

/** A short error line, or nothing. */
@Composable
internal fun ErrorLine(error: String?) {
    if (error != null) Text(error, style = MaterialTheme.typography.bodySmall, color = paper.snooze)
}

@Composable
private fun PrimaryButton(label: String, icon: ImageVector?, working: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !working,
        colors = ButtonDefaults.buttonColors(containerColor = paper.accent, contentColor = paper.onAccent),
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp),
    ) {
        if (working) {
            CircularProgressIndicator(color = paper.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(label)
        }
    }
}

@Composable
private fun PasskeyRow(copy: SealedCopy, enabled: Boolean, onRemove: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
        Icon(Icons.Outlined.Fingerprint, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(20.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(copy.name.ifBlank { "Passkey" }, color = paper.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                "Added ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(copy.addedAt))}",
                color = paper.textSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        TextButton(onClick = onRemove, enabled = enabled) { Text("Remove", color = paper.snooze) }
    }
}

/** A plain bordered text field with no autocorrect, outlined in warning color when [problem]. */
@Composable
internal fun PlainField(value: String, onChange: (String) -> Unit, label: String, problem: Boolean, modifier: Modifier = Modifier, singleLine: Boolean = true) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = paper.text),
        cursorBrush = SolidColor(paper.accent),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        modifier = modifier
            .fillMaxWidth()
            .fieldFrame(problem)
            .padding(horizontal = 14.dp, vertical = 14.dp)
            .semantics { contentDescription = label },
    )
}

/** The recovery phrase shown once, numbered, with Copy, then two of its words typed back. */
@Composable
private fun PhraseStep(phrase: String, working: Boolean, error: String?, finishLabel: String, onFinish: () -> Unit) {
    val words = remember(phrase) { phrase.split(" ") }
    val asked = remember(phrase) { (0 until PHRASE_WORDS).shuffled(Random.Default).take(2).sorted() }
    var first by remember(phrase) { mutableStateOf("") }
    var second by remember(phrase) { mutableStateOf("") }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    Heading("Your recovery phrase")
    Body("If you lose every passkey, these 8 words unlock your mail. This is the only time they're shown.")
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().background(paper.card, RoundedCornerShape(12.dp)).border(1.dp, paper.border, RoundedCornerShape(12.dp)).padding(16.dp),
    ) {
        for (row in 0 until PHRASE_WORDS / 2) {
            Row {
                for (column in 0..1) {
                    val index = column * (PHRASE_WORDS / 2) + row
                    Row(modifier = Modifier.weight(1f)) {
                        Text("${index + 1}", color = paper.faint, modifier = Modifier.width(24.dp))
                        Text(words.getOrElse(index) { "" }, color = paper.text, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
    TextButton(onClick = {
        scope.launch {
            val clip = ClipData.newPlainText("Recovery phrase", phrase)
            // Keeps the phrase out of the clipboard preview.
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
            clipboard.setClipEntry(ClipEntry(clip))
        }
    }) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = paper.accent, modifier = Modifier.size(16.dp))
        Text("Copy", color = paper.accent, modifier = Modifier.padding(start = 6.dp))
    }
    SectionLabel("To check you saved it, type words ${asked[0] + 1} and ${asked[1] + 1}")
    PlainField(first, { first = it }, "Word ${asked[0] + 1}", problem = false)
    PlainField(second, { second = it }, "Word ${asked[1] + 1}", problem = false)
    val matches = first.trim().lowercase() == words[asked[0]] && second.trim().lowercase() == words[asked[1]]
    ErrorLine(error)
    PrimaryButton(finishLabel, null, working) { if (matches) onFinish() }
    if (!matches && first.isNotBlank() && second.isNotBlank()) {
        Text("Those aren't the words. Check the list above.", style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
    }
}

/** Unlock on this phone: a passkey, or the recovery phrase typed in. */
@Composable
private fun UnlockStep(
    hasPasskeys: Boolean,
    working: Boolean,
    error: String?,
    onPasskey: () -> Unit,
    onPhrase: (String) -> Unit,
    onReset: () -> Unit,
) {
    var phrase by remember { mutableStateOf("") }
    val parsed = remember(phrase) { parsePhrase(phrase) }
    Heading("Unlock encrypted mail")
    Body("Your mail is encrypted. Unlock it on this phone with a passkey or your recovery phrase.")
    if (hasPasskeys) PrimaryButton("Use passkey", Icons.Outlined.Fingerprint, working, onPasskey)
    SectionLabel("Recovery phrase")
    PlainField(phrase, { phrase = it }, "Recovery phrase", problem = parsed.unknown.isNotEmpty(), singleLine = false)
    val unknown = parsed.unknown.map { parsed.words[it] }
    when {
        unknown.isNotEmpty() -> Text("Not in the word list: ${unknown.joinToString(", ")}", style = MaterialTheme.typography.bodySmall, color = paper.snooze)
        parsed.words.size > PHRASE_WORDS -> Text("The phrase has 8 words.", style = MaterialTheme.typography.bodySmall, color = paper.snooze)
        else -> Text("${parsed.words.size} of 8 words", style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
    }
    ErrorLine(error)
    TextButton(onClick = { parsed.normalized?.let(onPhrase) }, enabled = !working && parsed.normalized != null) {
        if (working) {
            CircularProgressIndicator(color = paper.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
            Text("Unlocking…", color = paper.textSecondary, modifier = Modifier.padding(start = 8.dp))
        } else {
            Text("Unlock with phrase", color = paper.accent)
        }
    }
    Body("Lost every passkey and the phrase? Reset encryption to start again with a new key.")
    TextButton(onClick = onReset, enabled = !working) { Text("Reset encryption", color = paper.snooze) }
}

/**
 * Asked before a message that must be encrypted goes (Send, Schedule, saving an edited scheduled
 * send) while this phone hasn't unlocked the key, or from the composer's Unlock: unlock with a
 * passkey or the recovery phrase. [confirmLabel] names what unlocking with the phrase also does.
 */
@Composable
fun UnlockToSendDialog(encryption: Encryption, hasPasskeys: Boolean, confirmLabel: String, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phrase by remember { mutableStateOf("") }
    var usePhrase by remember { mutableStateOf(!hasPasskeys) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val parsed = remember(phrase) { parsePhrase(phrase) }

    fun unlock(action: suspend () -> Unit) {
        working = true
        error = null
        scope.launch {
            try {
                action()
                onUnlocked()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: PasskeyException) {
                error = passkeyText(failure)
                if (failure.problem != PasskeyProblem.CANCELED) usePhrase = true
            } catch (failure: SealException) {
                error = "That phrase doesn't unlock your mail."
            } catch (failure: Exception) {
                error = "Something went wrong. Try again."
            } finally {
                working = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = paper.card,
        title = { Text("Unlock encrypted mail", color = paper.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Everyone on this message has a key. Unlock encrypted mail on this phone to send it encrypted.", color = paper.textSecondary)
                if (usePhrase) {
                    PlainField(phrase, { phrase = it }, "Recovery phrase", problem = parsed.unknown.isNotEmpty(), singleLine = false)
                    if (parsed.unknown.isNotEmpty()) {
                        Text(
                            "Not in the word list: ${parsed.unknown.joinToString(", ") { parsed.words[it] }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = paper.snooze,
                        )
                    }
                } else {
                    TextButton(onClick = { usePhrase = true }, enabled = !working) { Text("Use recovery phrase instead", color = paper.accent) }
                }
                ErrorLine(error)
            }
        },
        confirmButton = {
            if (working) {
                CircularProgressIndicator(color = paper.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else if (usePhrase) {
                TextButton(onClick = { parsed.normalized?.let { unlock { encryption.unlockWithPhrase(it) } } }, enabled = parsed.normalized != null) {
                    Text(confirmLabel, color = paper.accent)
                }
            } else {
                TextButton(onClick = { unlock { encryption.unlockWithPasskey(context) } }) { Text("Use passkey", color = paper.accent) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel", color = paper.textSecondary) } },
    )
}
