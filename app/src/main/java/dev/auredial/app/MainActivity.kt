package dev.auredial.app

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.auredial.domain.Rules
import dev.auredial.domain.internationalNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var voice: VoicePreview
    private val role = mutableStateOf(false)
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshRole() }
    private fun refreshRole() { role.value = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        voice = VoicePreview(this)
        val store = RuleStore(this)
        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) darkColorScheme(primary=Color(0xFFA9B5FF), background=Color(0xFF101116), surface=Color(0xFF191B23)) else lightColorScheme(primary=Color(0xFF465DDA), background=Color(0xFFF7F8FC), surface=Color.White)) {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                fun error(id: Int) { scope.launch { snackbar.showSnackbar(getString(id)) } }
                Scaffold(snackbarHost={ SnackbarHost(snackbar) }, bottomBar={
                    NavigationBar {
                        listOf(R.string.dial,R.string.protect,R.string.voice).forEachIndexed { index, label ->
                            NavigationBarItem(selected=tab==index,onClick={ if (tab==2) voice.stop(); tab=index },icon={ Text(listOf("⌕","◈","♪")[index],style=MaterialTheme.typography.titleLarge) },label={ Text(stringResource(label)) })
                        }
                    }
                }) { padding ->
                    Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
                        Text(stringResource(R.string.app_name), style=MaterialTheme.typography.headlineLarge, color=MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.alpha), style=MaterialTheme.typography.bodySmall)
                        when(tab) {
                            0 -> DialPage { number -> runCatching { startActivity(Intent(Intent.ACTION_DIAL,Uri.fromParts("tel",number,null))) }.onFailure { error(R.string.action_failed) } }
                            1 -> ProtectionPage(store,role.value,requestRole={
                                val manager=getSystemService(RoleManager::class.java)
                                if(manager != null && manager.isRoleAvailable(RoleManager.ROLE_CALL_SCREENING)) runCatching { roleRequest.launch(manager.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)) }.onFailure { error(R.string.action_failed) }
                                else error(R.string.role_unavailable)
                            },onError={ error(it) })
                            else -> VoicePage(voice)
                        }
                    }
                }
            }
        }
    }
    override fun onResume() { super.onResume(); refreshRole() }
    override fun onStop() { voice.stop(); super.onStop() }
    override fun onDestroy() { voice.close(); super.onDestroy() }
}

@Composable private fun DialPage(onDial: (String)->Unit) {
    var number by rememberSaveable { mutableStateOf("") }
    Text(stringResource(R.string.dial_note), style=MaterialTheme.typography.bodyMedium)
    OutlinedTextField(value=number,onValueChange={ number=it.filter { c->c in "0123456789+*#" }.take(40) },label={Text(stringResource(R.string.phone_number))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),modifier=Modifier.fillMaxWidth())
    listOf("123","456","789","*0#").forEach { digits ->
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxWidth()) {
            digits.forEach { digit -> FilledTonalButton(onClick={ if(number.length<40) number+=digit },modifier=Modifier.weight(1f).height(64.dp)) { Text(digit.toString(),style=MaterialTheme.typography.headlineSmall) } }
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick={number=number.dropLast(1)},enabled=number.isNotEmpty()) { Text(stringResource(R.string.delete_digit)) }
        OutlinedButton(onClick={ if(number.isEmpty()) number="+" },enabled=number.isEmpty()) { Text("+") }
    }
    Button(onClick={onDial(number)},enabled=number.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(stringResource(R.string.open_dialer)) }
}

@Composable private fun ProtectionPage(store: RuleStore, held: Boolean, requestRole: ()->Unit, onError:(Int)->Unit) {
    var rules by remember { mutableStateOf<Rules?>(null) }
    var pending by remember { mutableStateOf(false) }
    var entry by rememberSaveable { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    LaunchedEffect(store) { rules=withContext(Dispatchers.IO) { runCatching { store.read() }.getOrNull() }; if(rules==null) onError(R.string.save_error) }
    fun update(next: Rules) {
        if (pending) return
        pending=true
        scope.launch {
            try {
                val ok=withContext(Dispatchers.IO) { runCatching { store.write(next) }.getOrDefault(false) }
                if(ok) rules=next else onError(R.string.save_error)
            } finally { pending=false }
        }
    }
    Text(stringResource(R.string.rules_title),style=MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.rules_note))
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(stringResource(if(held) R.string.role_granted else R.string.role_missing))
        if(!held) Button(onClick=requestRole) { Text(stringResource(R.string.request_role)) }
        rules?.let { current -> Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.enable_rules),modifier=Modifier.weight(1f))
            Switch(checked=current.enabled,onCheckedChange={update(current.copy(enabled=it))},enabled=!pending)
        } }
    } }
    val current=rules ?: return
    OutlinedTextField(value=entry,onValueChange={entry=it.take(64)},label={Text(stringResource(R.string.international_number))},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone))
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Button(enabled=!pending,onClick={ val n=internationalNumber(entry); if(n==null) onError(R.string.invalid_number) else update(current.copy(allowed=current.allowed+n,blocked=current.blocked-n)) }) { Text(stringResource(R.string.allow)) }
        OutlinedButton(enabled=!pending,onClick={ val n=internationalNumber(entry); if(n==null) onError(R.string.invalid_number) else update(current.copy(blocked=current.blocked+n,allowed=current.allowed-n)) }) { Text(stringResource(R.string.block)) }
    }
    NumberList(R.string.allow_list,current.allowed,!pending) { update(current.copy(allowed=current.allowed-it)) }
    NumberList(R.string.block_list,current.blocked,!pending) { update(current.copy(blocked=current.blocked-it)) }
}
@Composable private fun NumberList(title:Int, numbers:Set<String>,enabled:Boolean,remove:(String)->Unit) {
    Text(stringResource(title),style=MaterialTheme.typography.titleMedium)
    if(numbers.isEmpty()) Text(stringResource(R.string.empty_list))
    numbers.sorted().forEach { number -> Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
        Text(number,modifier=Modifier.weight(1f)); TextButton(enabled=enabled,onClick={remove(number)}) { Text(stringResource(R.string.remove)) }
    } }
}
@Composable private fun VoicePage(voice: VoicePreview) {
    val status by voice.state.collectAsStateWithLifecycle()
    Text(stringResource(R.string.voice_title),style=MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.voice_note))
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(stringResource(status),style=MaterialTheme.typography.titleMedium)
        Button(onClick=voice::play,enabled=status!=R.string.voice_loading && status!=R.string.voice_unavailable && status!=R.string.voice_playing,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.preview)) }
        OutlinedButton(onClick=voice::stop,enabled=status==R.string.voice_playing,modifier=Modifier.fillMaxWidth()) { Text(stringResource(R.string.stop)) }
    } }
}
