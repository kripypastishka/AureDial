package dev.auredial.app

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Activity-owned, main-thread-only preview. No call audio or routing changes. */
class VoicePreview(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val status = MutableStateFlow(R.string.voice_loading)
    val state = status.asStateFlow()
    private var engine: TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var utterance: String? = null
    private var focus: AudioFocusRequest? = null
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
                    override fun onError(id: String?, errorCode: Int) { complete(id, R.string.voice_error) }
                    override fun onStop(id: String?, interrupted: Boolean) { complete(id, R.string.voice_ready) }
                }) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        status.value = if (ready) R.string.voice_ready else R.string.voice_unavailable
    }
    private fun complete(id: String?, result: Int) {
        main.post { if (!closed && id != null && id == utterance) finish(result) }
    }
    fun play() { playText(context.getString(R.string.preview_text),false) }
    fun playAnnouncement(text:String):Boolean = playText(text,true)
    private fun playText(text:String, ringing:Boolean):Boolean {
        if (closed || !ready) return false
        finish(R.string.voice_ready)
        if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL ||
            (audio.mode != AudioManager.MODE_NORMAL && !(ringing && audio.mode == AudioManager.MODE_RINGTONE))) {
            status.value = R.string.voice_silent
            return false
        }
        val id = UUID.randomUUID().toString()
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
        utterance = id
        status.value = R.string.voice_playing
        val success = runCatching {
            engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS
        }.getOrDefault(false)
        if (success) main.postDelayed(timeout, 10000L) else finish(R.string.voice_error)
        return success
    }
    private fun finish(result: Int) {
        main.removeCallbacks(timeout)
        utterance = null
        runCatching { engine?.stop() }
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
        status.value = result
    }
    fun stop() { if (!closed && ready) finish(R.string.voice_ready) }
    fun close() {
        if (closed) return
        stop()
        closed = true
        runCatching { engine?.shutdown() }
        engine = null
    }
}
