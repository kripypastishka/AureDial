package dev.auredial.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
internal fun VoicePage(onError: (Int) -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) { voicePreferences(context) }
    var settings by remember(context) { mutableStateOf(readVoiceSettings(context)) }
    var advanced by rememberSaveable { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    var readyPlayer by remember(context, settings.selection, generation) { mutableStateOf<VoicePreview?>(null) }
    DisposableEffect(context, settings.selection, generation) {
        val opened = VoicePreview(context, selection = settings.selection)
        readyPlayer = opened
        onDispose { opened.close() }
    }
    val player = readyPlayer ?: return
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { player.stop() }
    val status by player.state.collectAsStateWithLifecycle()
    val engines by player.engineOptions.collectAsStateWithLifecycle()
    val voices by player.voiceOptions.collectAsStateWithLifecycle()

    fun saveSelection(selection: VoiceSelection) {
        preferences.edit {
            putString("engine", selection.engine)
            putString("voice_language", selection.language)
            putString("voice_name", selection.voice)
        }
        settings = settings.copy(selection = selection)
    }

    fun savePercent(key: String, value: Int) {
        preferences.edit { putInt(key, value) }
        settings = readVoiceSettings(context)
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.voice_title), style = MaterialTheme.typography.titleLarge)
            SettingsSwitch(R.string.announce_enabled, settings.enabled) {
                preferences.edit { putBoolean("announce", it) }
                settings = settings.copy(enabled = it)
            }
            SettingsSwitch(R.string.contacts_only, settings.contactsOnly, settings.enabled) {
                preferences.edit { putBoolean("contacts_only", it) }
                settings = settings.copy(contactsOnly = it)
            }
            Text(stringResource(R.string.announce_limit), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
            if (status == R.string.voice_unavailable) {
                TextButton(onClick = { if (!player.openVoiceInstaller()) onError(R.string.open_tts_manually) }) {
                    Text(stringResource(R.string.install_voice))
                }
                TextButton(onClick = { generation++ }) { Text(stringResource(R.string.voice_reload)) }
            }
            if (status == R.string.voice_playing) {
                OutlinedButton(onClick = player::stop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.stop))
                }
            } else {
                Button(onClick = { player.play(settings) }, enabled = status != R.string.voice_loading && status != R.string.voice_unavailable,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.preview)) }
            }
            TextButton(onClick = { advanced = !advanced }) {
                Text(stringResource(if (advanced) R.string.voice_fewer_settings else R.string.voice_more_settings))
            }
            if (advanced) {
                HorizontalDivider()
                VoiceChoices(R.string.voice_engine, R.string.voice_system_default, engines, settings.selection.engine) {
                    saveSelection(settings.selection.copy(engine = it, voice = null))
                }
                val languages = listOf(VoiceOption("ru-RU", stringResource(R.string.voice_russian)),
                    VoiceOption("en-US", stringResource(R.string.voice_english)))
                VoiceChoices(R.string.voice_language, R.string.voice_system_default, languages, settings.selection.language) {
                    saveSelection(settings.selection.copy(language = it, voice = null))
                }
                VoiceChoices(R.string.voice_actor, R.string.voice_automatic, voices, settings.selection.voice) {
                    saveSelection(settings.selection.copy(voice = it))
                }
                VoiceSlider(R.string.voice_rate, settings.rate, 75, 125, 9) { savePercent("rate", it) }
                VoiceSlider(R.string.voice_pitch, settings.pitch, 75, 125, 9) { savePercent("pitch", it) }
                VoiceSlider(R.string.voice_volume, settings.volume, 10, 100, 8) { savePercent("volume", it) }
                VoiceSlider(R.string.voice_delay, settings.timing.delayMs.toInt(), 350, 4_000, 0) { savePercent("delay_ms", it) }
                VoiceSlider(R.string.voice_count, settings.timing.count, 1, 3, 1) { savePercent("count", it) }
                if (settings.timing.count > 1) {
                    VoiceSlider(R.string.voice_interval, settings.timing.intervalMs.toInt(), 2_000, 7_000, 9) {
                        savePercent("interval_ms", it)
                    }
                }
                SettingsSwitch(R.string.voice_identity_only, settings.identityOnly) {
                    preferences.edit { putBoolean("identity_only", it) }
                    settings = settings.copy(identityOnly = it)
                }
            }
        }
    }
}

@Composable
private fun VoiceChoices(
    @StringRes label: Int,
    @StringRes defaultOption: Int,
    options: List<VoiceOption>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val automatic = stringResource(defaultOption)
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(options.firstOrNull { it.key == selected }?.label ?: selected ?: automatic,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(automatic) }, onClick = { expanded = false; onSelect(null) })
                options.forEach { item ->
                    DropdownMenuItem(text = { Text(item.label) }, onClick = { expanded = false; onSelect(item.key) })
                }
            }
        }
    }
}

@Composable
private fun VoiceSlider(@StringRes label: Int, value: Int, start: Int, end: Int, steps: Int, onCommit: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column {
        Text(stringResource(label, current.roundToInt()), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = current, valueRange = start.toFloat()..end.toFloat(), steps = steps,
            onValueChange = { current = it }, onValueChangeFinished = { onCommit(current.roundToInt()) },
        )
    }
}

@Composable
internal fun RecordingStatusCard() {
    var details by rememberSaveable { mutableStateOf(false) }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.recording_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.recording_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = { details = true }) { Text(stringResource(R.string.recording_why)) }
        }
    }
    if (details) {
        AlertDialog(
            onDismissRequest = { details = false },
            title = { Text(stringResource(R.string.recording_title)) },
            text = { Text(stringResource(R.string.recording_explain)) },
            confirmButton = {
                TextButton(onClick = { details = false }) { Text(stringResource(R.string.close_details)) }
            },
        )
    }
}
