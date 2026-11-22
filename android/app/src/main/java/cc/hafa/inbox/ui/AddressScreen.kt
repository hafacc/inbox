package cc.hafa.inbox.ui

import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.inbox.core.address.ADDRESS_DOMAIN
import cc.hafa.inbox.core.address.AddressNoticeState
import cc.hafa.inbox.core.address.addressErrorText
import cc.hafa.inbox.core.address.addressNameProblem
import cc.hafa.inbox.core.address.addressNoticeState
import cc.hafa.inbox.core.address.addressReasonText
import cc.hafa.inbox.core.address.addressStatusText
import cc.hafa.inbox.core.address.normalizeAddressName
import cc.hafa.inbox.mail.MailState
import cc.hafa.inbox.ui.theme.fieldFrame
import cc.hafa.inbox.ui.theme.paper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a step of setting up the address stands. */
private enum class StepMark { DONE, WORKING, PROBLEM }

private const val TAG = "AddressScreen"

// How long the ring Fix it draws around Try again stays.
private const val POINT_AT_RETRY_MS = 2_500L

/**
 * Settings › Address: pick a name@hafa.cc (checked as typed) or take back the one this account had
 * before it was deleted, watch setup confirm it, find Cloudflare's mail when it has to be confirmed
 * by hand.
 */
@Composable
fun AddressScreen(
    state: MailState,
    email: String,
    onBack: () -> Unit,
    onFindConfirmation: () -> Unit,
    pointAtRetry: Boolean = false,
    onPointedAtRetry: () -> Unit = {},
) {
    val settings by state.sending.account.settings.collectAsStateWithLifecycle()
    val model by state.model.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf(email.substringBefore('@').lowercase().filter { it.isLetterOrDigit() || it == '.' || it == '-' }) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var former by remember { mutableStateOf<String?>(null) }
    ScreenBackHandler(onBack = onBack)

    fun run(action: suspend () -> Unit) {
        working = true
        error = null
        scope.launch {
            try {
                action()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = state.addresses.errorText(failure)
            } finally {
                working = false
            }
        }
    }

    val address = settings.address
    // Only the address this account had before it was deleted can be claimed, so offer just that.
    LaunchedEffect(address == null) {
        if (address != null) return@LaunchedEffect
        former = try {
            state.addresses.former()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Log.w(TAG, "former address lookup failed", failure)
            null
        }
    }
    Column(modifier = Modifier.fillMaxSize().background(paper.page).imePadding()) {
        PlainTopBar("Address", onBack)
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            val previous = former
            if (address == null && previous != null) {
                Text("Take back your address", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = paper.text)
                Text(previous, style = MaterialTheme.typography.titleMedium, color = paper.text)
                Text(
                    "This account had this address before it was deleted. It is the only one it can have, and mail to it will land in $email again.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = paper.textSecondary,
                )
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = paper.snooze) }
                Button(
                    onClick = { run { state.addresses.claim(normalizeAddressName(previous)) } },
                    enabled = !working,
                    colors = ButtonDefaults.buttonColors(containerColor = paper.accent, contentColor = paper.onAccent),
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp),
                ) {
                    if (working) CircularProgressIndicator(color = paper.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text("Take back $previous")
                }
                TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Skip for now", color = paper.textSecondary)
                }
            } else if (address == null) {
                val normalized = normalizeAddressName(name)
                val problem = if (name.isBlank()) null else addressNameProblem(name)
                Text("Pick your address", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = paper.text)
                Text("Address", style = MaterialTheme.typography.labelLarge, color = paper.textSecondary)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .fieldFrame(problem != null)
                        .padding(horizontal = 14.dp),
                ) {
                    BasicTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            error = null
                        },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = paper.text),
                        cursorBrush = SolidColor(paper.accent),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        modifier = Modifier.weight(1f).semantics { contentDescription = "Address name" },
                    )
                    Text("@$ADDRESS_DOMAIN", color = paper.textSecondary)
                }
                when {
                    problem != null -> Text(addressReasonText(problem).orEmpty(), style = MaterialTheme.typography.bodySmall, color = paper.snooze)
                    error != null -> Text(error ?: "", style = MaterialTheme.typography.bodySmall, color = paper.snooze)
                    normalized.isNotEmpty() -> Text("$normalized@$ADDRESS_DOMAIN", style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
                }
                Text(
                    "Mail to this address lands in $email. You can send from it here. It stays yours for good and can't be changed later.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = paper.textSecondary,
                )
                Button(
                    onClick = {
                        run { state.addresses.claim(normalized) }
                    },
                    enabled = !working && name.isNotBlank() && problem == null,
                    colors = ButtonDefaults.buttonColors(containerColor = paper.accent, contentColor = paper.onAccent),
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(top = 4.dp),
                ) {
                    if (working) CircularProgressIndicator(color = paper.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text("Continue")
                }
                TextButton(onClick = onBack, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Skip for now", color = paper.textSecondary)
                }
            } else {
                val setup = settings.addressSetup
                val status = setup?.status
                Text(address, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, color = paper.text)
                Text(addressStatusText(status, address), style = MaterialTheme.typography.bodyMedium, color = paper.textSecondary)
                val byHand = setup?.errorCode == "confirm-by-hand"
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    StepLine("Creating address", StepMark.DONE)
                    StepLine(
                        "Confirming with Cloudflare",
                        when {
                            status == "active" -> StepMark.DONE
                            byHand -> StepMark.PROBLEM
                            else -> StepMark.WORKING
                        },
                    )
                }
                addressErrorText(setup?.errorCode)?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = paper.text) }
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = paper.snooze) }
                if (byHand) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onFindConfirmation) { Text("Find the email", color = paper.accent) }
                        TextButton(onClick = { run { state.addresses.check() } }, enabled = !working) {
                            Text("Check again", color = paper.accent)
                        }
                    }
                } else if (setup != null && addressNoticeState(status, setup.errorCode, setup.updatedAt, model.now) == AddressNoticeState.ERROR) {
                    if (addressErrorText(setup.errorCode) == null) {
                        Text(
                            "Setup stopped partway. Try again to pick up where it left off.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = paper.textSecondary,
                        )
                    }
                    val retry = remember { FocusRequester() }
                    val bring = remember { BringIntoViewRequester() }
                    val ring by animateColorAsState(if (pointAtRetry) paper.accentSoft else Color.Transparent, label = "retry ring")
                    LaunchedEffect(pointAtRetry) {
                        if (!pointAtRetry) return@LaunchedEffect
                        bring.bringIntoView()
                        retry.requestFocus()
                        delay(POINT_AT_RETRY_MS)
                        onPointedAtRetry()
                    }
                    Button(
                        onClick = { run { state.addresses.check() } },
                        enabled = !working,
                        colors = ButtonDefaults.buttonColors(containerColor = paper.accent, contentColor = paper.onAccent),
                        modifier = Modifier
                            .border(4.dp, ring, RoundedCornerShape(50))
                            .padding(4.dp)
                            .height(48.dp)
                            .focusRequester(retry)
                            .bringIntoViewRequester(bring),
                    ) {
                        if (working) CircularProgressIndicator(color = paper.onAccent, strokeWidth = 2.dp, modifier = Modifier.size(18.dp)) else Text("Try again")
                    }
                }
            }
            SendAsSection(state)
        }
    }
}

