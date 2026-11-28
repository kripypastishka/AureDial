package dev.auredial.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.auredial.domain.t9Matches
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val CallTypes = listOf(null, CallLog.Calls.MISSED_TYPE, CallLog.Calls.BLOCKED_TYPE)

@Composable
internal fun DirectoryPage(
    history: Boolean, revision: Int, dialer: Boolean, setup: CallSetup,
    onSetup: (CallSetup) -> Unit, request: () -> Unit, onDetails: (PhoneRow) -> Unit, onDial: () -> Unit,
) {
    val context = LocalContext.current
    val allowed = if (history) dialer && context.hasPermission(Manifest.permission.READ_CALL_LOG)
        else context.hasPermission(Manifest.permission.READ_CONTACTS)
    val pageTitle = stringResource(if (history) R.string.recents else R.string.contacts)
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<PhoneRow>?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(revision, allowed, history) {
        failed = false
        if (allowed) {
            val result = withContext(Dispatchers.IO) { runCatching { readPeople(context, history) } }
            rows = result.getOrNull()
            failed = result.isFailure
        } else {
            rows = null
        }
    }
    val visible = remember(rows, filter, query, history) {
        val term = query.trim()
        val callType = if (history) CallTypes[filter] else null
        rows.orEmpty().filter { row ->
            (history || filter == 0 || row.favorite) && (callType == null || row.callType == callType) &&
                (term.isEmpty() || row.name.orEmpty().contains(term, ignoreCase = true) ||
                    row.number.contains(term) || t9Matches(term, row.name, row.number))
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        item(key = "heading") {
            AureHeading(pageTitle, trailing = {
                AureAvatar(null, glyph = if (history) R.drawable.aure_recent else R.drawable.aure_contacts,
                    size = 49.dp)
            })
        }
        if (history && setup != CallSetup.READY) {
            item(key = "setup") { CallSetupCard(setup, onSetup) }
        }
        if (!allowed) {
            if (!history || dialer) {
                item(key = "permission") {
                    AureEmpty(R.drawable.aure_lock, stringResource(R.string.permissions),
                        stringResource(R.string.permission_note)) {
                        AureAction(stringResource(R.string.permissions), request, icon = R.drawable.aure_lock)
                    }
                }
            }
        } else {
            item(key = "search") {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.search)) }, singleLine = true,
                    shape = AureShapes.medium,
                    leadingIcon = { AureGlyph(R.drawable.aure_search) },
                    trailingIcon = if (query.isBlank()) null else ({
                        IconButton(onClick = { query = "" }) {
                            AureGlyph(R.drawable.aure_close, description = stringResource(R.string.clear_search))
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }
            item(key = "filters") {
                val labels = if (history) listOf(R.string.all_calls, R.string.missed_calls, R.string.blocked_calls)
                    else listOf(R.string.all_contacts, R.string.favorites)
                AureSegments(labels.map { stringResource(it) }, filter, { filter = it },
                    Modifier.horizontalScroll(rememberScrollState()))
            }
            when {
                failed -> item(key = "error") {
                    AureEmpty(R.drawable.aure_info, stringResource(R.string.action_failed))
                }
                rows == null -> item(key = "loading") {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                visible.isEmpty() -> item(key = "empty") {
                    val title = when {
                        query.isNotBlank() -> R.string.search_empty
                        !history && filter == 1 -> R.string.favorites_empty
                        !history -> R.string.contacts_empty
                        filter == 1 -> R.string.missed_empty
                        filter == 2 -> R.string.blocked_empty
                        else -> R.string.recents_empty
                    }
                    AureEmpty(if (history) R.drawable.aure_recent else R.drawable.aure_contacts, stringResource(title)) {
                        if (history && filter == 0 && query.isBlank()) {
                            AureAction(stringResource(R.string.dial), onDial, icon = R.drawable.aure_grid)
                        }
                    }
                }
                else -> items(visible, key = { it.key }, contentType = { "person" }) { row ->
                    DirectoryRow(row) { onDetails(row) }
                }
            }
        }
    }
}

@DrawableRes
private fun directionGlyph(type: Int?) = when (type) {
    CallLog.Calls.OUTGOING_TYPE -> R.drawable.aure_out
    CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> R.drawable.aure_miss
    CallLog.Calls.BLOCKED_TYPE -> R.drawable.aure_shield_alert
    else -> R.drawable.aure_in
}

@Composable
private fun DirectoryRow(row: PhoneRow, open: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val warning = row.callType == CallLog.Calls.MISSED_TYPE || row.callType == CallLog.Calls.BLOCKED_TYPE
    val title = row.name ?: row.number.ifBlank { stringResource(R.string.unknown_caller) }
    val description = row.detail.ifBlank { row.number.takeIf { row.name != null }.orEmpty() }
    val glyph = if (row.callType == null) R.drawable.aure_person else directionGlyph(row.callType)

    AureCard(modifier = Modifier.clip(AureShapes.large).pressable(onClick = open)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 74.dp).padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AureAvatar(
                row.name, glyph = glyph, color = if (warning) scheme.errorContainer else scheme.secondaryContainer,
                ink = if (warning) scheme.onErrorContainer else scheme.onSecondaryContainer,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (description.isNotEmpty()) Text(description, style = MaterialTheme.typography.bodySmall,
                    color = if (warning) scheme.error else scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (row.favorite) AureGlyph(R.drawable.aure_star, size = 17.dp, tint = scheme.primary,
                description = stringResource(R.string.favorite_contact))
            AureGlyph(R.drawable.aure_chevron, size = 17.dp, tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun NumberDetails(row: PhoneRow, store: RuleStore, onDismiss: () -> Unit, notify: (Int) -> Unit, onDial: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    var confirmBlock by remember(row.key) { mutableStateOf(false) }
    var saving by remember(row.key) { mutableStateOf(false) }
    fun open(intent: Intent) {
        if (runCatching { context.startActivity(intent) }.isSuccess) onDismiss() else notify(R.string.action_failed)
    }
    fun block() {
        saving = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val number = normalizeRuleNumber(context, row.number)
                            ?: return@runCatching R.string.invalid_number
                        if (store.update { it.withNumber(number, false) } == null) R.string.save_error
                        else R.string.block_saved
                    }.getOrDefault(R.string.save_error)
                }
                if (result == R.string.block_saved) onDismiss()
                notify(result)
            } finally {
                saving = false
            }
        }
    }

    AureSheet(onDismiss = { if (!saving) onDismiss() }) {
        AnimatedContent(confirmBlock, transitionSpec = {
            fadeIn(tween(170)).togetherWith(fadeOut(tween(100)))
        }, label = "Number actions") { confirming ->
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (confirming) {
                    AureAvatar(null, glyph = R.drawable.aure_shield_alert, color = scheme.errorContainer,
                        ink = scheme.onErrorContainer)
                    Text(stringResource(R.string.confirm_block), style = MaterialTheme.typography.headlineSmall)
                    Text(row.number, style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.confirm_block_note), color = scheme.onSurfaceVariant)
                    AureAction(stringResource(R.string.block), ::block, Modifier.fillMaxWidth(),
                        icon = R.drawable.aure_shield_alert, enabled = !saving, color = scheme.error, ink = scheme.onError)
                    AureAction(stringResource(R.string.cancel_action), { confirmBlock = false }, Modifier.fillMaxWidth(),
                        enabled = !saving, color = scheme.surfaceContainer, ink = scheme.onSurface)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AureAvatar(row.name, size = 62.dp)
                        Column(Modifier.weight(1f)) {
                            Text(row.name ?: row.number.ifBlank { stringResource(R.string.unknown_caller) },
                                style = MaterialTheme.typography.headlineSmall)
                            if (row.name != null && row.number.isNotBlank()) Text(row.number, color = scheme.onSurfaceVariant)
                        }
                        AureIconButton(R.drawable.aure_close, stringResource(R.string.close_details), onDismiss)
                    }
                    if (row.detail.isNotBlank()) Text(row.detail, color = scheme.onSurfaceVariant)
                    if (row.number.isNotBlank()) {
                        AureAction(stringResource(R.string.make_call), onDial, Modifier.fillMaxWidth(), R.drawable.aure_answer)
                        AureCard {
                            AureOption(stringResource(R.string.send_message), R.drawable.aure_chat, onClick = {
                                open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", row.number, null)))
                            })
                            val contact = row.contactUri
                            AureOption(stringResource(if (contact == null) R.string.save_contact else R.string.edit_contact),
                                R.drawable.aure_edit, onClick = {
                                    open(if (contact != null) Intent(Intent.ACTION_EDIT, contact)
                                    else Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                        .putExtra(ContactsContract.Intents.Insert.PHONE, row.number))
                                })
                            AureOption(stringResource(R.string.block), R.drawable.aure_shield_alert,
                                onClick = { confirmBlock = true }, tint = scheme.error)
                        }
                    }
                }
            }
        }
    }
}
