package dev.auredial.app

import android.Manifest
import android.content.pm.PackageManager
import android.provider.CallLog
import android.provider.ContactsContract
import androidx.compose.foundation.horizontalScroll
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
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.core.content.edit
import androidx.core.net.toUri
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.auredial.domain.Rules
import dev.auredial.domain.t9Matches
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable internal fun AureTheme(content:@Composable ()->Unit) {
    val dark=isSystemInDarkTheme()
    MaterialTheme(colorScheme=if(dark) darkColorScheme(primary=Color(0xFFA9B5FF),background=Color(0xFF101116),surface=Color(0xFF191B23))
        else lightColorScheme(primary=Color(0xFF3C50BB), background=Color(0xFFF7F8FC), surface=Color(0xFFFDFBFF),
            primaryContainer=Color(0xFFE0E4FF), onPrimaryContainer=Color(0xFF142466)), content=content)
}

class MainActivity : ComponentActivity() {
    private val voiceDelegate = lazy(LazyThreadSafetyMode.NONE) { VoicePreview(this) }
    private val voice by voiceDelegate

    private fun stopPreview() {
        if (voiceDelegate.isInitialized()) voice.stop()
    }
    private val screening=mutableStateOf(false)
    private val dialer=mutableStateOf(false)
    private val revision=mutableIntStateOf(0)
    private val externalNumber=mutableStateOf<String?>(null)
    private val roleRequest=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){refresh()}
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val setupNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        if (granted) requestRole(RoleManager.ROLE_DIALER)
    }
    private fun refresh(){
        val manager=getSystemService(RoleManager::class.java)
        screening.value=manager?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING)==true
        dialer.value=isDialer()
        revision.intValue++
    }
    private fun handleIntent(intent:Intent) {
        if (intent.action == Intent.ACTION_DIAL) {
            externalNumber.value = intent.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart?.take(64) ?: ""
        }
    }
    private fun requestRole(role:String) {
        val manager=getSystemService(RoleManager::class.java)
        if(manager!=null && manager.isRoleAvailable(role)) runCatching{roleRequest.launch(manager.createRequestRoleIntent(role))}
    }
    private fun place(number:String):Boolean=runCatching {
        if (!isDialer()) {
            val telecom = getSystemService(TelecomManager::class.java)
            val systemDialer = telecom.defaultDialerPackage?.takeIf { it != packageName } ?: telecom.systemDialerPackage
            if (systemDialer == null || systemDialer == packageName) return false
            startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)).setPackage(systemDialer))
        }
        else {
            if(checkSelfPermission(Manifest.permission.CALL_PHONE)!=PackageManager.PERMISSION_GRANTED) {permissions.launch(arrayOf(Manifest.permission.CALL_PHONE));return false}
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel",number,null),Bundle())
        }
        true
    }.getOrDefault(false)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        val store=RuleStore(this)
        setContent {
            AureTheme {
                var tab by rememberSaveable {mutableIntStateOf(0)}
                var keypad by rememberSaveable {mutableStateOf(false)}
                var initial by rememberSaveable {mutableStateOf("")}
                var warning by rememberSaveable {mutableStateOf(false)}
                var keypadSession by rememberSaveable { mutableIntStateOf(0) }
                var details by remember { mutableStateOf<PhoneRow?>(null) }
                fun openKeypad(number: String) {
                    initial = number
                    keypadSession++
                    keypad = true
                }
                val callUi by CallRuntime.state.collectAsStateWithLifecycle()
                val snackbar=remember{SnackbarHostState()};val scope=rememberCoroutineScope()
                fun error(id:Int){scope.launch{snackbar.showSnackbar(getString(id))}}
                LaunchedEffect(externalNumber.value) {externalNumber.value?.let { openKeypad(it); externalNumber.value = null }}
                LaunchedEffect(callUi.calls.firstOrNull{it.state==Call.STATE_DIALING||it.state==Call.STATE_CONNECTING||it.state==Call.STATE_SELECT_PHONE_ACCOUNT}?.id){
                    if(callUi.calls.any{it.state==Call.STATE_DIALING||it.state==Call.STATE_CONNECTING||it.state==Call.STATE_SELECT_PHONE_ACCOUNT})
                        startActivity(Intent(this@MainActivity,CallActivity::class.java))
                }
                Scaffold(snackbarHost={SnackbarHost(snackbar)},floatingActionButton={
                    if (tab < 3 && !keypad) ExtendedFloatingActionButton(onClick = { openKeypad("") }){Text(stringResource(R.string.dial))}
                },bottomBar={
                    NavigationBar {
                        listOf(R.string.recents,R.string.contacts,R.string.favorites,R.string.protect,R.string.settings).forEachIndexed{index,label->
                            NavigationBarItem(selected=tab==index,onClick={stopPreview();tab=index;keypad=false},icon={Text(listOf("◷","●","★","◈","⚙")[index],style=MaterialTheme.typography.titleLarge)},label={Text(stringResource(label),maxLines=1,overflow=TextOverflow.Ellipsis)})
                        }
                    }
                }) {padding->
                    Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize().padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
                        Row(Modifier.fillMaxWidth().padding(top=12.dp),verticalAlignment=Alignment.CenterVertically){
                            Text(stringResource(R.string.app_name), style=MaterialTheme.typography.headlineMedium,color=MaterialTheme.colorScheme.primary,modifier=Modifier.weight(1f))
                            if(callUi.calls.isNotEmpty()) TextButton(onClick={startActivity(Intent(this@MainActivity,CallActivity::class.java))}){Text(stringResource(R.string.ongoing_call))}
                        }
                        if(!dialer.value && !keypad) ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(14.dp)){
                            Text(stringResource(R.string.default_note))
                            TextButton(onClick={warning=true}){Text(stringResource(R.string.set_default))}
                        }}
                        if(keypad) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                            TextButton(onClick={keypad=false}){Text(stringResource(R.string.close_keypad))}
                            key(keypadSession) {
                                DialPage(initial, dialer.value) { if (place(it)) keypad = false else error(R.string.action_failed) }
                            }
                        } else when(tab){
                            0,1,2 -> PeoplePage(tab,revision.intValue,dialer.value,
                                request={permissions.launch(if(tab==0)arrayOf(Manifest.permission.READ_CALL_LOG) else arrayOf(Manifest.permission.READ_CONTACTS))},
                                onDetails = { details = it })
                            3 -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                                ProtectionPage(store,screening.value,{requestRole(RoleManager.ROLE_CALL_SCREENING)},{error(it)})
                            }
                            else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)){
                                Text(stringResource(R.string.settings),style=MaterialTheme.typography.headlineSmall)
                                Text(stringResource(R.string.beta_notice),style=MaterialTheme.typography.bodySmall)
                                TextButton(onClick={warning=true}){Text(stringResource(R.string.set_default))}
                                TextButton(onClick={permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS,Manifest.permission.READ_PHONE_STATE))}){Text(stringResource(R.string.permissions))}
                                if(Build.VERSION.SDK_INT>=33) TextButton(onClick={permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))}){Text(stringResource(R.string.notifications))}
                                if(Build.VERSION.SDK_INT>=34) TextButton(onClick={runCatching{startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,("package:" + packageName).toUri()))}}){Text(stringResource(R.string.fullscreen))}
                                VoicePage(voice)
                            }
                        }
                    }
                }
                details?.let { row ->
                    NumberDetails(
                        row = row, store = store, onDismiss = { details = null }, onError = ::error,
                        onDial = { details = null; openKeypad(row.number) },
                    )
                }
                if(warning) AlertDialog(onDismissRequest={warning=false},title={Text(stringResource(R.string.set_default))},text={Text(stringResource(R.string.beta_notice))},
                    confirmButton={TextButton(onClick={
                        warning=false
                        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS))
                            setupNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else requestRole(RoleManager.ROLE_DIALER)
                    }){Text(stringResource(R.string.continue_action))}},dismissButton={TextButton(onClick={warning=false}){Text(stringResource(R.string.cancel_action))}})
            }
        }
    }
    override fun onNewIntent(intent:Intent){super.onNewIntent(intent);setIntent(intent);handleIntent(intent)}
    override fun onResume(){super.onResume();refresh()}
    override fun onStop(){stopPreview();super.onStop()}
    override fun onDestroy(){if (voiceDelegate.isInitialized()) voice.close();super.onDestroy()}
}

