package com.orphos.mobile.auth;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Process-wide app-lock state. The app counts as backgrounded only when NONE of its
 * activities is started — moving between the app's own screens (MainActivity → a detail
 * screen → back), switching tabs, rotating or changing language never drops the unlock.
 * Only a genuine background/close (or a sign-out) makes the next MainActivity start
 * prompt again. Registered once from {@link com.orphos.mobile.OrphosApp}; main thread only.
 */
public final class AppLockState implements Application.ActivityLifecycleCallbacks {

    /** Number of this app's activities currently between onStart and onStop. */
    private int startedActivities = 0;
    /** True once the user has unlocked in the current foreground session. */
    private boolean unlocked = false;
    /** True while a biometric / device-credential prompt is up (its UI may stop our activities). */
    private boolean authInProgress = false;

    public boolean isUnlocked() { return unlocked; }

    public void markUnlocked() { unlocked = true; }

    /** Forget the unlock (e.g. on sign-out) so the next session re-authenticates. */
    public void lock() { unlocked = false; }

    public boolean isAuthInProgress() { return authInProgress; }

    public void setAuthInProgress(boolean inProgress) { authInProgress = inProgress; }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        activityStarted();
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        activityStopped(activity.isChangingConfigurations());
    }

    void activityStarted() {
        startedActivities++;
    }

    void activityStopped(boolean changingConfigurations) {
        startedActivities = Math.max(0, startedActivities - 1);
        // Last visible activity gone and it's not a rotation/locale recreate, and not the
        // device-credential (PIN/pattern) screen that the unlock prompt itself opened:
        // the user has really left the app, so returning must re-authenticate.
        if (startedActivities == 0 && !changingConfigurations && !authInProgress) {
            unlocked = false;
        }
    }

    @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) { }
    @Override public void onActivityResumed(@NonNull Activity a) { }
    @Override public void onActivityPaused(@NonNull Activity a) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) { }
    @Override public void onActivityDestroyed(@NonNull Activity a) { }
}
