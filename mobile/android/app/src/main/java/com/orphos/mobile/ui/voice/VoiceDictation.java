package com.orphos.mobile.ui.voice;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonObject;
import com.orphos.mobile.data.api.ApiClient;

import java.io.File;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.RequestBody;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/**
 * Microphone dictation for Ask Orphos, transcribed by the platform's own Whisper
 * module via voice-service.
 *
 * <p>Deliberately NOT {@link android.speech.SpeechRecognizer}, which
 * {@link VoiceInputManager} uses for Scribbles. That runs on-device but on most
 * builds is Google's recogniser, so the audio leaves the estate; this path keeps
 * every clip inside the deployment's own Whisper container, and gives the same
 * transcript quality on a device whose recogniser is absent or locale-limited.
 * The two coexist — Scribbles keeps its on-device path, Ask Orphos uses this.</p>
 *
 * <p>A TOGGLE, not press-and-hold: the user needs to speak a whole question with
 * their attention on the screen, not on keeping a finger down.</p>
 *
 * <p>Threading: {@link MediaRecorder} is driven on the caller's (main) thread,
 * and every {@link Listener} callback is delivered there too, so the Activity can
 * touch views directly without posting.</p>
 */
public class VoiceDictation {

    public interface Listener {
        /** Recording actually began — the mic is live. */
        void onRecordingStarted();
        /** Recording stopped; the clip is being transcribed. */
        void onTranscribing();
        /** A transcript came back. Never empty. */
        void onTranscript(String text);
        /** Something failed; {@code message} is user-facing. */
        void onVoiceError(String message);
    }

    private final Context context;
    private final ApiClient apiClient;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaRecorder recorder;
    private File clip;
    private boolean recording;
    private boolean transcribing;
    /** The upload in flight, kept ONLY so the user can abandon a slow transcription. */
    private Call<JsonObject> inFlightCall;
    /** Set while cancelling, so the resulting failure is not reported as a fault. */
    private boolean cancelling;

    public VoiceDictation(Context context, ApiClient apiClient, Listener listener) {
        this.context = context.getApplicationContext();
        this.apiClient = apiClient;
        this.listener = listener;
    }

    public boolean isRecording()    { return recording; }
    public boolean isTranscribing() { return transcribing; }

    /**
     * Start if idle, stop-and-transcribe if recording, ABANDON if already transcribing.
     *
     * <p>That last case matters: transcription can stall on a slow or unreachable
     * backend, and without it the composer sits on "Transcribing…" with no way out
     * until the HTTP timeout expires. The same gesture that started the dictation
     * gets the user out of it.</p>
     */
    public void toggle() {
        if (transcribing) { abandon(); return; }
        if (recording) stop(); else start();
    }

    /** Drops an in-flight transcription at the user's request. */
    private void abandon() {
        if (!transcribing || inFlightCall == null) return;
        cancelling = true;
        inFlightCall.cancel();   // surfaces as onFailure, which clears the state
    }

    // ── Recording ──────────────────────────────────────────────────────────────

    private void start() {
        if (recording) return;
        try {
            clip = File.createTempFile("dictation", ".m4a", context.getCacheDir());

            recorder = newWhisperRecorder(context, clip);
            recorder.prepare();
            recorder.start();

            recording = true;
            listener.onRecordingStarted();
        } catch (Exception e) {
            releaseRecorder();
            deleteClip();
            recording = false;
            listener.onVoiceError("Could not start recording.");
        }
    }

    /**
     * A MediaRecorder configured for Whisper, writing to {@code out} (an .m4a file), not yet
     * prepared. Shared with the Voice Note recorder so both upload the same format.
     */
    public static MediaRecorder newWhisperRecorder(Context context, File out) {
        MediaRecorder r = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new MediaRecorder(context)
                : new MediaRecorder();
        r.setAudioSource(MediaRecorder.AudioSource.MIC);
        // AAC in an MP4 container, saved as .m4a — a format Whisper's ffmpeg
        // demuxes directly, so no transcoding step is needed on either side.
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        // 16 kHz mono is what Whisper resamples to internally anyway; recording
        // higher just makes a bigger upload for identical accuracy.
        r.setAudioSamplingRate(16000);
        r.setAudioChannels(1);
        r.setAudioEncodingBitRate(32000);
        r.setOutputFile(out.getAbsolutePath());
        return r;
    }

