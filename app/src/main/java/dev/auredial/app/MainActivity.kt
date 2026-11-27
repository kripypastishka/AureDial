package dev.auredial.app

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.os.Build
import android.provider.Settings
import android.telecom.Call
import android.telecom.TelecomManager
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
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

@Composable internal fun AureTheme(content:@Composable ()->Unit) {
    val dark=isSystemInDarkTheme()
    MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=Color(0xFFA9B5FF),background=Color(0xFF101116),surface=Color(0xFF191B23))
        else lightColorScheme(primary=Color(0xFF465DDA),background=Color(0xFFF7F8FC),surface=Color.White),content=content)
}

class MainActivity : ComponentActivity() {
    private lateinit var voice:VoicePreview
    private val screening=mutableStateOf(false)
    private val dialer=mutableStateOf(false)
    private val revision=mutableIntStateOf(0)
    private val externalNumber=mutableStateOf<String?>(null)
    private val roleRequest=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){refresh()}
    private val permissions=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){refresh()}
    private fun refresh(){
        val manager=getSystemService(RoleManager::class.java)
        screening.value=manager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)==true
        dialer.value=isDialer()
        revision.intValue++
    }
    private fun handleIntent(intent:Intent) {
        if(intent.action==Intent.ACTION_DIAL || intent.getBooleanExtra("open_dial",false)) externalNumber.value=intent.data?.takeIf{it.scheme=="tel"}?.schemeSpecificPart?.take(40) ?: ""
    }
    private fun requestRole(role:String) {
        val manager=getSystemService(RoleManager::class.java)
        if(manager!=null && manager.isRoleAvailable(role)) runCatching{roleRequest.launch(manager.createRequestRoleIntent(role))}
    }
    private fun place(number:String):Boolean=runCatching {
        if(!isDialer()) startActivity(Intent(Intent.ACTION_DIAL,Uri.fromParts("tel",number,null)))
        else {
            if(!hasPermission(Manifest.permission.CALL_PHONE)) {permissions.launch(arrayOf(Manifest.permission.CALL_PHONE));return false}
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel",number,null),Bundle())
        }
        true
    }.getOrDefault(false)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        voice=VoicePreview(this)
        handleIntent(intent)
        val store=RuleStore(this)
        setContent {
            AureTheme {
                var tab by rememberSaveable {mutableIntStateOf(0)}
                var keypad by rememberSaveable {mutableStateOf(false)}
                var initial by rememberSaveable {mutableStateOf("")}
                var warning by rememberSaveable {mutableStateOf(false)}
                val callUi by CallRuntime.state.collectAsStateWithLifecycle()
                val snackbar=remember{SnackbarHostState()};val scope=rememberCoroutineScope()
                fun error(id:Int){scope.launch{snackbar.showSnackbar(getString(id))}}
                LaunchedEffect(externalNumber.value) {externalNumber.value?.let{initial=it;keypad=true;externalNumber.value=null}}
                LaunchedEffect(callUi.calls.firstOrNull{it.state==Call.STATE_DIALING||it.state==Call.STATE_CONNECTING||it.state==Call.STATE_SELECT_PHONE_ACCOUNT}?.id){
                    if(callUi.calls.any{it.state==Call.STATE_DIALING||it.state==Call.STATE_CONNECTING||it.state==Call.STATE_SELECT_PHONE_ACCOUNT})
                        startActivity(Intent(this@MainActivity,CallActivity::class.java))
                }
                Scaffold(snackbarHost={SnackbarHost(snackbar)},floatingActionButton={
                    if(tab<3) ExtendedFloatingActionButton(onClick={initial="";keypad=true}){Text(stringResource(R.string.dial))}
                },bottomBar={
                    NavigationBar {
                        listOf(R.string.recents,R.string.contacts,R.string.favorites,R.string.protect,R.string.settings).forEachIndexed{index,label->
                            NavigationBarItem(selected=tab==index,onClick={voice.stop();tab=index;keypad=false},icon={Text(listOf("◷","●","★","◈","⚙")[index],style=MaterialTheme.typography.titleLarge)},label={Text(stringResource(label),maxLines=1,overflow=TextOverflow.Ellipsis)})
                        }
                    }
                }) {padding->
                    Column(Modifier.padding(padding).fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically){
                            Text("AureDial",style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.primary,modifier=Modifier.weight(1f))
                            if(callUi.calls.isNotEmpty()) TextButton(onClick={startActivity(Intent(this@MainActivity,CallActivity::class.java))}){Text(stringResource(R.string.ongoing_call))}
                        }
                        if(!dialer.value && !keypad) ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)){
                            Text(stringResource(R.string.default_note))
                            TextButton(onClick={warning=true}){Text(stringResource(R.string.set_default))}
                        }}
                        if(keypad) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                            TextButton(onClick={keypad=false}){Text(stringResource(R.string.close_keypad))}
                            key(initial){DialPage(initial,dialer.value){if(place(it))keypad=false else error(R.string.action_failed)}}
                        } else when(tab){
                            0,1,2 -> PeoplePage(tab,revision.intValue,dialer.value,
                                request={permissions.launch(if(tab==0)arrayOf(Manifest.permission.READ_CALL_LOG) else arrayOf(Manifest.permission.READ_CONTACTS))},
                                dial={initial=it;keypad=true})
                            3 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                                ProtectionPage(store,screening.value,{requestRole(RoleManager.ROLE_CALL_SCREENING)},{error(it)})
                            }
                            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                                Text(stringResource(R.string.settings),style=MaterialTheme.typography.headlineSmall)
                                Text(stringResource(R.string.beta_notice),style=MaterialTheme.typography.bodySmall)
                                TextButton(onClick={warning=true}){Text(stringResource(R.string.set_default))}
                                TextButton(onClick={permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS,Manifest.permission.READ_PHONE_STATE))}){Text(stringResource(R.string.permissions))}
                                if(Build.VERSION.SDK_INT>=33) TextButton(onClick={permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))}){Text(stringResource(R.string.notifications))}
                                if(Build.VERSION.SDK_INT>=34) TextButton(onClick={runCatching{startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,Uri.parse("package:"+packageName)))}}){Text(stringResource(R.string.fullscreen))}
                                var announce by remember {mutableStateOf(voiceEnabled(this@MainActivity))}
                                Row(verticalAlignment=Alignment.CenterVertically){
                                    Text(stringResource(R.string.announce_enabled),Modifier.weight(1f))
                                    Switch(checked=announce,onCheckedChange={announce=it;getSharedPreferences("voice",MODE_PRIVATE).edit().putBoolean("announce",it).apply()})
                                }
                                Text(stringResource(R.string.announce_limit),style=MaterialTheme.typography.bodySmall)
                                VoicePage(voice)
                            }
                        }
                    }
                }
                if(warning) AlertDialog(onDismissRequest={warning=false},title={Text(stringResource(R.string.set_default))},text={Text(stringResource(R.string.beta_notice))},
                    confirmButton={TextButton(onClick={
                        warning=false
                        if(Build.VERSION.SDK_INT>=33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        else requestRole(RoleManager.ROLE_DIALER)
                    }){Text(stringResource(R.string.continue_action))}},dismissButton={TextButton(onClick={warning=false}){Text(stringResource(R.string.cancel_action))}})
            }
        }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent)}
    override fun onResume(){super.onResume();refresh()}
    override fun onStop(){voice.stop();super.onStop()}
    override fun onDestroy(){voice.close();super.onDestroy()}
}

