package dev.auredial.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Locale

class CallActivity : ComponentActivity() {
    private var showKeypad by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        showKeypad = savedInstanceState?.getBoolean("keypad") ?: intent.getBooleanExtra("dialpad", false)
        if (savedInstanceState == null) answerFromNotification(intent)
        setContent {
            AureTheme(onCall = true) {
                val ui by CallRuntime.state.collectAsStateWithLifecycle()
                LaunchedEffect(ui.calls.isEmpty()) {
                    if (ui.calls.isEmpty()) {
                        delay(350)
                        if (CallRuntime.state.value.calls.isEmpty()) finish()
                    }
                }
                BackHandler(enabled = showKeypad) { showKeypad = false }
                CallPresentation(ui, showKeypad, { showKeypad = !showKeypad }) {
                    startActivity(Intent(Intent.ACTION_DIAL).setClass(this, MainActivity::class.java))
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.hasExtra("dialpad")) showKeypad = intent.getBooleanExtra("dialpad", false)
        answerFromNotification(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("keypad", showKeypad)
        super.onSaveInstanceState(outState)
    }

    private fun answerFromNotification(intent: Intent) {
        if (intent.action != ACTION_ANSWER_CALL) return
        val id = intent.getStringExtra(EXTRA_ANSWER_CALL_ID) ?: return
        // This Activity is private. Each notification action identifies its own ringing call.
        intent.removeExtra(EXTRA_ANSWER_CALL_ID)
        CallRuntime.action(id, "answer")
    }
}

@Composable
private fun CallPresentation(ui: CallUi, keypad: Boolean, toggleKeypad: () -> Unit, addCall: () -> Unit) {
    val incoming = ui.calls.firstOrNull { isIncomingRinging(it.state) }
    val primary = ui.calls.firstOrNull { it.state == Call.STATE_ACTIVE } ?: ui.calls.firstOrNull()
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        if (primary == null) return@Surface
        AnimatedContent(
            incoming != null, transitionSpec = {
                (fadeIn(tween(220)) + scaleIn(tween(235), initialScale = 0.985f))
                    .togetherWith(fadeOut(tween(130)))
            },
            label = "Call connection",
        ) { ringing ->
            if (ringing) {
                val call = incoming ?: primary
                IncomingCallScreen(call, ui.calls.firstOrNull { it.id != call.id })
            } else {
                OngoingCallScreen(primary, ui, keypad, toggleKeypad, addCall)
            }
        }
    }
}

@Composable
private fun OngoingCallScreen(call: CallView, ui: CallUi, keypad: Boolean, toggleKeypad: () -> Unit, addCall: () -> Unit) {
    val active = call.state == Call.STATE_ACTIVE
    val holding = call.state == Call.STATE_HOLDING
    val chooseSim = call.state == Call.STATE_SELECT_PHONE_ACCOUNT
    val stateLabel = when (call.state) {
        Call.STATE_ACTIVE -> R.string.ongoing_call
        Call.STATE_HOLDING -> R.string.hold
        Call.STATE_SELECT_PHONE_ACCOUNT -> R.string.choose_sim
        Call.STATE_DISCONNECTING -> R.string.call_ending
        else -> R.string.outgoing_call
    }

    CallFrame { compact ->
        val scroll = rememberScrollState()
        LaunchedEffect(call.id, keypad, compact) {
            if (keypad && compact) {
                withFrameNanos { }
                scroll.scrollTo(scroll.maxValue)
            }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CallStatusBar(stringResource(stateLabel), active || holding, call)
            CallIdentity(call, ringing = false)
            ui.calls.filter { it.id != call.id }.forEach { SecondaryCall(it) }
            if (chooseSim) {
                AureCard {
                    if (call.accounts.isEmpty()) {
                        Text(stringResource(R.string.no_sim), Modifier.padding(14.dp))
                    }
                    call.accounts.forEach { (account, label) ->
                        AureOption(label, R.drawable.aure_sim, onClick = { CallRuntime.selectAccount(call.id, account) })
                    }
                }
            }
            if (compact && (active || holding)) {
                CallDock(call, ui, keypad, toggleKeypad, addCall)
            }
        }
        if (!compact && (active || holding)) {
            CallDock(call, ui, keypad, toggleKeypad, addCall)
        }
        AureAction(
            stringResource(R.string.end_call), { CallRuntime.action(call.id, "end") },
            Modifier.fillMaxWidth().heightIn(min = 57.dp), R.drawable.aure_decline,
            enabled = call.state != Call.STATE_DISCONNECTING,
            color = Color(0xFFFFAEA8), ink = Color(0xFF481C1E),
        )
    }
}

@Composable
internal fun CallFrame(content: @Composable ColumnScope.(Boolean) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        val compact = maxHeight < 650.dp || LocalDensity.current.fontScale > 1.23f
        Column(
            Modifier.widthIn(max = 525.dp).fillMaxSize().padding(horizontal = 19.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp),
        ) { content(compact) }
    }
}

