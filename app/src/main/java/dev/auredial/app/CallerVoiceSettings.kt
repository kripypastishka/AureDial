package dev.auredial.app

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt

@Composable
internal fun VoicePage(back: () -> Unit, onError: (Int) -> Unit) {
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
    val player = readyPlayer
    if (player == null) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PageTop(stringResource(R.string.voice_title), back)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        return
    }
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

    val scheme = MaterialTheme.colorScheme
    val playing = status == R.string.voice_playing
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PageTop(stringResource(R.string.voice_title), back)
        AureCard(color = scheme.primaryContainer) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AureBadge(
                    stringResource(status), icon = if (playing) R.drawable.aure_sound else R.drawable.aure_wave,
                    color = scheme.surface, ink = scheme.onSurface,
                )
                VoiceWave(Modifier.fillMaxWidth().height(64.dp), scheme.onPrimaryContainer, playing)
                AureAction(
                    stringResource(if (playing) R.string.stop else R.string.preview),
                    onClick = { if (playing) player.stop() else player.play(settings) },
                    modifier = Modifier.fillMaxWidth(), icon = if (playing) R.drawable.aure_stop else R.drawable.aure_play,
                    enabled = status != R.string.voice_loading && status != R.string.voice_unavailable,
                )
                if (status == R.string.voice_unavailable) {
                    AureOption(stringResource(R.string.install_voice), R.drawable.aure_sound, onClick = {
                        if (!player.openVoiceInstaller()) onError(R.string.open_tts_manually)
                    })
                    AureOption(stringResource(R.string.voice_reload), R.drawable.aure_check, onClick = { generation++ })
                }
            }
        }
        AureCard {
            SettingsSwitch(R.string.announce_enabled, settings.enabled) {
                preferences.edit { putBoolean("announce", it) }
                settings = settings.copy(enabled = it)
            }
            HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = scheme.outlineVariant)
            SettingsSwitch(R.string.contacts_only, settings.contactsOnly, settings.enabled) {
                preferences.edit { putBoolean("contacts_only", it) }
                settings = settings.copy(contactsOnly = it)
            }
        }
        Text(stringResource(R.string.announce_limit), color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
        AureCard {
            AureOption(stringResource(if (advanced) R.string.voice_fewer_settings else R.string.voice_more_settings),
                R.drawable.aure_settings, onClick = { advanced = !advanced }, selected = advanced)
        }
        AnimatedVisibility(
            visible = advanced,
            enter = expandVertically(tween(240)) + fadeIn(tween(180, 45)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120)),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.voice_sound_heading), style = MaterialTheme.typography.headlineSmall)
                AureCard {
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
                }
                Text(stringResource(R.string.voice_timing_heading), style = MaterialTheme.typography.headlineSmall)
                AureCard {
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
}

@Composable
private fun VoiceChoices(
    @StringRes label: Int, @StringRes defaultOption: Int, options: List<VoiceOption>,
    selected: String?, onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val title = stringResource(label)
    val automatic = stringResource(defaultOption)
    AureOption(title, R.drawable.aure_wave, onClick = { expanded = true },
        subtitle = options.firstOrNull { it.key == selected }?.label ?: selected ?: automatic)
    if (expanded) {
        val choices = listOf(SheetChoice(null, automatic, R.drawable.aure_wave)) +
            options.map { SheetChoice(it.key, it.label, R.drawable.aure_wave) }
        AureChoiceSheet(title, selected, choices, { expanded = false }, onSelect)
    }
}

@Composable
private fun VoiceSlider(@StringRes label: Int, value: Int, start: Int, end: Int, steps: Int, onCommit: (Int) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    val description = stringResource(label, current.roundToInt())
    Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
        Text(description, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = current, valueRange = start.toFloat()..end.toFloat(), steps = steps,
            onValueChange = { current = it }, onValueChangeFinished = { onCommit(current.roundToInt()) },
        )
    }
}
