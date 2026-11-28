package dev.auredial.app

import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.auredial.domain.Rules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ProtectionPage(store: RuleStore, held: Boolean, requestRole: () -> Unit, notify: (Int) -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var rules by remember(store) { mutableStateOf<Rules?>(null) }
    var saving by remember { mutableStateOf(false) }
    var entry by rememberSaveable { mutableStateOf("") }
    var listIndex by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(store) {
        rules = withContext(Dispatchers.IO) { runCatching { store.read() }.getOrNull() }
        if (rules == null) notify(R.string.save_error)
    }
    fun update(transform: (Rules) -> Rules) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { runCatching { store.update(transform) }.getOrNull() }
                if (updated != null) rules = updated else notify(R.string.save_error)
            } finally {
                saving = false
            }
        }
    }
    fun add(allowed: Boolean) {
        val number = normalizeRuleNumber(context, entry)
        if (number == null) notify(R.string.invalid_number)
        else {
            update { it.withNumber(number, allowed) }
            listIndex = if (allowed) 1 else 0
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "heading") {
            AureHeading(stringResource(R.string.protect), trailing = {
                AureAvatar(null, glyph = R.drawable.aure_shield, size = 49.dp)
            })
        }
        val current = rules
        item(key = "status") {
            AureCard(color = scheme.secondaryContainer) {
                Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val status = when {
                        !held -> R.string.role_missing
                        current?.enabled == true -> R.string.protection_on
                        else -> R.string.protection_off
                    }
                    AureBadge(
                        stringResource(status), icon = if (held) R.drawable.aure_shield_check else R.drawable.aure_shield_alert,
                        color = scheme.surface, ink = scheme.onSurface,
                    )
                    Text(stringResource(R.string.rules_title), style = MaterialTheme.typography.headlineSmall,
                        color = scheme.onSecondaryContainer)
                    Text(stringResource(R.string.rules_note), color = scheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodyMedium)
                    if (!held) {
                        AureAction(stringResource(R.string.request_role), requestRole,
                            modifier = Modifier.fillMaxWidth(), icon = R.drawable.aure_shield_check,
                            color = scheme.secondary, ink = scheme.onSecondary)
                    }
                }
            }
        }
        if (current != null) {
            item(key = "switches") {
                AureCard {
                    SettingsSwitch(R.string.enable_rules, current.enabled, enabled = !saving) { enabled ->
                        update { it.copy(enabled = enabled) }
                    }
                    if (Build.VERSION.SDK_INT >= 30) {
                        HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = scheme.outlineVariant)
                        SettingsSwitch(R.string.silence_failed, current.silenceFailedVerification,
                            enabled = current.enabled && !saving, subtitle = stringResource(R.string.silence_failed_note)) { enabled ->
                            update { it.copy(silenceFailedVerification = enabled) }
                        }
                    }
                }
            }
            item(key = "number") {
                AureCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.add_rule), style = MaterialTheme.typography.titleMedium)
                        OutlinedTextField(
                            entry, { entry = it.take(64) }, label = { Text(stringResource(R.string.phone_number)) },
                            placeholder = { Text("+") }, singleLine = true, shape = AureShapes.small,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            AureAction(stringResource(R.string.block), { add(false) }, Modifier.weight(1f),
                                icon = R.drawable.aure_shield_alert, enabled = !saving,
                                color = scheme.errorContainer, ink = scheme.onErrorContainer)
                            AureAction(stringResource(R.string.allow), { add(true) }, Modifier.weight(1f),
                                icon = R.drawable.aure_check, enabled = !saving,
                                color = scheme.secondaryContainer, ink = scheme.onSecondaryContainer)
                        }
                    }
                }
            }
            item(key = "lists") {
                Text(stringResource(R.string.rules_lists), style = MaterialTheme.typography.titleLarge)
            }
            item(key = "list-picker") {
                AureSegments(
                    listOf(stringResource(R.string.block_list), stringResource(R.string.allow_list)),
                    listIndex, { listIndex = it }, Modifier.horizontalScroll(rememberScrollState()),
                )
            }
            val allowedList = listIndex == 1
            val numbers = if (allowedList) current.allowed else current.blocked
            if (numbers.isEmpty()) {
                item(key = "empty") { AureEmpty(R.drawable.aure_shield, stringResource(R.string.empty_list)) }
            }
            items(numbers.sorted(), key = { it }, contentType = { "number" }) { number ->
                AureCard {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        AureAvatar(null, glyph = if (allowedList) R.drawable.aure_shield_check else R.drawable.aure_shield_alert,
                            size = 42.dp)
                        Text(number, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        AureIconButton(R.drawable.aure_trash, stringResource(R.string.remove_number, number), enabled = !saving,
                            onClick = {
                                update { if (allowedList) it.copy(allowed = it.allowed - number) else it.copy(blocked = it.blocked - number) }
                            })
                    }
                }
            }
        }
    }
}