@Composable
internal fun CallStatusBar(label: String, connected: Boolean, call: CallView) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            val icon = when {
                call.state == Call.STATE_HOLDING -> R.drawable.aure_pause
                connected -> R.drawable.aure_wave
                isIncomingRinging(call.state) -> R.drawable.aure_in
                else -> R.drawable.aure_out
            }
            AureBadge(label, icon, color = scheme.surfaceVariant, ink = scheme.onSurface)
        }
        if (connected && call.connectedAt > 0) CallTimer(call)
    }
}

@Composable
internal fun CallIdentity(call: CallView, ringing: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val warning = call.verificationFailed
    val accent = if (warning) scheme.error else scheme.primary
    val name = call.name ?: if (ringing) null else call.number
    val title = name ?: stringResource(R.string.unknown_caller)
    AureCard(color = scheme.surface) {
        Box(Modifier.fillMaxWidth()) {
            CallSignal(Modifier.matchParentSize(), accent, ringing)
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AureAvatar(
                    call.name, size = 59.dp,
                    glyph = if (warning) R.drawable.aure_shield_alert else R.drawable.aure_person,
                    color = if (warning) scheme.errorContainer else scheme.primaryContainer,
                    ink = if (warning) scheme.onErrorContainer else scheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(17.dp))
                Text(title, style = MaterialTheme.typography.displaySmall,
                    maxLines = 3, overflow = TextOverflow.Ellipsis, color = scheme.onSurface)
                val number = call.number?.takeIf { it != title }
                val metadata = listOfNotNull(number, call.accountLabel).joinToString(" · ")
                if (metadata.isNotBlank()) Text(metadata, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                if (warning) {
                    AureBadge(stringResource(R.string.number_verification_failed), R.drawable.aure_shield_alert,
                        color = scheme.errorContainer, ink = scheme.onErrorContainer)
                } else if (call.fromContacts) {
                    AureBadge(stringResource(R.string.from_contacts), R.drawable.aure_shield_check)
                }
            }
        }
    }
}

@Composable
private fun CallTimer(call: CallView) {
    var seconds by remember(call.id, call.connectedAt) {
        mutableLongStateOf(((System.currentTimeMillis() - call.connectedAt) / 1000).coerceAtLeast(0))
    }
    LaunchedEffect(call.id, call.connectedAt) {
        val startedHere = SystemClock.elapsedRealtime()
        val initialMillis = (System.currentTimeMillis() - call.connectedAt).coerceAtLeast(0)
        while (true) {
            val elapsed = initialMillis + SystemClock.elapsedRealtime() - startedHere
            seconds = elapsed / 1000
            delay(1_000L - (elapsed % 1000))
        }
    }
    Text(
        String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60),
        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.secondary,
    )
}

@Composable
private fun SecondaryCall(call: CallView) {
    val scheme = MaterialTheme.colorScheme
    AureCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(call.name ?: call.number ?: stringResource(R.string.unknown_caller),
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (call.state == Call.STATE_HOLDING) {
                    AureAction(stringResource(R.string.resume_call), { CallRuntime.action(call.id, "hold") },
                        Modifier.weight(1f), R.drawable.aure_play)
                }
                AureAction(stringResource(R.string.end_call), { CallRuntime.action(call.id, "end") },
                    Modifier.weight(1f), R.drawable.aure_decline, color = scheme.errorContainer, ink = scheme.onErrorContainer)
            }
        }
    }
}

