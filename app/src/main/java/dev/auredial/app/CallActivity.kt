package dev.auredial.app

import android.content.Intent
import android.os.Bundle
import android.telecom.Call
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay

class CallActivity: ComponentActivity() {
    private val showKeypad=mutableStateOf(false)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true); setTurnScreenOn(true)
        showKeypad.value=intent.getBooleanExtra("dialpad",false)
        setContent {
            AureTheme {
                val ui by CallRuntime.state.collectAsStateWithLifecycle()
                LaunchedEffect(ui.calls.isEmpty()) { if(ui.calls.isEmpty()) { delay(350); if(CallRuntime.state.value.calls.isEmpty()) finish() } }
                Surface(Modifier.fillMaxSize()) {
                    val call=ui.calls.firstOrNull { it.state==Call.STATE_RINGING } ?: ui.calls.firstOrNull { it.state==Call.STATE_ACTIVE } ?: ui.calls.firstOrNull()
                    if(call!=null) CallScreen(call,ui,showKeypad.value,{showKeypad.value=!showKeypad.value},accounts(this),
                        onAdd={startActivity(Intent(this,MainActivity::class.java).putExtra("open_dial",true))},
                        onSilence={getSystemService(TelecomManager::class.java).silenceRinger()})
                }
            }
        }
    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent); setIntent(intent); showKeypad.value=intent.getBooleanExtra("dialpad",false) }
    override fun onResume() { super.onResume(); CallRuntime.visible(true) }
    override fun onPause() { CallRuntime.visible(false); super.onPause() }
}

@Composable private fun CallScreen(call:CallView,ui:CallUi,keypad:Boolean,toggleKeypad:()->Unit,
                                    accounts:List<Pair<android.telecom.PhoneAccountHandle,String>>,onAdd:()->Unit,onSilence:()->Unit) {
    val ringing=call.state==Call.STATE_RINGING
    val active=call.state==Call.STATE_ACTIVE
    val holding=call.state==Call.STATE_HOLDING
    var elapsed by remember(call.id,call.connectedAt){mutableLongStateOf(0)}
    LaunchedEffect(call.id,call.connectedAt,active) {
        while(active && call.connectedAt>0){elapsed=((System.currentTimeMillis()-call.connectedAt)/1000).coerceAtLeast(0);delay(1000)}
    }
    val title=call.name ?: call.number ?: stringResource(R.string.unknown_caller)
    Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text(stringResource(when(call.state){Call.STATE_RINGING->R.string.incoming_call;Call.STATE_ACTIVE->R.string.ongoing_call;Call.STATE_HOLDING->R.string.hold;Call.STATE_SELECT_PHONE_ACCOUNT->R.string.choose_sim;else->R.string.outgoing_call}),style=MaterialTheme.typography.titleMedium,color=MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Surface(Modifier.size(112.dp),shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer) {
            Box(contentAlignment=Alignment.Center){Text(title.firstOrNull()?.uppercase() ?: "?",style=MaterialTheme.typography.displayMedium,color=MaterialTheme.colorScheme.onPrimaryContainer)}
        }
        Text(title,style=MaterialTheme.typography.headlineLarge,textAlign=TextAlign.Center)
        if(call.name!=null && call.number!=null) Text(call.number,color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(active) Text(java.lang.String.format(java.util.Locale.ROOT,"%02d:%02d",elapsed/60,elapsed%60),style=MaterialTheme.typography.titleLarge)
        ui.calls.filter {it.id!=call.id}.forEach { other ->
            ElevatedCard(Modifier.fillMaxWidth()) { Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Text(other.name ?: other.number ?: stringResource(R.string.unknown_caller),Modifier.weight(1f))
                if(other.state==Call.STATE_HOLDING) TextButton(onClick={CallRuntime.action(other.id,"hold")}) {Text(stringResource(R.string.resume_call))}
                TextButton(onClick={CallRuntime.action(other.id,"end")}) {Text(stringResource(R.string.end_call))}
            } }
        }
        if(call.state==Call.STATE_SELECT_PHONE_ACCOUNT) {
            if(accounts.isEmpty()) Text(stringResource(R.string.no_sim))
            accounts.forEach { (account,label)->Button(onClick={CallRuntime.selectAccount(call.id,account)},modifier=Modifier.fillMaxWidth()){Text(label)} }
        }
        if(ringing) {
            TextButton(onClick=onSilence){Text(stringResource(R.string.silence))}
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                Button(onClick={CallRuntime.action(call.id,"reject")},modifier=Modifier.weight(1f).heightIn(min=64.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB42335),contentColor=Color.White)){Text(stringResource(R.string.reject))}
                Button(onClick={CallRuntime.action(call.id,"answer")},modifier=Modifier.weight(1f).heightIn(min=64.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFF18794E),contentColor=Color.White)){Text(stringResource(R.string.answer))}
            }
        } else {
            if(active || holding) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick={CallRuntime.action(call.id,"mute")},enabled=active,modifier=Modifier.weight(1f)){Text(stringResource(if(ui.muted)R.string.unmute else R.string.mute))}
                    FilledTonalButton(onClick={CallRuntime.action(call.id,"hold")},enabled=holding||call.canHold,modifier=Modifier.weight(1f)){Text(stringResource(if(holding)R.string.resume_call else R.string.hold))}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    FilledTonalButton(onClick=toggleKeypad,enabled=active,modifier=Modifier.weight(1f)){Text(stringResource(R.string.dial))}
                    FilledTonalButton(onClick=onAdd,enabled=active&&call.canHold,modifier=Modifier.weight(1f)){Text(stringResource(R.string.add_call))}
                }
                if(call.canMerge) OutlinedButton(onClick={CallRuntime.action(call.id,"merge")},enabled=active){Text(stringResource(R.string.merge_calls))}
                if(active && ui.routes.isNotEmpty()) {
                    Text(stringResource(R.string.audio_output),style=MaterialTheme.typography.titleSmall)
                    ui.routes.forEach { route -> FilterChip(selected=ui.currentRoute==route.key,onClick={CallRuntime.route(route.key)},label={Text(route.name)}) }
                }
                if(active && keypad) listOf("123","456","789","*0#").forEach { row ->
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach { digit->FilledTonalButton(onClick={CallRuntime.action(call.id,"dtmf:"+digit)},modifier=Modifier.weight(1f).heightIn(min=48.dp)){Text(digit.toString())} }}
                }
            }
            Button(onClick={CallRuntime.action(call.id,"end")},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp),colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB42335),contentColor=Color.White)){Text(stringResource(R.string.end_call))}
        }
    }
}
