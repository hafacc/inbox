package cc.hafa.inbox.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.PanTool
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Draw
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsPaused
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.hafa.inbox.InboxApplication
import cc.hafa.inbox.core.mail.MAX_OFFLINE_DAYS
import cc.hafa.inbox.core.mail.Person
import cc.hafa.inbox.core.mail.formatSize
import cc.hafa.inbox.core.runSuspendCatching
import cc.hafa.inbox.core.send.SendSuggestions
import cc.hafa.inbox.core.menu.CornerSide
import cc.hafa.inbox.core.theme.ThemePalette
import cc.hafa.inbox.data.ImagePolicy
import cc.hafa.inbox.data.ThemeMode
import cc.hafa.inbox.mail.OfflineCopy
import cc.hafa.inbox.mail.Sending
import cc.hafa.inbox.pgp.EncryptionStatus
import cc.hafa.inbox.ui.theme.LocalDarkTheme
import cc.hafa.inbox.ui.theme.paper
import cc.hafa.inbox.ui.theme.rowHighlight
import cc.hafa.inbox.ui.theme.themeTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val DELETE_WORD = "delete"

// How long typing in the signature pauses before it is saved, as on the web.
private const val SIGNATURE_SAVE_DELAY_MS = 600L

/**
 * Settings: account, address, signature, default From, images, schedule-send variation,
 * notifications, labels, filters, encryption, mail saved for offline, sign out, deleting the account, and links to privacy, support, feedback and the source code.
 */
@Composable
fun SettingsScreen(
    sending: Sending,
    email: String,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    onDeleteAccount: suspend () -> Unit,
    onAddress: () -> Unit,
    onSignature: () -> Unit,
    onFilters: () -> Unit,
    onEncryption: () -> Unit,
    onLabels: () -> Unit,
    addressAlert: Boolean,
    savedMail: OfflineCopy,
    savedMailBytes: suspend () -> Long,
) {
    val context = LocalContext.current
    var deleting by rememberSaveable { mutableStateOf(false) }
    val settings by sending.account.settings.collectAsStateWithLifecycle()
    val senders by sending.senders.collectAsStateWithLifecycle()
    val notifications = rememberNotificationsControl()
    ScreenBackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize().background(paper.page)) {
        PlainTopBar("Settings", onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(76.dp)
                    .background(paper.card, RoundedCornerShape(22.dp))
                    .padding(horizontal = 16.dp),
            ) {
                Avatar(Person("", email), size = 40.dp, single = true)
                Text(email, fontWeight = FontWeight.SemiBold, color = paper.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            SectionTitle("Mail")
            Group {
                val addressValue = settings.address?.let { address ->
                    if (settings.addressSetup?.status == "active" || settings.addressSetup == null) address else "$address (setting up)"
                } ?: "Pick one"
                SettingRow(Icons.Outlined.AlternateEmail, "Address", addressValue, onClick = onAddress, alert = addressAlert)
                DefaultFromRow(senders, sending.defaultFrom().email) { sending.account.setSendAsDefault(it) }
                SettingRow(
                    Icons.Outlined.Draw,
                    "Signature",
                    settings.signature?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: "None",
                    onClick = onSignature,
                )
                ImagesRow(settings.imagePolicy, sending.account::setImagePolicy)
                SwitchRow(
                    title = "Vary scheduled send times",
                    checked = settings.vary.enabled,
                    onChange = sending.account::setVarySendTimes,
                    detail = "Moves suggested times a little, so mail doesn't land on the hour",
                    icon = Icons.Outlined.Shuffle,
                    minHeight = 64.dp,
                    padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                )
                if (settings.vary.enabled) {
                    MaxShiftRow(settings.vary.maxMinutes, sending.account::setVarySendMaxMinutes)
                }
            }
            SectionTitle("Look")
            Group {
                val look = (context.applicationContext as InboxApplication).theme
                val shownTheme by look.themeId.collectAsStateWithLifecycle()
                val mode by look.mode.collectAsStateWithLifecycle()
                ModeRow(mode, look::setMode)
                ThemeRow(shownTheme, LocalDarkTheme.current) { id ->
                    sending.account.setTheme(id)
                    look.show(id)
                }
                val corner = (context.applicationContext as InboxApplication).cornerSide
                val side by corner.side.collectAsStateWithLifecycle()
                CornerRow(side, corner::set)
            }
            SectionTitle("Inbox")
            Group {
                SettingRow(Icons.Outlined.Notifications, "Notifications", if (notifications.allowed) "On" else "Off", onClick = notifications.toggle)
                SwitchRow(
                    title = "Skip notifications for bundled mail",
                    checked = settings.quietBundles,
                    onChange = sending.account::setQuietBundles,
                    detail = "New mail that goes into a bundle arrives without a notification. All other mail still notifies.",
                    icon = Icons.Outlined.NotificationsPaused,
                    minHeight = 64.dp,
                    padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                )
                SettingRow(Icons.AutoMirrored.Outlined.Label, "Labels", null, onClick = onLabels)
                SettingRow(Icons.Outlined.FilterList, "Filters", null, onClick = onFilters)
                SwitchRow(
                    title = "Show todos",
                    checked = settings.showTodos,
                    onChange = sending.account::setShowTodos,
                    detail = "Todos sit among your mail and in a list of their own. They snooze and pin like mail, and can repeat.",
                    icon = Icons.Outlined.TaskAlt,
                    minHeight = 64.dp,
                    padding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                )
                val encryptionStatus by sending.encryption.status.collectAsStateWithLifecycle()
                val passkeys = settings.pgp?.sealed?.size ?: 0
                val encryptionValue = when (encryptionStatus) {
                    EncryptionStatus.UNLOCKED -> if (passkeys == 1) "1 passkey" else "$passkeys passkeys"
                    EncryptionStatus.LOCKED -> "Locked"
                    EncryptionStatus.LOADING -> null
                    else -> "Off"
                }
                SettingRow(Icons.Outlined.Lock, "Encryption", encryptionValue, onClick = onEncryption)
            }
            SectionTitle("Offline")
            Group { OfflineRows(savedMail, savedMailBytes) }
            SectionTitle("App")
            Group {
                SettingRow(Icons.AutoMirrored.Outlined.Logout, "Sign out", null, onClick = onSignOut)
                SettingRow(Icons.Outlined.DeleteForever, "Delete account", null, onClick = { deleting = true }, last = true)
            }
            ProjectLinks(
                onNoApp = sending::showNotice,
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth(),
            )
        }
    }
    if (deleting) DeleteAccountDialog(onDelete = onDeleteAccount, onDismiss = { deleting = false })
}

