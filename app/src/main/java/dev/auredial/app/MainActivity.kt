package dev.auredial.app

import android.Manifest
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.Call
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val screening=mutableStateOf(false)
    private val dialer=mutableStateOf(false)
    private val revision=mutableIntStateOf(0)
    private val externalNumber=mutableStateOf<String?>(null)
    private val roleRequest=registerForActivityResult(ActivityResultContracts.StartActivityForResult()){refresh()}
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    private val setupNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private var callSetup by mutableStateOf(CallSetup.DEFAULT_APP)
    private var lastPresentedCall: String? = null

    private fun refresh() {
        val roles = getSystemService(RoleManager::class.java)
        screening.value = roles?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true
        dialer.value = isDialer()
        val notifications = getSystemService(NotificationManager::class.java)
        val importance = notifications.getNotificationChannel(INCOMING_CALL_CHANNEL)?.importance
        callSetup = when {
            !dialer.value -> CallSetup.DEFAULT_APP
            !notifications.areNotificationsEnabled() -> CallSetup.NOTIFICATIONS
            importance != null && importance < NotificationManager.IMPORTANCE_HIGH -> CallSetup.CHANNEL
            !canShowCallFullScreen(this) -> CallSetup.FULL_SCREEN
            else -> CallSetup.READY
        }
        revision.intValue++
    }

    private fun openSetting(action: String, configure: Intent.() -> Unit = {}) {
        val destination = Intent(action).apply(configure)
        if (runCatching { startActivity(destination) }.isFailure) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, ("package:" + packageName).toUri()))
        }
    }

    private fun configureCalls(step: CallSetup) {
        when (step) {
            CallSetup.DEFAULT_APP -> requestRole(RoleManager.ROLE_DIALER)
            CallSetup.NOTIFICATIONS -> {
                val prefs = getPreferences(MODE_PRIVATE)
                if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS) &&
                    (!prefs.getBoolean("asked_notifications", false) ||
                        shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))
                ) {
                    prefs.edit { putBoolean("asked_notifications", true) }
                    setupNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else openSetting(Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                    putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                }
            }
            CallSetup.CHANNEL -> openSetting(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS) {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, INCOMING_CALL_CHANNEL)
            }
            CallSetup.FULL_SCREEN -> if (Build.VERSION.SDK_INT >= 34) {
                openSetting(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT) {
                    data = ("package:" + packageName).toUri()
                }
            }
            CallSetup.READY -> Unit
        }
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
            if(checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {permissions.launch(arrayOf(Manifest.permission.CALL_PHONE));return false}
            getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel",number,null),Bundle())
        }
        true
    }.getOrDefault(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        val store = RuleStore(this)
        setContent {
            AureTheme {
                val callUi by CallRuntime.state.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {
                    lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                        CallRuntime.state.collect { state ->
                            val call = state.calls.firstOrNull { isIncomingRinging(it.state) } ?: state.calls.firstOrNull {
                                it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING ||
                                    it.state == Call.STATE_SELECT_PHONE_ACCOUNT
                            }
                            if (call != null && lastPresentedCall != call.id) {
                                lastPresentedCall = call.id
                                startActivity(callScreenIntent(this@MainActivity))
                            } else if (state.calls.isEmpty()) {
                                lastPresentedCall = null
                            }
                        }
                    }
                }
                Home(
                    store = store,
                    defaultPhone = dialer.value,
                    screening = screening.value,
                    revision = revision.intValue,
                    setup = callSetup,
                    inCall = callUi.calls.isNotEmpty(),
                    externalNumber = externalNumber.value,
                )
            }
        }
    }

    @Composable
    private fun Home(
        store: RuleStore, defaultPhone: Boolean, screening: Boolean, revision: Int,
        setup: CallSetup, inCall: Boolean, externalNumber: String?,
    ) {
        var tab by rememberSaveable { mutableStateOf(HomePage.RECENTS) }
        var subpage by rememberSaveable { mutableStateOf<HomePage?>(null) }
        var dialNumber by rememberSaveable { mutableStateOf("") }
        var dialSession by rememberSaveable { mutableIntStateOf(0) }
        var details by remember { mutableStateOf<PhoneRow?>(null) }
        val page = subpage ?: tab
        val back = { subpage = null }
        val stateHolder = rememberSaveableStateHolder()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        fun notify(id: Int) { scope.launch { snackbar.showSnackbar(getString(id)) } }
        fun openDial(number: String) {
            dialNumber = number
            dialSession++
            subpage = HomePage.DIAL
        }

        BackHandler(enabled = subpage != null, onBack = back)
        LaunchedEffect(externalNumber) {
            if (externalNumber != null) {
                openDial(externalNumber)
                this@MainActivity.externalNumber.value = null
            }
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (subpage == null) HomeDock(tab, { tab = it }, { openDial("") })
            },
            contentWindowInsets = WindowInsets.safeDrawing,
        ) { padding ->
            Column(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (inCall) {
                    AureAction(
                        stringResource(R.string.open_call), onClick = { startActivity(callScreenIntent(this@MainActivity)) },
                        modifier = Modifier.padding(12.dp).widthIn(max = 530.dp).fillMaxWidth(), icon = R.drawable.aure_answer,
                    )
                }
                AnimatedContent(
                    page, modifier = Modifier.weight(1f).widthIn(max = 610.dp).fillMaxWidth(),
                    transitionSpec = {
                        val direction = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (fadeIn(tween(180, delayMillis = 45)) + slideInHorizontally(tween(245)) { direction * it / 9 })
                            .togetherWith(fadeOut(tween(125)) + slideOutHorizontally(tween(180)) { -direction * it / 12 })
                    },
                    label = "Phone screen",
                ) { destination ->
                    stateHolder.SaveableStateProvider(destination.name) {
                        when (destination) {
                            HomePage.RECENTS, HomePage.CONTACTS -> DirectoryPage(
                                history = destination == HomePage.RECENTS, revision = revision, dialer = defaultPhone,
                                setup = setup, onSetup = ::configureCalls,
                                request = {
                                    val permission = if (destination == HomePage.RECENTS) Manifest.permission.READ_CALL_LOG
                                    else Manifest.permission.READ_CONTACTS
                                    permissions.launch(arrayOf(permission))
                                },
                                onDetails = { details = it },
                                onDial = { openDial("") },
                            )
                            HomePage.PROTECTION -> ProtectionPage(
                                store, screening, { requestRole(RoleManager.ROLE_CALL_SCREENING) }, ::notify,
                            )
                            HomePage.SETTINGS -> SettingsPage(
                                status = setup, openCalls = { subpage = HomePage.CALLS },
                                openVoice = { subpage = HomePage.VOICE }, openRecording = { subpage = HomePage.RECORDING },
                            )
                            HomePage.CALLS -> CallSetupPage(setup, back, ::configureCalls) {
                                permissions.launch(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_PHONE_STATE))
                            }
                            HomePage.VOICE -> VoicePage(back, ::notify)
                            HomePage.RECORDING -> RecordingPage(back)
                            HomePage.DIAL -> key(dialSession) {
                                DialPage(dialNumber, defaultPhone, back) { if (place(it)) back() else notify(R.string.action_failed) }
                            }
                        }
                    }
                }
            }
        }
        details?.let { row ->
            NumberDetails(row, store, { details = null }, ::notify) { details = null; openDial(row.number) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }
}