@Composable
private fun StepLine(text: String, mark: StepMark) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(20.dp)) {
            when (mark) {
                StepMark.DONE -> Icon(Icons.Outlined.Check, contentDescription = "Done", tint = paper.accent, modifier = Modifier.size(18.dp))
                StepMark.WORKING -> CircularProgressIndicator(color = paper.accent, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                StepMark.PROBLEM -> Icon(Icons.Outlined.ErrorOutline, contentDescription = "Waiting on you", tint = paper.snooze, modifier = Modifier.size(18.dp))
            }
        }
        Text(text, color = paper.text)
    }
}

// Gmail's settings page for send-as addresses; the Gmail API can't add one on a personal account.
private const val GMAIL_ACCOUNTS_SETTINGS = "https://mail.google.com/mail/u/0/#settings/accounts"

/** The addresses compose can send from, unconfirmed Gmail ones below, and where to change them. */
@Composable
private fun SendAsSection(state: MailState) {
    val senders by state.sending.senders.collectAsStateWithLifecycle()
    val sendAs by state.sending.sendAsList.collectAsStateWithLifecycle()
    val unconfirmed = sendAs.filter { !it.isPrimary && it.verificationStatus == "pending" }
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Send mail as", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = paper.text, modifier = Modifier.weight(1f))
            IconButton(
                onClick = {
                    scope.launch {
                        refreshing = true
                        try {
                            state.sending.reloadSendAs()
                        } finally {
                            refreshing = false
                        }
                    }
                },
                enabled = !refreshing,
            ) {
                Icon(Icons.Outlined.Refresh, contentDescription = "Check Gmail for changes", tint = paper.textSecondary)
            }
        }
        Text(
            "Compose offers these in From. Your @hafa.cc address joins them once mail to it arrives.",
            style = MaterialTheme.typography.bodySmall,
            color = paper.textSecondary,
        )
        Column(modifier = Modifier.fillMaxWidth().background(paper.card, RoundedCornerShape(16.dp)).border(1.dp, paper.border, RoundedCornerShape(16.dp))) {
            for (sender in senders) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(sender.email, style = MaterialTheme.typography.bodyMedium, color = paper.text)
                    if (sender.name.isNotEmpty()) Text(sender.name, style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
                }
            }
            for (entry in unconfirmed) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(entry.sendAsEmail, style = MaterialTheme.typography.bodyMedium, color = paper.textSecondary)
                    Text("Waiting for its confirmation in Gmail", style = MaterialTheme.typography.bodySmall, color = paper.textSecondary)
                }
            }
        }
        TextButton(onClick = { uriHandler.openUri(GMAIL_ACCOUNTS_SETTINGS) }) {
            Text("Add or change these in Gmail's settings", color = paper.accent)
        }
    }
}

