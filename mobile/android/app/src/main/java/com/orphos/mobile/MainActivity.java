package com.orphos.mobile;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.orphos.mobile.databinding.ActivityMainBinding;
import com.orphos.mobile.ui.advisors.AdvisorsHostFragment;
import com.orphos.mobile.ui.advisors.AiAdvisorsFragment;
import com.orphos.mobile.ui.auth.LoginActivity;
import com.orphos.mobile.ui.financials.FinancialsFragment;
import com.orphos.mobile.ui.metrics.CompanyMetricsFragment;
import com.orphos.mobile.ui.metrics.RoleDashboardFragment;
import com.orphos.mobile.ui.home.HomeFragment;
import com.orphos.mobile.ui.scribbles.ScribblesFragment;

/**
 * Single-activity host for the v2 (Figma "ORPHOS-1") design. Five bottom-nav
 * destinations, each rendering its own in-screen header. A floating AI orb opens
 * the AI Advisors surface. Requires an authenticated (or demo) session.
 */
public class MainActivity extends AppCompatActivity {

    /** Inner Business tab to open on arrival (see {@link #businessTabIntent}). */
    private static final String EXTRA_BUSINESS_TAB = "com.orphos.mobile.extra.BUSINESS_TAB";

    private ActivityMainBinding binding;
    /**
     * App-wide unlock state. Kept at process level, not per activity instance: opening one
     * of the app's own screens stops this activity, and a per-instance flag reset in
     * onStop() re-prompted on every return. Only a real background/close re-locks.
     */
    private final com.orphos.mobile.auth.AppLockState lock = OrphosApp.get().lockState();
    /** Inner tab the Business host should open on next show (-1 = none). Set by openBusinessTab. */
    private int pendingBusinessTab = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!OrphosApp.get().session().isAuthenticated()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // Keep the bottom nav clear of the gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNav, (v, insets) -> {
            Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), nav.bottom);
            return insets;
        });

        binding.demoBanner.setVisibility(
                OrphosApp.get().config().isDemoMode() ? View.VISIBLE : View.GONE);
        // Tapping the demo banner opens Settings (role, app lock, endpoint).
        binding.demoBanner.setOnClickListener(v ->
                startActivity(new Intent(this, com.orphos.mobile.ui.settings.SettingsActivity.class)));

        applyWorkTab();

        binding.lockUnlock.setOnClickListener(v -> unlock());

        // RBAC: hide bottom-nav destinations this persona can't access.
        applyNavGating();

        // Cold start with an existing token: refresh persona / roles / access grants from the
        // backend, then re-apply nav gating. Never overrides a manual Settings role here.
        if (!OrphosApp.get().config().isDemoMode()) {
            OrphosApp.get().repo().loadProfile(false, (ok, fromMock) -> {
                if (binding == null) return;
                applyWorkTab();
                applyNavGating();
            });
        }

        // Apply the tenant's Mobile Access config (biometric lock, alerts, status thresholds,
        // refresh interval) and enforce the access gate.
        OrphosApp.get().repo().loadConfig((ok, fromMock) -> {
            if (binding == null) return;
            if (!OrphosApp.get().config().isDemoMode()
                    && !OrphosApp.get().config().mobileAccessEnabled()) {
                showAccessDisabled();
            }
        });

        if (savedInstanceState == null) {
            show(new HomeFragment());
        }
        // Lock is gated in onStart() so it also re-prompts when the app returns
        // from the background, not only on a cold start.

        binding.bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) {
                show(new HomeFragment());
            } else if (id == R.id.nav_advisors) {
                show(new AiAdvisorsFragment());
            } else if (id == R.id.nav_business) {
                show(new AdvisorsHostFragment());
            } else if (id == R.id.nav_scribbles) {
                show(new ScribblesFragment());
            } else if (id == R.id.nav_financials) {
                com.orphos.mobile.core.role.Role role = currentRole();
                show(role.showsCompanyMetrics() ? new CompanyMetricsFragment()
                        : role.showsRoleDashboard() ? new RoleDashboardFragment()
                        : new FinancialsFragment());
            }
            return true;
        });

        if (savedInstanceState == null) handleBusinessTabExtra(getIntent());
    }

    /** Admin has disabled mobile access for this tenant — block entry and offer sign-out. */
    private void showAccessDisabled() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.access_disabled_title)
                .setMessage(R.string.access_disabled_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.sign_out, (d, w) ->
                        com.orphos.mobile.auth.AuthManager.signOut(this))
                .show();
    }

    /**
     * Intent that brings the existing main screen back to the front (closing the screens
     * opened above it) on the Business tab's given inner tab — the header bell on a detail
     * screen uses it to reach Notifications exactly as it does from Home.
     */
    public static Intent businessTabIntent(@NonNull android.content.Context context, int inner) {
        return new Intent(context, MainActivity.class)
                .putExtra(EXTRA_BUSINESS_TAB, inner)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    @Override
    protected void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (binding != null) handleBusinessTabExtra(intent);
    }

    private void handleBusinessTabExtra(Intent intent) {
        int inner = intent.getIntExtra(EXTRA_BUSINESS_TAB, -1);
        if (inner < 0) return;
        intent.removeExtra(EXTRA_BUSINESS_TAB);
        openBusinessTab(inner);
    }

    /** Open the Business tab at a given inner tab (0=Approvals, 1=Tasks, 2=Notifications). */
    public void openBusinessTab(int inner) {
        pendingBusinessTab = inner;
        selectTab(R.id.nav_business);
    }

    /** Read-and-clear the inner Business tab requested via {@link #openBusinessTab(int)}. */
    public int consumeBusinessInnerTab() {
        int t = pendingBusinessTab;
        pendingBusinessTab = -1;
        return t;
    }

    private static com.orphos.mobile.core.role.Role currentRole() {
        return com.orphos.mobile.core.role.Role.of(OrphosApp.get().config().userRole());
    }

    /**
     * Work tab: always labelled "Metrics" with one icon; its content is still role-based —
     * company metrics (CEO), finance (CFO), people dashboard (CHRO), and so on.
     */
    private void applyWorkTab() {
        com.orphos.mobile.core.role.Role role = currentRole();
        android.view.MenuItem work = binding.bottomNav.getMenu().findItem(R.id.nav_financials);
        work.setTitle(role.workTabTitleRes());
        work.setIcon(role.workTabIconRes());
    }

    /**
     * Hides bottom-nav destinations the current persona can't access (Home is always shown).
     * If the currently selected tab is no longer visible, falls back to Home.
     */
    private void applyNavGating() {
        com.orphos.mobile.core.role.MobileAccess access =
                new com.orphos.mobile.core.role.MobileAccess(OrphosApp.get().accessState());
        android.view.Menu menu = binding.bottomNav.getMenu();

        boolean advisors = access.canSee(com.orphos.mobile.core.role.MobileAccess.ASK_ORPHOS);
        boolean business = access.canSee(com.orphos.mobile.core.role.MobileAccess.DECISION_LOG)
                || access.canSee(com.orphos.mobile.core.role.MobileAccess.TEAM_INTEL);
        boolean scribbles = access.canSee(com.orphos.mobile.core.role.MobileAccess.CAPTURES);
        boolean financials = access.canSee(com.orphos.mobile.core.role.MobileAccess.FINANCIALS);

        menu.findItem(R.id.nav_home).setVisible(true);
        menu.findItem(R.id.nav_advisors).setVisible(advisors);
        menu.findItem(R.id.nav_business).setVisible(business);
        menu.findItem(R.id.nav_scribbles).setVisible(scribbles);
        menu.findItem(R.id.nav_financials).setVisible(financials);

        // If the active tab was just hidden, return to Home (always visible).
        android.view.MenuItem selected = menu.findItem(binding.bottomNav.getSelectedItemId());
        if (selected != null && !selected.isVisible()) {
            binding.bottomNav.setSelectedItemId(R.id.nav_home);
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        // binding is null only when onCreate redirected to Login (unauthenticated).
        if (binding != null) maybeLock();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // super.onStop() has already told AppLockState. If that was a genuine backgrounding
        // (no other app screen started, no auth prompt up), cover the content now so it
        // is hidden in the recents thumbnail and on return until the user unlocks.
        // Opening another app screen keeps the unlock, so the overlay stays hidden.
        if (binding != null && !lock.isUnlocked() && !lock.isAuthInProgress()) {
            binding.lockOverlay.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // A recreate (rotation / language) while the prompt is up detaches its callback from
        // this instance; clear the flag so the new instance can prompt instead of the app
        // staying stuck with backgrounding ignored.
        if (binding != null && lock.isAuthInProgress()) lock.setAuthInProgress(false);
    }

    /** Require a biometric / device-credential unlock before the app is usable. */
    private void maybeLock() {
        com.orphos.mobile.core.config.AppConfig cfg = OrphosApp.get().config();
        if (!cfg.appLockEnabled() || !com.orphos.mobile.auth.LockManager.deviceSecure(this)) {
            lock.markUnlocked();
            binding.lockOverlay.setVisibility(View.GONE);
            return;
        }
        if (lock.isUnlocked()) {                  // already unlocked this foreground session
            binding.lockOverlay.setVisibility(View.GONE);
            return;
        }
        if (lock.isAuthInProgress()) return;      // a prompt is already showing
        binding.lockOverlay.setVisibility(View.VISIBLE);
        if (!cfg.lockSetupDone()) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.lock_setup_title)
                    .setMessage(R.string.lock_setup_msg)
                    .setCancelable(false)
                    .setPositiveButton(R.string.lock_setup_enable, (d, w) -> {
                        cfg.setLockSetupDone(true);
                        if (!com.orphos.mobile.auth.LockManager.biometricAvailable(this)) {
                            openBiometricEnrollment(); // returns via onStart() → maybeLock()
                        } else {
                            unlock();
                        }
                    })
                    .setNegativeButton(R.string.lock_setup_skip, (d, w) -> {
                        cfg.setLockSetupDone(true); // still unlocks via device PIN/pattern
                        unlock();
                    })
                    .show();
        } else {
            unlock();
        }
    }

    private void unlock() {
        binding.lockOverlay.setVisibility(View.VISIBLE);
        lock.setAuthInProgress(true);
        com.orphos.mobile.auth.LockManager.authenticate(this,
                getString(R.string.lock_title), getString(R.string.lock_subtitle),
                new com.orphos.mobile.auth.LockManager.Callback() {
                    @Override public void onUnlocked() {
                        lock.setAuthInProgress(false);
                        lock.markUnlocked();
                        binding.lockOverlay.setVisibility(View.GONE);
                    }
                    @Override public void onCancelled() {
                        // Stay locked behind the (opaque, blocking) overlay; the user taps
                        // Unlock to retry. The app content is not reachable until unlocked.
                        lock.setAuthInProgress(false);
                        binding.lockOverlay.setVisibility(View.VISIBLE);
                    }
                });
    }

    /** Deep-link to system biometric enrollment (best effort). */
    private void openBiometricEnrollment() {
        try {
            Intent i;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                i = new Intent(android.provider.Settings.ACTION_BIOMETRIC_ENROLL);
                i.putExtra(android.provider.Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
                                | androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL);
            } else {
                i = new Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS);
            }
            startActivity(i);
        } catch (Exception ignored) { }
    }

    private void show(@NonNull Fragment fragment) {
        getSupportFragmentManager().beginTransaction()
                .replace(R.id.nav_host, fragment)
                .commit();
    }

    /** Return to a given tab (used by in-screen back arrows on the main destinations). */
    public void selectTab(int menuId) {
        binding.bottomNav.setSelectedItemId(menuId);
    }
}
