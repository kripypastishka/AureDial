package dev.auredial.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

internal enum class CallSetup(@field:StringRes val description: Int, @field:StringRes val action: Int?) {
    DEFAULT_APP(R.string.default_note, R.string.set_default),
    NOTIFICATIONS(R.string.call_alerts_needed, R.string.notifications),
    CHANNEL(R.string.call_channel_needed, R.string.incoming_call_settings),
    FULL_SCREEN(R.string.call_full_screen_needed, R.string.fullscreen),
    READY(R.string.call_setup_ready, null),
}

@Composable
internal fun PageTop(title: String, back: () -> Unit, closeLabel: String = stringResource(R.string.back)) {
    Row(
        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AureIconButton(R.drawable.aure_back, closeLabel, back)
        Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
    }
}

@Composable
internal fun SettingsPage(status: CallSetup, openCalls: () -> Unit, openVoice: () -> Unit, openRecording: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp),
    ) {
        AureHeading(stringResource(R.string.settings), trailing = {
            AureAvatar(null, glyph = R.drawable.aure_settings, size = 49.dp)
        })
        Text(stringResource(R.string.calls_group), style = MaterialTheme.typography.titleMedium)
        AureCard {
            AureOption(
                stringResource(R.string.receive_calls_title), R.drawable.aure_bell, openCalls,
                subtitle = stringResource(if (status == CallSetup.READY) R.string.setup_ready_short else R.string.setup_action_needed),
            )
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = scheme.outlineVariant)
            AureOption(stringResource(R.string.recording_title), R.drawable.aure_record, openRecording,
                subtitle = stringResource(R.string.recording_unavailable))
        }
        Text(stringResource(R.string.sound_group), style = MaterialTheme.typography.titleMedium)
        AureCard(color = scheme.primaryContainer) {
            AureOption(stringResource(R.string.voice_title), R.drawable.aure_wave, openVoice)
        }
    }
}

@Composable
internal fun CallSetupCard(status: CallSetup, onAction: (CallSetup) -> Unit, showTitle: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val ready = status == CallSetup.READY
    AureCard(color = if (ready) scheme.secondaryContainer else scheme.primaryContainer) {
        val tint = if (ready) scheme.onSecondaryContainer else scheme.onPrimaryContainer
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (showTitle) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    AureGlyph(if (ready) R.drawable.aure_check else R.drawable.aure_bell, tint = tint)
                    Text(stringResource(R.string.receive_calls_title), style = MaterialTheme.typography.titleMedium, color = tint)
                }
            } else {
                AureBadge(stringResource(if (ready) R.string.setup_ready_short else R.string.setup_action_needed),
                    if (ready) R.drawable.aure_check else R.drawable.aure_info, color = scheme.surface, ink = scheme.onSurface)
            }
            Text(stringResource(status.description), style = MaterialTheme.typography.bodyMedium, color = tint)
            status.action?.let {
                AureAction(stringResource(it), { onAction(status) }, Modifier.fillMaxWidth(), R.drawable.aure_chevron)
            }
        }
    }
}

@Composable
internal fun CallSetupPage(status: CallSetup, back: () -> Unit, configure: (CallSetup) -> Unit, requestIdentity: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageTop(stringResource(R.string.receive_calls_title), back)
        CallSetupCard(status, configure, showTitle = false)
        AureCard {
            AureOption(stringResource(R.string.identity_title), R.drawable.aure_sim, requestIdentity,
                subtitle = stringResource(R.string.identity_hint))
        }
    }
}

@Composable
internal fun RecordingPage(back: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var details by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageTop(stringResource(R.string.recording_title), back)
        AureCard {
            Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                AureAvatar(null, size = 57.dp, glyph = R.drawable.aure_record)
                Text(stringResource(R.string.recording_unavailable), style = MaterialTheme.typography.headlineSmall)
                AureAction(stringResource(R.string.recording_why), { details = true },
                    Modifier.fillMaxWidth(), R.drawable.aure_info, color = scheme.surfaceContainerHigh, ink = scheme.onSurface)
            }
        }
    }
    if (details) {
        AureSheet({ details = false }) {
            Text(stringResource(R.string.recording_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.recording_explain), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            AureAction(stringResource(R.string.close_details), { details = false }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
internal fun SettingsSwitch(
    @StringRes label: Int, checked: Boolean, enabled: Boolean = true, subtitle: String? = null,
    onChange: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(AureShapes.medium)
            .toggleable(value = checked, onValueChange = onChange, role = Role.Switch, enabled = enabled)
            .heightIn(min = 58.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(label), style = MaterialTheme.typography.titleSmall,
                color = if (enabled) scheme.onSurface else scheme.onSurfaceVariant)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            thumbContent = if (checked) ({ AureGlyph(R.drawable.aure_check, size = 14.dp) }) else null)
    }
}
