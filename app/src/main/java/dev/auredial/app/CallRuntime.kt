package dev.auredial.app

import android.Manifest
import android.content.pm.PackageManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.os.SystemClock
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import dev.auredial.domain.AnnouncementAction
import dev.auredial.domain.AnnouncementSession
import dev.auredial.domain.speakerIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.annotation.RequiresApi
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class CallView(val id: String, val name: String?, val number: String?, val state: Int,
                    val canHold: Boolean, val canMerge: Boolean, val connectedAt: Long, val verificationFailed: Boolean,
                    val accounts: List<Pair<PhoneAccountHandle, String>>)
data class RouteView(val key: String, val name: String)
data class CallUi(val calls: List<CallView> = emptyList(), val muted: Boolean = false,
                  val routes: List<RouteView> = emptyList(), val currentRoute: String? = null,
                  val canAddCall: Boolean = false)

/** Process-local bridge. Telecom objects never enter Compose or persistent storage. Main thread only. */
object CallRuntime {
    private val mutable = MutableStateFlow(CallUi())
    val state = mutable.asStateFlow()
    internal var service: AureInCallService? = null
    internal fun publish(owner: AureInCallService, value: CallUi) {
        if (service === owner) mutable.value = value
    }
    fun action(id: String, action: String) { service?.action(id, action) }
    fun route(key: String) { service?.route(key) }
    fun selectAccount(id: String, account: PhoneAccountHandle) { service?.selectAccount(id,account) }
    fun visible(visible: Boolean) { service?.visible(visible) }
}

class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("call") ?: return
        val action = intent.getStringExtra("command") ?: return
        CallRuntime.action(id,action)
    }
}

@Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
class AureInCallService : InCallService() {
    private class Entry(val call: Call, val id: String, val callback: Call.Callback) {
        var lookupNumber: String? = null
        var localName: String? = null
        var lookupJob: Job? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val entries = LinkedHashMap<Call, Entry>()
    private val main = Handler(Looper.getMainLooper())
    private val incomingChannel = "incoming_v2"
    private val ongoingChannel = "ongoing_v2"
    private var muted = false
    private var legacyMask = 0
    private var legacyRoute = 0
    private var endpoints: List<CallEndpoint> = emptyList()
    private var endpointKey: String? = null
    private var visible = false
    private var announcement: VoicePreview? = null
    private val voiceSession = AnnouncementSession()
    private var ringingVoiceId: String? = null
    private var dtmfCall: Call? = null
    private val stopTone = Runnable { dtmfCall?.stopDtmfTone(); dtmfCall = null }
    private val retryVoice = Runnable { maybeAnnounce() }

    override fun onCreate() {
        super.onCreate()
        CallRuntime.service = this
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(incomingChannel,getString(R.string.incoming_call),NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null,null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        manager.createNotificationChannel(NotificationChannel(ongoingChannel,getString(R.string.ongoing_call),NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null,null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        if (entries.containsKey(call)) return
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) = refresh()
            override fun onDetailsChanged(call: Call, details: Call.Details) {
                entries[call]?.let(::resolveLocalName)
                refresh()
            }
            override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = refresh()
            override fun onChildrenChanged(call: Call, children: MutableList<Call>) = refresh()
        }
        val entry = Entry(call, UUID.randomUUID().toString(), callback)
        entries[call] = entry
        call.registerCallback(callback, main)
        resolveLocalName(entry)
        refresh()
    }
    override fun onCallRemoved(call: Call) {
        entries.remove(call)?.let {
            it.lookupJob?.cancel()
            call.unregisterCallback(it.callback)
        }
        if (dtmfCall === call) { main.removeCallbacks(stopTone); stopTone.run() }
        refresh()
        super.onCallRemoved(call)
    }
    override fun onBringToForeground(showDialpad: Boolean) {
        runCatching { startActivity(Intent(this,CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("dialpad",showDialpad)) }
    }
    override fun onSilenceRinger() {
        voiceSession.suppress(currentRinging()?.id)
        stopVoice()
    }
    override fun onCanAddCallChanged(canAddCall: Boolean) { publish() }
    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        if (Build.VERSION.SDK_INT < 34) {
            muted=audioState.isMuted; legacyMask=audioState.supportedRouteMask; legacyRoute=audioState.route
            publish()
        }
    }
    @RequiresApi(34)
    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) {
        endpoints=availableEndpoints.toList(); publish()
    }
    @RequiresApi(34)
    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) {
        endpointKey=callEndpoint.identifier.toString(); publish()
    }
    @RequiresApi(34)
    override fun onMuteStateChanged(isMuted: Boolean) { muted=isMuted; publish() }

