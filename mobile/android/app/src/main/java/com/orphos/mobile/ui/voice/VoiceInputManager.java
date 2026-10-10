package com.orphos.mobile.ui.voice;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.Locale;

/**
 * Wraps on-device {@link SpeechRecognizer} for the Voice CEO feature. Speech-to-text
 * runs on the device; only the resulting transcript is sent to /api/nl-query/ask,
 * satisfying the data-minimisation stance in the Blueprint (§03/§06).
 */
public class VoiceInputManager implements RecognitionListener {

    public interface Listener {
        void onReady();
        void onResult(String transcript);
        void onError(String message);
        void onEnd();
    }

    private final Context context;
    private final Listener listener;
    private SpeechRecognizer recognizer;

    public VoiceInputManager(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public static boolean isAvailable(Context context) {
        return SpeechRecognizer.isRecognitionAvailable(context);
    }

    public void start() {
        if (!isAvailable(context)) {
            listener.onError("Voice input is not available on this device.");
            return;
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(context);
        recognizer.setRecognitionListener(this);

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        recognizer.startListening(intent);
    }

    public void stop() {
        if (recognizer != null) {
            recognizer.stopListening();
        }
    }

    public void destroy() {
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    // --- RecognitionListener ---

    @Override
    public void onReadyForSpeech(Bundle params) {
        listener.onReady();
    }

    @Override
    public void onResults(Bundle results) {
        ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches != null && !matches.isEmpty()) {
            listener.onResult(matches.get(0));
        } else {
            listener.onError("Didn't catch that — try again.");
        }
        listener.onEnd();
    }

    @Override
    public void onError(int error) {
        String msg;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
                msg = "Didn't catch that — try again."; break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                msg = "No speech detected."; break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                msg = "Microphone permission is required."; break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                msg = "Voice recogniser is offline."; break;
            default:
                msg = "Voice input error.";
        }
        listener.onError(msg);
        listener.onEnd();
    }

    @Override public void onBeginningOfSpeech() { }
    @Override public void onRmsChanged(float rmsdB) { }
    @Override public void onBufferReceived(byte[] buffer) { }
    @Override public void onEndOfSpeech() { }
    @Override public void onPartialResults(Bundle partialResults) { }
    @Override public void onEvent(int eventType, Bundle params) { }
}
