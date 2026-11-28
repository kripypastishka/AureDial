package dev.auredial.testvoice;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class SmokeVoiceService extends TextToSpeechService {
    private static final String NAME = "auredial-ci-en-US";

    @Override public List<Voice> onGetVoices() {
        return Collections.singletonList(new Voice(NAME, Locale.US, Voice.QUALITY_NORMAL,
                Voice.LATENCY_NORMAL, false, Collections.emptySet()));
    }

    @Override public int onIsValidVoiceName(String voice) {
        return NAME.equals(voice) ? TextToSpeech.SUCCESS : TextToSpeech.ERROR;
    }

    @Override public int onLoadVoice(String voice) {
        return onIsValidVoiceName(voice);
    }

    @Override protected String[] onGetLanguage() {
        return new String[] {"eng", "USA", ""};
    }

    @Override protected int onIsLanguageAvailable(String language, String country, String variant) {
        return "eng".equals(language) ? TextToSpeech.LANG_COUNTRY_AVAILABLE : TextToSpeech.LANG_NOT_SUPPORTED;
    }

    @Override protected int onLoadLanguage(String language, String country, String variant) {
        return onIsLanguageAvailable(language, country, variant);
    }

    @Override protected void onStop() {}

    @Override protected void onSynthesizeText(SynthesisRequest request, SynthesisCallback callback) {
        String text = request.getCharSequenceText().toString();
        if (text.contains("2 0 2 5 5 5 0 1 2 3")) {
            Log.i("AureDialTestVoice", "Incoming number reached offline voice");
        } else if (text.contains("Alex")) {
            Log.i("AureDialTestVoice", "Preview reached offline voice");
        }
        // Silent PCM keeps the automated test independent of the emulator's audio device.
        if (callback.start(16_000, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS) {
            byte[] silence = new byte[Math.min(3_200, callback.getMaxBufferSize())];
            callback.audioAvailable(silence, 0, silence.length);
            callback.done();
        }
    }
}