    private fun live() = entries.values.filter { it.call.state != Call.STATE_DISCONNECTED }
    private fun currentRinging() = live().firstOrNull { it.call.state == Call.STATE_RINGING }
    private fun primary() = currentRinging() ?: live().firstOrNull { it.call.state == Call.STATE_ACTIVE } ?: live().firstOrNull()
    private fun presentedNumber(details: Call.Details): String? =
        details.handle?.takeIf {
            details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED && it.scheme == "tel"
        }?.schemeSpecificPart

    private fun resolveLocalName(entry: Entry) {
        val number = presentedNumber(entry.call.details)
        if (number == entry.lookupNumber) return
        entry.lookupJob?.cancel()
        entry.lookupNumber = number
        entry.localName = null
        if (number == null) return
        entry.lookupJob = scope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { lookupContactName(this@AureInCallService, number) }.getOrNull()
            }
            if (entries[entry.call] === entry && entry.lookupNumber == number) {
                entry.localName = name
                refresh()
            }
        }
    }

    private fun contactName(entry: Entry): String? {
        val details = entry.call.details
        if (presentedNumber(details) == null || !hasPermission(Manifest.permission.READ_CONTACTS)) return null
        val systemContact = if (Build.VERSION.SDK_INT >= 30) details.contactDisplayName else null
        return systemContact?.takeIf(String::isNotBlank) ?: entry.localName
    }

    private fun view(entry: Entry): CallView {
        val call = entry.call
        val details = call.details
        val number = presentedNumber(details)
        val networkName = details.callerDisplayName?.takeIf {
            number != null && details.callerDisplayNamePresentation == TelecomManager.PRESENTATION_ALLOWED && it.isNotBlank()
        }
        return CallView(
            entry.id, contactName(entry) ?: networkName, number, call.state,
            details.can(Call.Details.CAPABILITY_HOLD),
            details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE) || call.conferenceableCalls.isNotEmpty(),
            details.connectTimeMillis, number != null && details.failedVerification(),
            if (call.state == Call.STATE_SELECT_PHONE_ACCOUNT) callAccounts(details) else emptyList(),
        )
    }

    private fun publish() {
        val routes=if(Build.VERSION.SDK_INT>=34) endpoints.map { RouteView(it.identifier.toString(),it.endpointName.toString()) } else
            listOf(CallAudioState.ROUTE_EARPIECE to R.string.route_earpiece,CallAudioState.ROUTE_SPEAKER to R.string.route_speaker,CallAudioState.ROUTE_WIRED_HEADSET to R.string.route_wired,CallAudioState.ROUTE_BLUETOOTH to R.string.route_bluetooth)
                .filter { legacyMask and it.first != 0 }.map { RouteView(it.first.toString(),getString(it.second)) }
        CallRuntime.publish(this,CallUi(live().map(::view),muted,routes,if(Build.VERSION.SDK_INT>=34) endpointKey else legacyRoute.toString(), canAddCall()))
    }
    private fun refresh() {
        publish()
        maybeAnnounce()
        updateNotification()
    }
    private data class NotificationKey(val id: String, val state: Int, val title: String)
    private var notificationKey: NotificationKey? = null
    private fun pending(id: String, command: String): PendingIntent = PendingIntent.getBroadcast(this,0,
        Intent(this,CallActionReceiver::class.java).setData("auredial://call/$id/$command".toUri())
            .putExtra("call",id).putExtra("command",command),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun updateNotification() {
        val manager=getSystemService(NotificationManager::class.java)
        val e=primary()
        if(e==null) { manager.cancel(72); notificationKey=null; return }
        val v=view(e); val incoming=v.state==Call.STATE_RINGING
        val title = v.name ?: v.number ?: getString(R.string.unknown_caller)
        val key = NotificationKey(v.id, v.state, title)
        if (Build.VERSION.SDK_INT >= 33 && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        if (notificationKey == key) return
        val newIncoming = incoming && (notificationKey?.id != v.id || notificationKey?.state != Call.STATE_RINGING)
        val open=PendingIntent.getActivity(this,72,Intent(this,CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder=Notification.Builder(this,if(incoming) incomingChannel else ongoingChannel)
            .setSmallIcon(android.R.drawable.sym_action_call).setContentTitle(title)
            .setContentText(getString(if(incoming) R.string.incoming_call else R.string.ongoing_call))
            .setCategory(Notification.CATEGORY_CALL).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setOngoing(true).setContentIntent(open).setOnlyAlertOnce(true)
        if(Build.VERSION.SDK_INT>=31) {
            val person=Person.Builder().setName(title).build()
            builder.setStyle(if(incoming) Notification.CallStyle.forIncomingCall(person,pending(v.id,"reject"),pending(v.id,"answer"))
                else Notification.CallStyle.forOngoingCall(person,pending(v.id,"end")))
        } else {
            if(incoming) builder.addAction(Notification.Action.Builder(null,getString(R.string.answer),pending(v.id,"answer")).build())
            builder.addAction(Notification.Action.Builder(null,getString(if(incoming) R.string.reject else R.string.end_call),pending(v.id,if(incoming) "reject" else "end")).build())
        }
        if(newIncoming && (Build.VERSION.SDK_INT<34 || manager.canUseFullScreenIntent())) builder.setFullScreenIntent(open,true)
        if (Build.VERSION.SDK_INT < 33 || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            if (runCatching { manager.notify(72, builder.build()) }.isSuccess) notificationKey = key
        }
    }

    internal fun visible(value: Boolean) {
        visible = value
        maybeAnnounce()
    }

    private fun stopVoice() {
        main.removeCallbacks(retryVoice)
        announcement?.stop()
    }

    private fun maybeAnnounce() {
        main.removeCallbacks(retryVoice)
        val entry = currentRinging()
        if (ringingVoiceId != entry?.id) {
            stopVoice()
            ringingVoiceId = entry?.id
        }
        val settings = readVoiceSettings(this)
        val silenced = entry?.call?.details?.extras?.getBoolean(Call.EXTRA_SILENT_RINGING_REQUESTED) == true
        val eligible = entry != null && live().size == 1 && settings.enabled && !silenced
        val player = if (eligible && visible) announcement ?: VoicePreview(this).also { announcement = it } else announcement
        when (voiceSession.update(entry?.id, eligible, visible, player?.isReady == true, SystemClock.elapsedRealtime())) {
            AnnouncementAction.STOP -> stopVoice()
            AnnouncementAction.WAIT -> if (player != null && (player.isLoading || player.isReady)) {
                main.postDelayed(retryVoice, 100L)
            }
            AnnouncementAction.SPEAK -> if (entry != null) {
                val identity = speakerIdentity(contactName(entry), presentedNumber(entry.call.details), settings.contactsOnly)
                    ?: getString(R.string.unknown_caller).takeUnless { settings.contactsOnly }
                if (identity != null) player?.playAnnouncement(getString(R.string.announce_caller, identity))
            }
            AnnouncementAction.KEEP -> Unit
        }
    }

    internal fun action(id:String, action:String) {
        val c=entries.values.firstOrNull { it.id==id }?.call ?: return
        if(c.state==Call.STATE_DISCONNECTED) return
        if (c.state == Call.STATE_RINGING && (action == "answer" || action == "reject" || action == "end")) {
            voiceSession.suppress(id)
            stopVoice()
        }
        runCatching {
            when(action) {
                "answer" -> if(c.state==Call.STATE_RINGING) c.answer(VideoProfile.STATE_AUDIO_ONLY)
                "reject" -> if(c.state==Call.STATE_RINGING) c.reject(false,null)
                "end" -> if(c.state==Call.STATE_RINGING) c.reject(false,null) else c.disconnect()
                "mute" -> if(c.state==Call.STATE_ACTIVE) setMuted(!muted)
                "hold" -> if(c.state==Call.STATE_ACTIVE && c.details.can(Call.Details.CAPABILITY_HOLD)) c.hold() else if(c.state==Call.STATE_HOLDING) c.unhold()
                "merge" -> if(c.state==Call.STATE_ACTIVE) {
                    val other=c.conferenceableCalls.firstOrNull()
                    if(other!=null) c.conference(other) else if(c.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)) c.mergeConference()
                }
                else -> if(action.startsWith("dtmf:") && action.length==6 && action.last() in "0123456789*#" && c.state==Call.STATE_ACTIVE) {
                    main.removeCallbacks(stopTone); stopTone.run(); c.playDtmfTone(action.last()); dtmfCall=c; main.postDelayed(stopTone,150L)
                }
            }
        }
    }
    internal fun route(key:String) {
        if(live().none { it.call.state==Call.STATE_ACTIVE }) return
        runCatching {
            if(Build.VERSION.SDK_INT>=34) requestRoute34(key)
            else key.toIntOrNull()?.takeIf { it in listOf(1,2,4,8) && legacyMask and it!=0 }?.let { setAudioRoute(it) }
        }
    }
    @RequiresApi(34)
    private fun requestRoute34(key:String) {
        val endpoint=endpoints.firstOrNull { it.identifier.toString()==key } ?: return
        requestCallEndpointChange(endpoint,mainExecutor,object:OutcomeReceiver<Void,CallEndpointException>{
            override fun onResult(result:Void?) = Unit
            override fun onError(error:CallEndpointException) = Unit
        })
    }
    private fun callAccounts(details: Call.Details): List<Pair<PhoneAccountHandle, String>> {
        val extras = details.extras
        val handles = if (Build.VERSION.SDK_INT >= 33) extras?.getParcelableArrayList(
            Call.AVAILABLE_PHONE_ACCOUNTS, PhoneAccountHandle::class.java,
        ) else extras?.getParcelableArrayList<PhoneAccountHandle>(Call.AVAILABLE_PHONE_ACCOUNTS)
        val available = accounts(this)
        return handles?.mapIndexed { index, handle ->
            handle to (available.firstOrNull { it.first == handle }?.second ?: getString(R.string.sim_number, index + 1))
        } ?: available
    }

    internal fun selectAccount(id: String, account: PhoneAccountHandle) {
        val call = entries.values.firstOrNull { it.id == id }?.call ?: return
        if (call.state == Call.STATE_SELECT_PHONE_ACCOUNT && callAccounts(call.details).any { it.first == account }) {
            runCatching { call.phoneAccountSelected(account, false) }
        }
    }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null); scope.cancel(); stopTone.run(); announcement?.close()
        entries.values.forEach { it.call.unregisterCallback(it.callback) }; entries.clear()
        getSystemService(NotificationManager::class.java).cancel(72)
        CallRuntime.publish(this,CallUi()); if(CallRuntime.service===this) CallRuntime.service=null
        super.onDestroy()
    }
}