    private void stop() {
        if (!recording) return;
        recording = false;

        boolean usable = true;
        try {
            recorder.stop();
        } catch (RuntimeException e) {
            // MediaRecorder.stop() throws when it is stopped almost immediately after
            // start() — no frames were written, and the output file is a zero-length
            // or malformed MP4. Treat it as "too short" rather than as a crash.
            usable = false;
        } finally {
            releaseRecorder();
        }

        if (!usable || clip == null || !clip.exists() || clip.length() == 0L) {
            deleteClip();
            listener.onVoiceError("That was too short to hear. Hold the thought a moment longer.");
            return;
        }

        transcribing = true;
        listener.onTranscribing();
        upload(clip);
    }

    /** Stops the mic and drops any upload — for onPause/onDestroy. */
    public void cancel() {
        if (recording) {
            recording = false;
            try { recorder.stop(); } catch (RuntimeException ignored) { /* nothing captured */ }
            releaseRecorder();
        }
        // An upload outliving the screen would deliver its transcript into a composer
        // the user has already left, and hold the clip open until it completed.
        if (inFlightCall != null) {
            cancelling = true;
            inFlightCall.cancel();
        }
        deleteClip();
    }

    private void releaseRecorder() {
        if (recorder != null) {
            try { recorder.release(); } catch (Exception ignored) { }
            recorder = null;
        }
    }

    private void deleteClip() {
        if (clip != null) {
            // Best-effort: the clip is a temp file in the app cache, and a voice
            // recording is more personal than the text it becomes — nothing keeps it
            // once the transcript is in hand.
            //noinspection ResultOfMethodCallIgnored
            clip.delete();
            clip = null;
        }
    }

    // ── Transcription ──────────────────────────────────────────────────────────

    private void upload(File file) {
        RequestBody body = RequestBody.create(file, MediaType.parse("audio/mp4"));
        // The filename's extension is what tells Whisper how to decode this.
        MultipartBody.Part part = MultipartBody.Part.createFormData("audio", "dictation.m4a", body);

        inFlightCall = apiClient.orphos().voiceTranscribe(part, dictationLanguage());
        inFlightCall.enqueue(new Callback<JsonObject>() {
            @Override
            public void onResponse(Call<JsonObject> call, Response<JsonObject> response) {
                finish(() -> {
                    if (!response.isSuccessful() || response.body() == null) {
                        listener.onVoiceError(response.code() == 403
                                ? "Voice is not enabled for your organisation."
                                : "Could not transcribe that. Please try again.");
                        return;
                    }
                    String text = response.body().has("text")
                            ? response.body().get("text").getAsString().trim() : "";
                    if (text.isEmpty()) {
                        listener.onVoiceError("No speech was recognised — try again, closer to the mic.");
                    } else {
                        listener.onTranscript(text);
                    }
                });
            }

            @Override
            public void onFailure(Call<JsonObject> call, Throwable t) {
                // A user-initiated cancel arrives here too, as an IOException from
                // Call.cancel(). Reporting that as a fault would blame the platform for
                // something the user just asked for, so it clears silently.
                boolean userCancelled = cancelling;
                finish(() -> {
                    if (userCancelled) return;
                    listener.onVoiceError(t instanceof java.net.SocketTimeoutException
                            ? "Transcription timed out. Check your connection and try again."
                            : "Speech recognition is unavailable right now.");
                });
            }
        });
    }

    /**
     * The device language as an ISO-639-1 hint for Whisper, or null to let it detect.
     * Short clips are too little audio for reliable detection, so the user's own
     * locale is a far better guess than Whisper's.
     */
    public static String dictationLanguage() {
        String lang = java.util.Locale.getDefault().getLanguage();
        // Locale still reports the legacy codes for a few languages (iw, in, ji);
        // Whisper wants the ISO-639-1 ones.
        switch (lang) {
            case "iw": return "he";
            case "in": return "id";
            case "ji": return "yi";
            default:   return lang == null || lang.length() != 2 ? null : lang;
        }
    }

    /** Clears the in-flight state, drops the clip, then runs {@code then} on the main thread. */
    private void finish(Runnable then) {
        main.post(() -> {
            transcribing = false;
            cancelling = false;
            inFlightCall = null;
            deleteClip();
            then.run();
        });
    }
}