/** Deleting the account, once "delete" is typed; on success the app signs out and this goes with it. */
@Composable
private fun DeleteAccountDialog(onDelete: suspend () -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val confirmed = typed.trim().lowercase() == DELETE_WORD
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        containerColor = paper.card,
        title = { Text("Delete account", color = paper.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Deletes everything Inbox keeps about you: settings, snooze times, scheduled sends, push " +
                        "tokens, your @hafa.cc address and your encryption keys. It also ends Inbox's access to " +
                        "your Google account and clears this phone. Your mail stays in Gmail; snoozed threads go " +
                        "back to your inbox. This can't be undone.",
                    color = paper.textSecondary,
                )
                Text("Type $DELETE_WORD to confirm", color = paper.text)
                PlainField(typed, { typed = it }, "Type $DELETE_WORD to confirm", problem = false)
                ErrorLine(error)
            }
        },
        confirmButton = {
            if (working) {
                CircularProgressIndicator(color = paper.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            } else {
                TextButton(
                    enabled = confirmed,
                    onClick = {
                        working = true
                        error = null
                        scope.launch {
                            try {
                                onDelete()
                            } catch (failure: CancellationException) {
                                throw failure
                            } catch (failure: Exception) {
                                Log.w("Settings", "account deletion failed", failure)
                                error = "Couldn't delete the account. Check your connection and try again."
                                working = false
                            }
                        }
                    },
                ) { Text("Delete account", color = if (confirmed) paper.danger else paper.faint, fontWeight = FontWeight.SemiBold) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !working) { Text("Cancel", color = paper.textSecondary) } },
    )
}

@Composable
private fun SectionTitle(text: String) {
    SectionHeading(text, Modifier.padding(start = 4.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(paper.card),
    ) { content() }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    value: String?,
    onClick: (() -> Unit)? = null,
    alert: Boolean = false,
    last: Boolean = false,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .then(if (onClick != null) Modifier.rowHighlight().clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 12.dp),
    ) {
        IndicatorDot(show = alert, tone = DotTone.PROBLEM, ring = paper.card, round = true) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(36.dp).background(paper.muted, CircleShape),
            ) {
                Icon(icon, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(20.dp))
            }
        }
        Text(
            title,
            color = paper.text,
            modifier = Modifier.weight(1f).then(if (alert) Modifier.semantics { contentDescription = "$title. Your @hafa.cc address isn't set up" } else Modifier),
        )
        if (value != null) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                color = paper.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        if (onClick != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = paper.faint)
    }
    if (!last) HorizontalDivider(color = paper.separator, modifier = Modifier.padding(start = 68.dp))
}

