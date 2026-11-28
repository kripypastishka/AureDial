package dev.auredial.app

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.telecom.Call
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import java.util.Locale

class CallActivity : ComponentActivity() {
    private var showKeypad by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        showKeypad = savedInstanceState?.getBoolean("keypad") ?: intent.getBooleanExtra("dialpad", false)
        if (savedInstanceState == null) answerFromNotification(intent)
        setContent {
            AureTheme {
                val ui by CallRuntime.state.collectAsStateWithLifecycle()
                LaunchedEffect(ui.calls.isEmpty()) {
                    if (ui.calls.isEmpty()) {
                        delay(350)
                        if (CallRuntime.state.value.calls.isEmpty()) finish()
                    }
                }
                Surface(Modifier.fillMaxSize()) {
                    val incoming = ui.calls.firstOrNull { isIncomingRinging(it.state) }
                    val primary = ui.calls.firstOrNull { it.state == Call.STATE_ACTIVE } ?: ui.calls.firstOrNull()
                    if (incoming != null) {
                        IncomingCallScreen(incoming, ui.calls.firstOrNull { it.id != incoming.id })
                    } else if (primary != null) {
                        OngoingCallScreen(primary, ui, showKeypad, { showKeypad = !showKeypad }) {
                            startActivity(Intent(Intent.ACTION_DIAL).setClass(this, MainActivity::class.java))
                        }
                    }
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
private fun OngoingCallScreen(
    call: CallView,
    ui: CallUi,
    keypad: Boolean,
    toggleKeypad: () -> Unit,
    onAdd: () -> Unit,
) {
    val active = call.state == Call.STATE_ACTIVE
    val holding = call.state == Call.STATE_HOLDING
    val colors = MaterialTheme.colorScheme
    val title = call.name ?: call.number ?: stringResource(R.string.unknown_caller)
    val stateLabel = when (call.state) {
        Call.STATE_ACTIVE -> R.string.ongoing_call
        Call.STATE_HOLDING -> R.string.hold
        Call.STATE_SELECT_PHONE_ACCOUNT -> R.string.choose_sim
        Call.STATE_DISCONNECTING -> R.string.call_ending
        else -> R.string.outgoing_call
    }

    Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(stateLabel), style = MaterialTheme.typography.titleSmall, color = colors.primary)
                Surface(Modifier.size(76.dp), shape = CircleShape, color = colors.primaryContainer) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(painterResource(R.drawable.aure_person), null, Modifier.size(36.dp), tint = colors.onPrimaryContainer)
                    }
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                val detail = listOfNotNull(call.number.takeIf { call.name != null }, call.accountLabel).joinToString(" · ")
                if (detail.isNotBlank()) Text(detail, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                if ((active || holding) && call.connectedAt > 0) CallTimer(call)

                ui.calls.filter { it.id != call.id }.forEach { OtherCall(it) }
                if (call.state == Call.STATE_SELECT_PHONE_ACCOUNT) {
                    if (call.accounts.isEmpty()) Text(stringResource(R.string.no_sim))
                    call.accounts.forEach { (account, label) ->
                        OutlinedButton(onClick = { CallRuntime.selectAccount(call.id, account) }, modifier = Modifier.fillMaxWidth()) {
                            Text(label)
                        }
                    }
                }
                if (active || holding) {
                    CallActions(call, ui, keypad, toggleKeypad, onAdd)
                }
                AnimatedVisibility(visible = active && keypad, enter = fadeIn(), exit = fadeOut()) {
                    DtmfPad(call.id)
                }
            }

            Button(
                onClick = { CallRuntime.action(call.id, "end") },
                enabled = call.state != Call.STATE_DISCONNECTING,
                modifier = Modifier.padding(top = 12.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB52D40), contentColor = Color.White),
            ) {
                Icon(painterResource(R.drawable.aure_decline), null, Modifier.size(21.dp))
                Spacer(Modifier.width(9.dp))
                Text(stringResource(R.string.end_call))
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
    Text(String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60),
        style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OtherCall(call: CallView) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(call.name ?: call.number ?: stringResource(R.string.unknown_caller), Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (call.state == Call.STATE_HOLDING) {
                TextButton(onClick = { CallRuntime.action(call.id, "hold") }) { Text(stringResource(R.string.resume_call)) }
            }
            TextButton(onClick = { CallRuntime.action(call.id, "end") }) { Text(stringResource(R.string.end_call)) }
        }
    }
}

@Composable
private fun CallActions(call: CallView, ui: CallUi, keypad: Boolean, toggleKeypad: () -> Unit, onAdd: () -> Unit) {
    val active = call.state == Call.STATE_ACTIVE
    val holding = call.state == Call.STATE_HOLDING
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { CallRuntime.action(call.id, "mute") }, enabled = active, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (ui.muted) R.string.unmute else R.string.mute), textAlign = TextAlign.Center)
                }
                FilledTonalButton(onClick = toggleKeypad, enabled = active, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (keypad) R.string.close_keypad else R.string.dial), textAlign = TextAlign.Center)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RouteMenu(call.id, ui, active, Modifier.weight(1f))
                FilledTonalButton(onClick = { CallRuntime.action(call.id, "hold") },
                    enabled = holding || call.canHold, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (holding) R.string.resume_call else R.string.hold), textAlign = TextAlign.Center)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.canAddCall) {
                    TextButton(onClick = onAdd, enabled = active, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.add_call), textAlign = TextAlign.Center)
                    }
                }
                if (call.canMerge) {
                    TextButton(onClick = { CallRuntime.action(call.id, "merge") }, enabled = active, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.merge_calls), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

@Composable
private fun RouteMenu(callId: String, ui: CallUi, active: Boolean, modifier: Modifier) {
    var expanded by remember(callId) { mutableStateOf(false) }
    Box(modifier) {
        val current = ui.routes.firstOrNull { it.key == ui.currentRoute }
        FilledTonalButton(
            onClick = { expanded = true }, enabled = active && ui.routes.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(current?.name ?: stringResource(R.string.audio_output), maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        DropdownMenu(expanded = expanded && active, onDismissRequest = { expanded = false }) {
            ui.routes.forEach { route ->
                DropdownMenuItem(text = { Text(route.name) }, enabled = route.key != ui.currentRoute,
                    onClick = { expanded = false; CallRuntime.route(route.key) })
            }
        }
    }
}

@Composable
private fun DtmfPad(callId: String) {
    Column(Modifier.widthIn(max = 360.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("123", "456", "789", "*0#").forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { digit ->
                    FilledTonalButton(onClick = { CallRuntime.action(callId, "dtmf:$digit") },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(digit.toString()) }
                }
            }
        }
    }
}
