package dev.auredial.app

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Main-thread-only offline speech used by the settings preview and the in-call service. */
class VoicePreview(private val context: Context, private val incomingCall: Boolean = false) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val attributes = AudioAttributes.Builder()
        .setUsage(if (incomingCall) AudioAttributes.USAGE_NOTIFICATION_RINGTONE else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val status = MutableStateFlow(R.string.voice_loading)
    val state = status.asStateFlow()
    val isReady: Boolean get() = ready && !closed
    val isLoading: Boolean get() = status.value == R.string.voice_loading
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var utterance: String? = null
    private var focus: AudioFocusRequest? = null
    private var observingAudio = false
    private val audioObserver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (utterance == null) return
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) finish(R.string.voice_interrupted)
            else if (!systemAllowsSpeech()) finish(R.string.voice_silent)
        }
    }
    private val timeout = Runnable { finish(R.string.voice_error) }

    init {
        engine = TextToSpeech(context.applicationContext) { result ->
            main.post {
                if (!closed) initialize(result)
            }
        }
    }
    private fun initialize(result: Int) {
        val tts = engine
        if (result != TextToSpeech.SUCCESS || tts == null) {
            status.value = R.string.voice_unavailable
            return
        }
        val locale = context.resources.configuration.locales[0]
        ready = runCatching {
            val voice = tts.voices.orEmpty().filter {
                !it.isNetworkConnectionRequired && it.locale.language == locale.language &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
            }.sortedWith(compareByDescending<android.speech.tts.Voice> { it.locale == locale }
                .thenByDescending { it.quality }.thenBy { it.name }).firstOrNull()
            voice != null && tts.setVoice(voice) == TextToSpeech.SUCCESS &&
                tts.setAudioAttributes(attributes) == TextToSpeech.SUCCESS &&
                tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) = Unit
                    override fun onDone(id: String?) { complete(id, R.string.voice_ready) }
                    @Deprecated("Legacy callback")
                    override fun onError(id: String?) { complete(id, R.string.voice_error) }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onError(id: String?, errorCode: Int) { complete(id, R.string.voice_error) }
                    @Suppress("OVERRIDE_DEPRECATION")
                    override fun onStop(id: String?, interrupted: Boolean) { complete(id, R.string.voice_ready) }
                }) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        status.value = if (ready) R.string.voice_ready else R.string.voice_unavailable
    }
    private fun complete(id: String?, result: Int) {
        main.post { if (!closed && id != null && id == utterance) finish(result) }
    }
    private fun systemAllowsSpeech(): Boolean =
        audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL

    private fun observeAudio(): Boolean = runCatching {
        ContextCompat.registerReceiver(context, audioObserver, IntentFilter().apply {
            addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        observingAudio = true
    }.isSuccess

    fun play() { playText(context.getString(R.string.preview_text)) }

    fun openVoiceInstaller(): Boolean = runCatching {
        val intent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).apply {
            engine?.defaultEngine?.let(::setPackage)
        }
        context.startActivity(intent)
    }.isSuccess

    fun playAnnouncement(text: String): Boolean = playText(text)

    private fun playText(text: String): Boolean {
        if (closed || !ready) return false
        if (utterance != null) finish(R.string.voice_ready)
        if (!systemAllowsSpeech()) {
            status.value = R.string.voice_silent
            return false
        }
        val mode = audio.mode
        if (mode != AudioManager.MODE_NORMAL && !(incomingCall && mode == AudioManager.MODE_RINGTONE)) {
            status.value = R.string.voice_interrupted
            return false
        }
        val id = UUID.randomUUID().toString()
        // Incoming speech is a brief call alert on the ringtone stream.
        // Telecom retains its ring audio focus. The settings preview uses media focus.
        if (!incomingCall) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener({ change ->
                    if (change < 0 && utterance == id) finish(R.string.voice_focus_denied)
                }, main).build()
            if (runCatching { audio.requestAudioFocus(request) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
                != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                status.value = R.string.voice_focus_denied
                return false
            }
            focus = request
        }
        if (!observeAudio() || !systemAllowsSpeech()) {
            finish(R.string.voice_interrupted)
            return false
        }
        utterance = id
        status.value = R.string.voice_playing
        val success = runCatching {
            val tts = engine ?: return@runCatching false
            tts.setSpeechRate(readVoiceSettings(context).rate / 100f) == TextToSpeech.SUCCESS &&
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (success) main.postDelayed(timeout, 10000L) else finish(R.string.voice_error)
        return success
    }
    private fun finish(result: Int) {
        main.removeCallbacks(timeout)
        utterance = null
        if (observingAudio) {
            context.unregisterReceiver(audioObserver)
            observingAudio = false
        }
        runCatching { engine?.stop() }
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
        status.value = result
    }
    fun stop() {
        if (!closed && utterance != null) finish(R.string.voice_ready)
    }
    fun close() {
        if (closed) return
        stop()
        closed = true
        runCatching { engine?.shutdown() }
        engine = null
    }
}

internal data class VoiceSettings(val enabled: Boolean, val contactsOnly: Boolean, val rate: Int)

internal fun voicePreferences(context: Context) = context.getSharedPreferences("voice", Context.MODE_PRIVATE)

internal fun readVoiceSettings(context: Context): VoiceSettings = voicePreferences(context).let {
    VoiceSettings(
        enabled = it.getBoolean("announce", true),
        contactsOnly = it.getBoolean("contacts_only", false),
        rate = it.getInt("rate", 100).coerceIn(75, 125),
    )
}