@Composable private fun PeoplePage(tab:Int,revision:Int,dialer:Boolean,request:()->Unit,dial:(String)->Unit){
    val context=LocalContext.current
    val history=tab==0
    val permitted=if(history)dialer&&context.hasPermission(Manifest.permission.READ_CALL_LOG) else context.hasPermission(Manifest.permission.READ_CONTACTS)
    var query by rememberSaveable(tab){mutableStateOf("")}
    var rows by remember(tab){mutableStateOf<List<PhoneRow>?>(null)}
    var failed by remember(tab){mutableStateOf(false)}
    LaunchedEffect(tab,revision,permitted){
        rows=null;failed=false
        if(permitted){val result=withContext(Dispatchers.IO){runCatching{readPeople(context,history)}}
            rows=result.getOrNull();failed=result.isFailure}
    }
    Text(stringResource(listOf(R.string.recents,R.string.contacts,R.string.favorites)[tab]),style=MaterialTheme.typography.headlineSmall)
    if(!permitted){Text(stringResource(if(history&&!dialer)R.string.default_note else R.string.permission_note));if(!history||dialer) Button(onClick=request){Text(stringResource(R.string.permissions))};return}
    OutlinedTextField(value=query,onValueChange={query=it},label={Text(stringResource(R.string.search))},singleLine=true,modifier=Modifier.fillMaxWidth())
    if(failed){Text(stringResource(R.string.action_failed));return}
    val data=rows
    if(data==null){LinearProgressIndicator(Modifier.fillMaxWidth());return}
    val visible=remember(data,tab,query){data.filter{(tab!=2||it.favorite)&&(query.isBlank()||it.name.orEmpty().contains(query,true)||it.number.contains(query))}}
    if(visible.isEmpty())Text(stringResource(R.string.empty_list))
    LazyColumn(contentPadding=PaddingValues(bottom=90.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        items(visible,key={it.key}){row->
            ElevatedCard(onClick={dial(row.number)},modifier=Modifier.fillMaxWidth()){
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Surface(Modifier.size(44.dp),shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){Text((row.name ?: row.number).firstOrNull()?.uppercase() ?: "?",color=MaterialTheme.colorScheme.onPrimaryContainer)}}
                    Column(Modifier.weight(1f)){Text(row.name ?: row.number,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis);if(row.name!=null)Text(row.number,style=MaterialTheme.typography.bodySmall);if(row.detail.isNotBlank())Text(row.detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Text("›",style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable private fun DialPage(initial:String, isDefault:Boolean,onDial: (String)->Unit) {
    var number by rememberSaveable { mutableStateOf(initial.filter{it in "0123456789+*#"}.take(40)) }
    Text(stringResource(if(isDefault)R.string.dial else R.string.dial_note), style=MaterialTheme.typography.bodyMedium)
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
    Button(onClick={onDial(number)},enabled=number.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) { Text(stringResource(if(isDefault)R.string.make_call else R.string.open_dialer)) }
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
