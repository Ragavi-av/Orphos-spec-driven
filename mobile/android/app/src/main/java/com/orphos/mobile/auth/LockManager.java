package com.orphos.mobile.auth;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

/**
 * App-lock helper. Unlocks with a biometric (fingerprint/face) when enrolled, otherwise
 * falls back to the device's PIN / pattern / password. If the device has no secure lock at
 * all, there is nothing to enforce and the caller is allowed through.
 */
public final class LockManager {

    public interface Callback {
        void onUnlocked();
        void onCancelled();
    }

    private LockManager() { }

    /** True when the device has a secure lock (biometric or PIN/pattern/password). */
    public static boolean deviceSecure(Context c) {
        KeyguardManager km = (KeyguardManager) c.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    /** True when a biometric can currently be used (enrolled + available). */
    public static boolean biometricAvailable(Context c) {
        int r = BiometricManager.from(c).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_WEAK);
        return r == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * Prompt for a biometric or device-credential unlock. Proceeds automatically when the
     * device has no secure lock (nothing to verify against).
     */
    public static void authenticate(FragmentActivity host, String title, String subtitle,
                                    Callback cb) {
        if (!deviceSecure(host)) { cb.onUnlocked(); return; }

        BiometricPrompt prompt = new BiometricPrompt(host,
                ContextCompat.getMainExecutor(host),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override public void onAuthenticationSucceeded(
                            BiometricPrompt.AuthenticationResult result) {
                        cb.onUnlocked();
                    }
                    @Override public void onAuthenticationError(int code, CharSequence msg) {
                        // Negative/cancel/back or lockout — treat as not unlocked.
                        cb.onCancelled();
                    }
                });

        BiometricPrompt.PromptInfo.Builder b = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle);
        // Allow biometric OR the device credential (PIN/pattern/password).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
        } else {
            //noinspection deprecation
            b.setDeviceCredentialAllowed(true);
        }
        try {
            prompt.authenticate(b.build());
        } catch (Exception e) {
            // If the prompt can't be shown, stay locked (fail closed) rather than
            // letting the caller in unauthenticated. The overlay's Unlock button retries.
            cb.onCancelled();
        }
    }
}