private enum class HomePage(@field:StringRes val title: Int, @field:DrawableRes val icon: Int) {
    RECENTS(R.string.recents, R.drawable.aure_recent),
    CONTACTS(R.string.contacts, R.drawable.aure_contacts),
    PROTECTION(R.string.protect, R.drawable.aure_shield),
    SETTINGS(R.string.settings, R.drawable.aure_settings),
    CALLS(R.string.receive_calls_title, R.drawable.aure_bell),
    VOICE(R.string.voice_title, R.drawable.aure_wave),
    RECORDING(R.string.recording_title, R.drawable.aure_record),
    DIAL(R.string.dial, R.drawable.aure_grid),
}

@Composable
private fun HomeDock(current: HomePage, select: (HomePage) -> Unit, dial: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(color = scheme.surface, contentColor = scheme.onSurface) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            HorizontalDivider(color = scheme.outlineVariant)
            Row(
                Modifier.widthIn(max = 620.dp).fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp).selectableGroup(),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                DockTab(HomePage.RECENTS, current, select, Modifier.weight(1f))
                DockTab(HomePage.CONTACTS, current, select, Modifier.weight(1f))
                Column(
                    Modifier.weight(1f).clip(AureShapes.medium).pressable(onClick = dial)
                        .padding(horizontal = 2.dp, vertical = 3.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Box(Modifier.size(46.dp, 37.dp).clip(AureShapes.small).background(scheme.primary), contentAlignment = Alignment.Center) {
                        AureGlyph(R.drawable.aure_grid, tint = scheme.onPrimary, size = 21.dp)
                    }
                    Text(stringResource(R.string.dial), style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
                DockTab(HomePage.PROTECTION, current, select, Modifier.weight(1f))
                DockTab(HomePage.SETTINGS, current, select, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun DockTab(page: HomePage, current: HomePage, select: (HomePage) -> Unit, modifier: Modifier) {
    val selected = current == page
    val scheme = MaterialTheme.colorScheme
    val fill by androidx.compose.animation.animateColorAsState(
        if (selected) scheme.primaryContainer else scheme.surface, tween(195), label = "Tab highlight",
    )
    Column(
        modifier.clip(AureShapes.medium).selectable(selected, role = Role.Tab, onClick = { select(page) })
            .padding(horizontal = 2.dp, vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(45.dp, 33.dp).clip(AureShapes.small).background(fill), contentAlignment = Alignment.Center) {
            AureGlyph(page.icon, tint = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant, size = 21.dp)
        }
        Text(stringResource(page.title), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) scheme.onSurface else scheme.onSurfaceVariant)
    }
}