@Composable private fun PeoplePage(tab:Int,revision:Int,dialer:Boolean,request:()->Unit,onDetails:(PhoneRow)->Unit){
    val context=LocalContext.current
    val history=tab==0
    val permitted=if(history)dialer&&context.hasPermission(Manifest.permission.READ_CALL_LOG) else context.hasPermission(Manifest.permission.READ_CONTACTS)
    var query by rememberSaveable(tab) { mutableStateOf("") }
    var selectedType by rememberSaveable { mutableStateOf<Int?>(null) }
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
    if (history) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(null to R.string.all_calls, CallLog.Calls.MISSED_TYPE to R.string.missed_calls,
                CallLog.Calls.BLOCKED_TYPE to R.string.blocked_calls).forEach { (type, label) ->
                FilterChip(selected = selectedType == type, onClick = { selectedType = type },
                    label = { Text(stringResource(label)) })
            }
        }
    }
    if(failed){Text(stringResource(R.string.action_failed));return}
    val data=rows
    if(data==null){LinearProgressIndicator(Modifier.fillMaxWidth());return}
    val visible = remember(data, tab, query, selectedType) {
        val term = query.trim()
        data.filter { row ->
            (tab != 2 || row.favorite) && (!history || selectedType == null || row.callType == selectedType) &&
                (term.isEmpty() || row.name.orEmpty().contains(term, true) ||
                    row.number.contains(term) || t9Matches(term, row.name, row.number))
        }
    }
    if(visible.isEmpty())Text(stringResource(R.string.empty_list))
    LazyColumn(contentPadding=PaddingValues(bottom=90.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){
        items(visible,key={it.key}){row->
            val title = row.name ?: row.number.ifBlank { stringResource(R.string.unknown_caller) }
            ElevatedCard(onClick = { onDetails(row) }, modifier = Modifier.fillMaxWidth()){
                Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Surface(Modifier.size(44.dp),shape=CircleShape,color=MaterialTheme.colorScheme.primaryContainer){Box(contentAlignment=Alignment.Center){Text(title.firstOrNull()?.uppercase() ?: "?",color=MaterialTheme.colorScheme.onPrimaryContainer)}}
                    Column(Modifier.weight(1f)){Text(title,style=MaterialTheme.typography.titleMedium,maxLines=1,overflow=TextOverflow.Ellipsis);if(row.name!=null && row.number.isNotBlank())Text(row.number,style=MaterialTheme.typography.bodySmall);if(row.detail.isNotBlank())Text(row.detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
                    Text("›",style=MaterialTheme.typography.headlineSmall,color=MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable private fun DialPage(initial:String, isDefault:Boolean,onDial: (String)->Unit) {
    val context = LocalContext.current
    var number by rememberSaveable { mutableStateOf(initial.toDialInput()) }
    var contacts by remember { mutableStateOf(emptyList<PhoneRow>()) }
    LaunchedEffect(Unit) {
        contacts = withContext(Dispatchers.IO) { runCatching { readPeople(context, false) }.getOrDefault(emptyList()) }
    }
    val matches = remember(contacts, number) {
        if (number.length < 2) emptyList() else contacts.asSequence()
            .filter { t9Matches(number, it.name, it.number) }.take(3).toList()
    }
    Text(stringResource(if(isDefault)R.string.dial else R.string.dial_note), style=MaterialTheme.typography.bodyMedium)
    OutlinedTextField(value=number,onValueChange = { number = it.toDialInput() },label={Text(stringResource(R.string.phone_number))},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone),modifier=Modifier.fillMaxWidth())
    val keypadLetters = stringResource(R.string.keypad_letters).split('|')
    listOf("123", "456", "789", "*0#").forEach { digits ->
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp),modifier=Modifier.fillMaxWidth()) {
            digits.forEach { digit ->
                FilledTonalButton(
                    onClick = { number = (number + digit).toDialInput() },
                    modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(digit.toString(), style = MaterialTheme.typography.headlineSmall)
                        val letters = keypadLetters.getOrNull(digit - '2')
                        if (letters != null) Text(letters, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
    Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick={number=number.dropLast(1)},enabled=number.isNotEmpty()) { Text(stringResource(R.string.delete_digit)) }
        OutlinedButton(onClick={ if(number.isEmpty()) number="+" },enabled=number.isEmpty()) { Text("+") }
    }
    Button(onClick={onDial(number)},enabled=number.isNotBlank() && number != "+",modifier=Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text(stringResource(if(isDefault)R.string.make_call else R.string.open_dialer)) }
    matches.forEach { row ->
        OutlinedCard(onClick = { number = row.number.toDialInput() }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text(row.name ?: row.number, style = MaterialTheme.typography.titleSmall)
                if (row.name != null) Text(row.number, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun String.toDialInput(): String = buildString {
    for (character in this@toDialInput) {
        if (length == 40) break
        val digit = Character.digit(character, 10)
        when {
            digit >= 0 -> append(digit)
            character == '+' && isEmpty() -> append(character)
            character in "*#" -> append(character)
            character in ",;" && isNotEmpty() -> append(character)
        }
    }
}

@Composable private fun ProtectionPage(store: RuleStore, held: Boolean, requestRole: ()->Unit, onError:(Int)->Unit) {
    val context = LocalContext.current
    var rules by remember { mutableStateOf<Rules?>(null) }
    var pending by remember { mutableStateOf(false) }
    var entry by rememberSaveable { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    LaunchedEffect(store) { rules=withContext(Dispatchers.IO) { runCatching { store.read() }.getOrNull() }; if(rules==null) onError(R.string.save_error) }
    fun update(transform: (Rules) -> Rules) {
        if (pending) return
        pending=true
        scope.launch {
            try {
                val updated = withContext(Dispatchers.IO) { runCatching { store.update(transform) }.getOrNull() }
                if (updated != null) rules = updated else onError(R.string.save_error)
            } finally { pending=false }
        }
    }
    Text(stringResource(R.string.rules_title),style=MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.rules_note))
    ElevatedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text(stringResource(if(held) R.string.role_granted else R.string.role_missing))
        if(!held) Button(onClick=requestRole) { Text(stringResource(R.string.request_role)) }
        rules?.let { current ->
            SettingsSwitch(R.string.enable_rules, current.enabled, !pending) { enabled -> update { it.copy(enabled = enabled) } }
            if (Build.VERSION.SDK_INT >= 30) {
                SettingsSwitch(R.string.silence_failed, current.silenceFailedVerification, current.enabled && !pending) { enabled ->
                    update { it.copy(silenceFailedVerification = enabled) }
                }
                Text(stringResource(R.string.silence_failed_note), style = MaterialTheme.typography.bodySmall)
            }
        }
    } }
    val current=rules ?: return
    OutlinedTextField(value=entry,onValueChange={entry=it.take(64)},label={Text(stringResource(R.string.international_number))},singleLine=true,modifier=Modifier.fillMaxWidth(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Phone))
    Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Button(enabled = !pending, onClick = {
            val number = normalizeRuleNumber(context, entry)
            if (number == null) onError(R.string.invalid_number)
            else update { it.withNumber(number, allowed = true) }
        }) { Text(stringResource(R.string.allow)) }
        OutlinedButton(enabled = !pending, onClick = {
            val number = normalizeRuleNumber(context, entry)
            if (number == null) onError(R.string.invalid_number)
            else update { it.withNumber(number, allowed = false) }
        }) { Text(stringResource(R.string.block)) }
    }
    NumberList(R.string.allow_list, current.allowed, !pending) { number -> update { it.copy(allowed = it.allowed - number) } }
    NumberList(R.string.block_list, current.blocked, !pending) { number -> update { it.copy(blocked = it.blocked - number) } }

}
@Composable private fun NumberList(title:Int, numbers:Set<String>,enabled:Boolean,remove:(String)->Unit) {
    Text(stringResource(title),style=MaterialTheme.typography.titleMedium)
    if(numbers.isEmpty()) Text(stringResource(R.string.empty_list))
    numbers.sorted().forEach { number -> Row(verticalAlignment=Alignment.CenterVertically,modifier=Modifier.fillMaxWidth()) {
        Text(number,modifier=Modifier.weight(1f)); TextButton(enabled=enabled,onClick={remove(number)}) { Text(stringResource(R.string.remove)) }
    } }
}
@Composable
private fun SettingsSwitch(@StringRes label: Int, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val description = stringResource(label)
        Text(description, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled,
            modifier = Modifier.semantics { contentDescription = description })
    }
}

@Composable
private fun NumberDetails(row: PhoneRow, store: RuleStore, onDismiss: () -> Unit, onError: (Int) -> Unit, onDial: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val hasNumber = row.number.isNotBlank()
    var confirmBlock by remember(row.key) { mutableStateOf(false) }
    var saving by remember(row.key) { mutableStateOf(false) }
    fun open(intent: Intent) {
        if (runCatching { context.startActivity(intent) }.isSuccess) onDismiss() else onError(R.string.action_failed)
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = {
            Text(if (confirmBlock) stringResource(R.string.confirm_block)
                else row.name ?: row.number.ifBlank { stringResource(R.string.unknown_caller) })
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (hasNumber && (row.name != null || confirmBlock)) Text(row.number)
                if (!confirmBlock) {
                    if (row.detail.isNotBlank()) Text(row.detail)
                    if (hasNumber) {
                        TextButton(onClick = { open(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", row.number, null))) }) {
                            Text(stringResource(R.string.send_message))
                        }
                        val contactUri = row.contactUri
                        TextButton(onClick = {
                            open(if (contactUri != null) Intent(Intent.ACTION_EDIT, contactUri) else
                                Intent(Intent.ACTION_INSERT_OR_EDIT).setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                    .putExtra(ContactsContract.Intents.Insert.PHONE, row.number))
                        }) { Text(stringResource(if (contactUri == null) R.string.save_contact else R.string.edit_contact)) }
                        TextButton(onClick = { confirmBlock = true }) { Text(stringResource(R.string.block)) }
                    }
                } else {
                    Text(stringResource(R.string.confirm_block_note), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                if (!hasNumber) onDismiss()
                else if (!confirmBlock) onDial()
                else {
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
                            onError(result)
                        } finally {
                            saving = false
                        }
                    }
                }
            }) { Text(stringResource(when {
                !hasNumber -> R.string.close_details
                confirmBlock -> R.string.block
                else -> R.string.make_call
            })) }
        },
        dismissButton = {
            if (hasNumber) TextButton(enabled = !saving, onClick = { if (confirmBlock) confirmBlock = false else onDismiss() }) {
                Text(stringResource(R.string.cancel_action))
            }
        },
    )
}

@Composable
private fun VoicePage(voice: VoicePreview) {
    val context = LocalContext.current
    val preferences = remember(context) { voicePreferences(context) }
    var settings by remember(context) { mutableStateOf(readVoiceSettings(context)) }
    var rate by remember(settings.rate) { mutableFloatStateOf(settings.rate.toFloat()) }
    val status by voice.state.collectAsStateWithLifecycle()
    Text(stringResource(R.string.voice_title), style = MaterialTheme.typography.headlineSmall)
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSwitch(R.string.announce_enabled, settings.enabled) {
                preferences.edit { putBoolean("announce", it) }
                settings = settings.copy(enabled = it)
            }
            SettingsSwitch(R.string.contacts_only, settings.contactsOnly, settings.enabled) {
                preferences.edit { putBoolean("contacts_only", it) }
                settings = settings.copy(contactsOnly = it)
            }
            Text(stringResource(R.string.announce_limit), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.voice_rate, rate.roundToInt()))
            Slider(value = rate, valueRange = 75f..125f, steps = 9, onValueChange = { rate = it },
                onValueChangeFinished = {
                    val chosen = rate.roundToInt()
                    preferences.edit { putInt("rate", chosen) }
                    settings = settings.copy(rate = chosen)
                })
            Text(stringResource(status), style = MaterialTheme.typography.bodyMedium)
            if (status == R.string.voice_playing) {
                OutlinedButton(onClick = voice::stop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.stop)) }
            } else {
                Button(onClick = voice::play, enabled = status != R.string.voice_loading && status != R.string.voice_unavailable,
                    modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.preview)) }
            }
        }
    }
}