@Composable
private fun CallDock(call: CallView, ui: CallUi, keypad: Boolean, toggleKeypad: () -> Unit, addCall: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val active = call.state == Call.STATE_ACTIVE
    val held = call.state == Call.STATE_HOLDING
    var routesOpen by remember(call.id) { mutableStateOf(false) }

    AureCard {
        AnimatedContent(
            active && keypad, transitionSpec = {
                (fadeIn(tween(180, delayMillis = 30)) + slideInVertically(tween(220)) { it / 12 })
                    .togetherWith(fadeOut(tween(110)) + slideOutVertically(tween(140)) { -it / 14 })
            },
            label = "Call keypad",
        ) { showDigits ->
            Column(Modifier.fillMaxWidth().padding(9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                if (showDigits) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 5.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(stringResource(R.string.call_keypad), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        AureIconButton(R.drawable.aure_close, stringResource(R.string.close_keypad), toggleKeypad)
                    }
                    DigitGrid { digit -> CallRuntime.action(call.id, "dtmf:" + digit) }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        CallTile(
                            stringResource(if (ui.muted) R.string.unmute else R.string.mute),
                            if (ui.muted) R.drawable.aure_mic_off else R.drawable.aure_mic,
                            Modifier.weight(1f), active, selected = ui.muted,
                        ) { CallRuntime.action(call.id, "mute") }
                        CallTile(stringResource(R.string.dial), R.drawable.aure_grid, Modifier.weight(1f), active,
                            onClick = toggleKeypad)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        val current = ui.routes.firstOrNull { it.key == ui.currentRoute }
                        CallTile(
                            stringResource(R.string.audio_output), R.drawable.aure_sound, Modifier.weight(1f),
                            enabled = active && ui.routes.isNotEmpty(), subtitle = current?.name,
                        ) { routesOpen = true }
                        CallTile(
                            stringResource(if (held) R.string.resume_call else R.string.hold),
                            if (held) R.drawable.aure_play else R.drawable.aure_pause, Modifier.weight(1f),
                            enabled = held || call.canHold, selected = held,
                        ) { CallRuntime.action(call.id, "hold") }
                    }
                    if (ui.canAddCall || call.canMerge) {
                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            if (ui.canAddCall) {
                                AureAction(stringResource(R.string.add_call), addCall, Modifier.weight(1f),
                                    R.drawable.aure_plus, enabled = active, color = scheme.surfaceContainer, ink = scheme.onSurface)
                            }
                            if (call.canMerge) {
                                AureAction(stringResource(R.string.merge_calls), { CallRuntime.action(call.id, "merge") },
                                    Modifier.weight(1f), R.drawable.aure_merge, enabled = active, color = scheme.surfaceContainer, ink = scheme.onSurface)
                            }
                        }
                    }
                }
            }
        }
    }
    if (routesOpen && active) {
        AureChoiceSheet(
            stringResource(R.string.audio_output), ui.currentRoute,
            ui.routes.map { SheetChoice(it.key, it.name, R.drawable.aure_sound) },
            onDismiss = { routesOpen = false },
            onChoose = { key -> if (key != null && key != ui.currentRoute) CallRuntime.route(key) },
        )
    }
}

@Composable
private fun CallTile(
    label: String, @DrawableRes glyph: Int, modifier: Modifier, enabled: Boolean,
    selected: Boolean = false, subtitle: String? = null, onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val color by animateColorAsState(
        if (selected) scheme.primaryContainer else scheme.surfaceContainer, tween(190), label = "Call control selected",
    )
    val ink = when {
        !enabled -> scheme.onSurfaceVariant.copy(alpha = 0.65f)
        selected -> scheme.onPrimaryContainer
        else -> scheme.onSurface
    }
    Row(
        modifier.clip(AureShapes.medium).background(color)
            .pressable(enabled, onClick = onClick).semantics { this.selected = selected }
            .heightIn(min = 60.dp).padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AureGlyph(glyph, size = 22.dp, tint = if (enabled) scheme.primary else ink)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
