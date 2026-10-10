package com.orphos.mobile.ui.scribbles;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.orphos.mobile.R;

import java.io.File;
import java.util.Locale;

/**
 * Play / pause / seek for a voice note's recording on the capture screen: a play button,
 * a seek bar and "elapsed / length". Owned by the Activity, which must {@link #release()}
 * it in onDestroy and {@link #pause()} it in onPause.
 */
final class AudioPlayback {

    interface Failure { void onUnplayable(); }

    private final View play;
    private final ImageView playIcon;
    private final SeekBar seek;
    private final TextView time;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaPlayer player;
    private boolean seeking;

    AudioPlayback(View play, ImageView playIcon, SeekBar seek, TextView time) {
        this.play = play;
        this.playIcon = playIcon;
        this.seek = seek;
        this.time = time;
    }

    /** Prepares {@code file}; the controls enable once it is ready, or {@code failure} runs. */
    void load(File file, Failure failure) {
        release();
        if (file == null || !file.exists()) { failure.onUnplayable(); return; }
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        try {
            mp.setDataSource(file.getAbsolutePath());
            mp.setOnPreparedListener(p -> {
                if (player != p) return;
                seek.setMax(p.getDuration());
                seek.setProgress(0);
                play.setEnabled(true);
                seek.setEnabled(true);
                show(0, p.getDuration());
            });
            mp.setOnCompletionListener(p -> {
                handler.removeCallbacks(progress);
                seek.setProgress(0);
                p.seekTo(0);
                showPlaying(false);
                show(0, p.getDuration());
            });
            mp.setOnErrorListener((p, what, extra) -> {
                release();
                failure.onUnplayable();
                return true;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            release();
            failure.onUnplayable();
            return;
        }

        play.setOnClickListener(v -> toggle());
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if (fromUser && player != null) show(value, player.getDuration());
            }
            @Override public void onStartTrackingTouch(SeekBar s) { seeking = true; }
            @Override public void onStopTrackingTouch(SeekBar s) {
                seeking = false;
                if (player != null) player.seekTo(s.getProgress());
            }
        });
    }

    void pause() {
        if (player != null && player.isPlaying()) {
            player.pause();
            handler.removeCallbacks(progress);
            showPlaying(false);
        }
    }

    void release() {
        handler.removeCallbacks(progress);
        if (player != null) {
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        play.setEnabled(false);
        seek.setEnabled(false);
        showPlaying(false);
    }

    private void toggle() {
        if (player == null) return;
        if (player.isPlaying()) {
            pause();
        } else {
            player.start();
            showPlaying(true);
            handler.post(progress);
        }
    }

    private final Runnable progress = new Runnable() {
        @Override public void run() {
            if (player == null) return;
            if (!seeking) {
                seek.setProgress(player.getCurrentPosition());
                show(player.getCurrentPosition(), player.getDuration());
            }
            handler.postDelayed(this, 200);
        }
    };

    private void showPlaying(boolean playing) {
        playIcon.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        play.setContentDescription(play.getContext().getString(playing ? R.string.audio_pause : R.string.audio_play));
    }

    private void show(int posMs, int durMs) {
        time.setText(clock(posMs) + " / " + clock(Math.max(0, durMs)));
    }

    private static String clock(int ms) {
        int s = ms / 1000;
        return String.format(Locale.ROOT, "%02d:%02d", s / 60, s % 60);
    }
}