/** Light, dark or the phone's, for this device only. */
@Composable
private fun ModeRow(current: ThemeMode, onPick: (ThemeMode) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Contrast, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(20.dp))
            Text("Light or dark", color = paper.text)
        }
        Text(
            "Set on this phone only.",
            style = MaterialTheme.typography.bodySmall,
            color = paper.textSecondary,
            modifier = Modifier.padding(start = 36.dp, top = 2.dp),
        )
        val options = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(start = 36.dp, top = 10.dp).fillMaxWidth()) {
            options.forEachIndexed { index, (mode, label) ->
                SegmentedButton(
                    selected = mode == current,
                    onClick = { onPick(mode) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = paper.accentSoft,
                        activeContentColor = paper.accentStrong,
                        activeBorderColor = paper.borderStrong,
                        inactiveContainerColor = paper.card,
                        inactiveContentColor = paper.textSecondary,
                        inactiveBorderColor = paper.borderStrong,
                    ),
                ) {
                    Text(label)
                }
            }
        }
    }
    HorizontalDivider(color = paper.separator)
}

/** Which bottom corner the round buttons sit in, for this device only; a drag on one moves them too. */
@Composable
private fun CornerRow(current: CornerSide, onPick: (CornerSide) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.PanTool, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(20.dp))
            Text("Round buttons", color = paper.text)
        }
        Text(
            "The bottom corner they sit in. Set on this phone only; you can also drag a button down, then across to the other corner.",
            style = MaterialTheme.typography.bodySmall,
            color = paper.textSecondary,
            modifier = Modifier.padding(start = 36.dp, top = 2.dp),
        )
        val options = listOf(CornerSide.LEFT to "Left", CornerSide.RIGHT to "Right")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.padding(start = 36.dp, top = 10.dp).fillMaxWidth()) {
            options.forEachIndexed { index, (side, label) ->
                SegmentedButton(
                    selected = side == current,
                    onClick = { onPick(side) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = paper.accentSoft,
                        activeContentColor = paper.accentStrong,
                        activeBorderColor = paper.borderStrong,
                        inactiveContainerColor = paper.card,
                        inactiveContentColor = paper.textSecondary,
                        inactiveBorderColor = paper.borderStrong,
                    ),
                ) {
                    Text(label)
                }
            }
        }
    }
    HorizontalDivider(color = paper.separator)
}

