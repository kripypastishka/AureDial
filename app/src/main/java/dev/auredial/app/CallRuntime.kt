package dev.auredial.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.InCallService
import android.telecom.PhoneAccountHandle
import android.telecom.VideoProfile
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

data class CallView(val id: String, val name: String?, val number: String?, val state: Int,
                    val canHold: Boolean, val canMerge: Boolean, val connectedAt: Long)
data class RouteView(val key: String, val name: String)
data class CallUi(val calls: List<CallView> = emptyList(), val muted: Boolean = false,
                  val routes: List<RouteView> = emptyList(), val currentRoute: String? = null)

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

@Suppress("DEPRECATION")
class AureInCallService : InCallService() {
    private data class Entry(val call: Call, val id: String, val callback: Call.Callback)
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
    private var announcedId: String? = null
    private var attemptId: String? = null
    private var attempts = 0
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
            override fun onDetailsChanged(call: Call, details: Call.Details) = refresh()
            override fun onConferenceableCallsChanged(call: Call, conferenceableCalls: MutableList<Call>) = refresh()
            override fun onChildrenChanged(call: Call, children: MutableList<Call>) = refresh()
        }
        entries[call] = Entry(call,UUID.randomUUID().toString(),callback)
        call.registerCallback(callback,main)
        refresh()
    }
    override fun onCallRemoved(call: Call) {
        entries.remove(call)?.let { call.unregisterCallback(it.callback) }
        if (dtmfCall === call) { main.removeCallbacks(stopTone); stopTone.run() }
        refresh()
        super.onCallRemoved(call)
    }
    override fun onBringToForeground(showDialpad: Boolean) {
        runCatching { startActivity(Intent(this,CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("dialpad",showDialpad)) }
    }
    override fun onSilenceRinger() { announcement?.stop(); announcedId = currentRinging()?.id; main.removeCallbacks(retryVoice) }
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
    private fun view(e: Entry): CallView {
        val c=e.call; val d=c.details
        val number=d.handle?.takeIf { d.handlePresentation == android.telecom.TelecomManager.PRESENTATION_ALLOWED && it.scheme=="tel" }?.schemeSpecificPart
        val name=if (d.callerDisplayNamePresentation==android.telecom.TelecomManager.PRESENTATION_ALLOWED) d.callerDisplayName?.takeIf { it.isNotBlank() } else null
        return CallView(e.id,name,number,c.state,d.can(Call.Details.CAPABILITY_HOLD),d.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)||c.conferenceableCalls.isNotEmpty(),d.connectTimeMillis)
    }
    private fun publish() {
        val routes=if(Build.VERSION.SDK_INT>=34) endpoints.map { RouteView(it.identifier.toString(),it.endpointName.toString()) } else
            listOf(CallAudioState.ROUTE_EARPIECE to R.string.route_earpiece,CallAudioState.ROUTE_SPEAKER to R.string.route_speaker,CallAudioState.ROUTE_WIRED_HEADSET to R.string.route_wired,CallAudioState.ROUTE_BLUETOOTH to R.string.route_bluetooth)
                .filter { legacyMask and it.first != 0 }.map { RouteView(it.first.toString(),getString(it.second)) }
        CallRuntime.publish(this,CallUi(live().map(::view),muted,routes,if(Build.VERSION.SDK_INT>=34) endpointKey else legacyRoute.toString()))
    }
    private fun refresh() {
        publish()
        if (currentRinging()==null || live().size!=1) {
            main.removeCallbacks(retryVoice); announcement?.stop(); attemptId=null
        } else maybeAnnounce()
        updateNotification()
    }
    private var notificationKey: String? = null
    private fun pending(id: String, command: String): PendingIntent = PendingIntent.getBroadcast(this,0,
        Intent(this,CallActionReceiver::class.java).setData(Uri.parse("auredial://call/"+id+"/"+command))
            .putExtra("call",id).putExtra("command",command),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun updateNotification() {
        val manager=getSystemService(NotificationManager::class.java)
        val e=primary()
        if(e==null) { manager.cancel(72); notificationKey=null; return }
        val v=view(e); val incoming=v.state==Call.STATE_RINGING
        val key=v.id+":"+incoming+":"+v.state
        if(notificationKey==key) return
        val newIncoming=incoming && notificationKey?.startsWith(v.id+":")!=true
        notificationKey=key
        val title=v.name ?: v.number ?: getString(R.string.unknown_caller)
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
        runCatching { manager.notify(72,builder.build()) }
    }

    internal fun visible(value:Boolean) {
        visible=value
        if(value) maybeAnnounce() else { main.removeCallbacks(retryVoice); announcement?.stop() }
    }
    private fun maybeAnnounce() {
        val e=currentRinging() ?: return
        if(!visible || live().size!=1 || announcedId==e.id || !voiceEnabled(this)) return
        if(attemptId!=e.id) { attemptId=e.id; attempts=0 }
        if(attempts++>=20) return
        val player=announcement ?: VoicePreview(this).also { announcement=it }
        val v=view(e)
        val spoken=(v.name ?: v.number?.map { it.toString() }?.joinToString(" ") ?: getString(R.string.unknown_caller)).take(120)
        if(player.playAnnouncement(getString(R.string.announce_caller,spoken))) announcedId=e.id
        else { main.removeCallbacks(retryVoice); main.postDelayed(retryVoice,150L) }
    }

    internal fun action(id:String, action:String) {
        val c=entries.values.firstOrNull { it.id==id }?.call ?: return
        if(c.state==Call.STATE_DISCONNECTED) return
        if(action=="answer" || action=="reject" || action=="end") { announcement?.stop(); main.removeCallbacks(retryVoice); announcedId=id }
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
    internal fun selectAccount(id:String, account:PhoneAccountHandle) {
        val call=entries.values.firstOrNull { it.id==id }?.call ?: return
        if(call.state==Call.STATE_SELECT_PHONE_ACCOUNT) runCatching { call.phoneAccountSelected(account,false) }
    }
    override fun onDestroy() {
        main.removeCallbacksAndMessages(null); stopTone.run(); announcement?.close()
        entries.values.forEach { it.call.unregisterCallback(it.callback) }; entries.clear()
        getSystemService(NotificationManager::class.java).cancel(72)
        CallRuntime.publish(this,CallUi()); if(CallRuntime.service===this) CallRuntime.service=null
        super.onDestroy()
    }
}

internal fun voiceEnabled(context:Context)=context.getSharedPreferences("voice",Context.MODE_PRIVATE).getBoolean("announce",false)