/** The account's color theme as a row of swatches; it follows the account to every device. */
@Composable
private fun ThemeRow(current: String, dark: Boolean, onPick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Outlined.Palette, contentDescription = null, tint = paper.textSecondary, modifier = Modifier.size(20.dp))
            Text("Theme", color = paper.text)
        }
        Text(
            "Follows your account to every device.",
            style = MaterialTheme.typography.bodySmall,
            color = paper.textSecondary,
            modifier = Modifier.padding(start = 36.dp, top = 2.dp),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .padding(start = 28.dp, top = 10.dp)
                .horizontalScroll(rememberScrollState()),
        ) {
            for (theme in ThemePalette.themes) {
                val colors = remember(theme.id, dark) { themeTokens(theme.id, dark) }
                val chosen = theme.id == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .rowHighlight(RoundedCornerShape(14.dp))
                        .selectable(selected = chosen, role = Role.RadioButton, onClick = { onPick(theme.id) })
                        .background(if (chosen) paper.muted else Color.Transparent)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(36.dp).background(colors.getValue("accent"), CircleShape),
                    ) {
                        if (chosen) {
                            Icon(Icons.Outlined.Check, contentDescription = null, tint = colors.getValue("on-accent"), modifier = Modifier.size(18.dp))
                        }
                    }
                    Text(
                        theme.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (chosen) paper.text else paper.textSecondary,
                        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
    HorizontalDivider(color = paper.separator)
}

@Composable
private fun DefaultFromRow(senders: List<Person>, current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingRow(Icons.Outlined.Person, "Default from", current, onClick = if (senders.size > 1) ({ open = true }) else null)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = paper.raised) {
            for (sender in senders) {
                DropdownMenuItem(
                    text = { Text(sender.email, color = paper.text) },
                    onClick = {
                        onPick(sender.email)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ImagesRow(current: ImagePolicy, onPick: (ImagePolicy) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val names = mapOf(ImagePolicy.ASK to "Ask first", ImagePolicy.ALWAYS to "Always show")
    Box {
        SettingRow(Icons.Outlined.Image, "Images", names.getValue(current), onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = paper.raised) {
            for ((policy, name) in names) {
                DropdownMenuItem(
                    text = { Text(name, color = paper.text) },
                    onClick = {
                        onPick(policy)
                        open = false
                    },
                )
            }
        }
    }
}

/** How many days of mail are saved besides the inbox, and how much is saved. */
@Composable
private fun OfflineRows(savedMail: OfflineCopy, bytesUsed: suspend () -> Long) {
    val info by savedMail.info.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var days by remember(info.days) { mutableFloatStateOf(info.days.toFloat()) }
    val bytes by produceState<Long?>(null, info.count) { value = runSuspendCatching { bytesUsed() }.getOrNull() }
    val shown = days.roundToInt()
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(
            "Saved mail opens without a connection. Your inbox, snoozed mail and drafts are always saved, and so " +
                "is encrypted mail, so search can find words inside it. Attachments and images open when you're back " +
                "online. The first download waits for Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium,
            color = paper.textSecondary,
        )
        Text(
            when {
                !info.loaded -> ""
                shown == 0 -> "No other mail"
                shown == 1 -> "Also the last day"
                else -> "Also the last $shown days"
            },
            color = paper.text,
            modifier = Modifier.padding(top = 12.dp),
        )
        Slider(
            value = days,
            onValueChange = { days = it },
            onValueChangeFinished = { scope.launch { savedMail.setDays(days.roundToInt()) } },
            enabled = info.loaded,
            valueRange = 0f..MAX_OFFLINE_DAYS.toFloat(),
            steps = MAX_OFFLINE_DAYS - 1,
            colors = SliderDefaults.colors(thumbColor = paper.accent, activeTrackColor = paper.accent),
            modifier = Modifier.semantics { contentDescription = "Days of mail saved" },
        )
        val count = if (info.count == 1) "1 thread saved" else "${info.count} threads saved"
        Text(
            bytes?.let { "$count, using ${formatSize(it)} on this phone" } ?: count,
            style = MaterialTheme.typography.bodyMedium,
            color = paper.textSecondary,
        )
    }
}

@Composable
private fun MaxShiftRow(stored: Int, onChange: (Int) -> Unit) {
    var minutes by remember(stored) { mutableFloatStateOf(stored.toFloat()) }
    val shown = minutes.roundToInt()
    Column(modifier = Modifier.fillMaxWidth().padding(start = 52.dp, end = 16.dp, bottom = 8.dp)) {
        Text(
            if (shown == 1) "Up to 1 minute" else "Up to $shown minutes",
            style = MaterialTheme.typography.bodyMedium,
            color = paper.textSecondary,
        )
        Slider(
            value = minutes,
            onValueChange = { minutes = it },
            onValueChangeFinished = { onChange(minutes.roundToInt()) },
            valueRange = SendSuggestions.MIN_MAX_SHIFT_MINUTES.toFloat()..SendSuggestions.MAX_MAX_SHIFT_MINUTES.toFloat(),
            steps = SendSuggestions.MAX_MAX_SHIFT_MINUTES - SendSuggestions.MIN_MAX_SHIFT_MINUTES - 1,
            colors = SliderDefaults.colors(thumbColor = paper.accent, activeTrackColor = paper.accent),
            modifier = Modifier.semantics { contentDescription = "Largest shift in minutes" },
        )
    }
}

/** Settings › Signature: the signature, saved as it is typed (as on the web), so Back keeps it. */
@Composable
internal fun SignatureScreen(sending: Sending, onBack: () -> Unit) {
    val settings by sending.account.settings.collectAsStateWithLifecycle()
    ScreenBackHandler(onBack = onBack)
    Column(modifier = Modifier.fillMaxSize().background(paper.card).imePadding()) {
        PlainTopBar("Signature", onBack)
        HorizontalDivider(color = paper.separator)
        Text(
            "Added below every message you write, after a \"-- \" line. *bold* and _italic_ work here too.",
            style = MaterialTheme.typography.bodySmall,
            color = paper.textSecondary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        // Typing waits for the saved signature, which it would otherwise be saved over.
        if (settings.loaded) SignatureField(sending, settings.signature.orEmpty())
    }
}

@Composable
private fun SignatureField(sending: Sending, saved: String) {
    var text by rememberSaveable { mutableStateOf(saved) }
    // Typed but not saved yet, which leaving saves at once.
    var unsaved by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(text)
    LaunchedEffect(text) {
        if (unsaved) {
            delay(SIGNATURE_SAVE_DELAY_MS)
            sending.account.setSignature(text)
            unsaved = false
        }
    }
    DisposableEffect(Unit) { onDispose { if (unsaved) sending.account.setSignature(latest) } }
    Box(modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) {
        if (text.isEmpty()) Text("No signature", color = paper.faint)
        BasicTextField(
            value = text,
            onValueChange = {
                if (it != text) unsaved = true
                text = it
            },
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = paper.text),
            cursorBrush = SolidColor(paper.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).semantics { contentDescription = "Signature" },
        )
    }
}
