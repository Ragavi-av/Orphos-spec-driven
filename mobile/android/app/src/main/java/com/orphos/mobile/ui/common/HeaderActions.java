package com.orphos.mobile.ui.common;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import com.google.android.material.snackbar.Snackbar;
import com.orphos.mobile.MainActivity;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.core.config.AppConfig;
import com.orphos.mobile.ui.advisors.AskOrphosActivity;
import com.orphos.mobile.ui.settings.SettingsActivity;

/**
 * The header shared by every screen that shows it — Home, the main tabs and the detail
 * screens: the availability dot ({@code btn_status} / {@code status_dot}), the notifications
 * bell ({@code btn_bell}, unread badge {@code bell_badge}) and the Orphos-AI search bar.
 * Every screen gets the same behaviour as Home:
 * <ul>
 *   <li>Dot — three-colour status from the admin-managed thresholds; tap for the reason.</li>
 *   <li>Bell — opens Business ▸ Notifications; long-press opens Settings. The badge shows
 *       while there are notifications not yet seen on that screen.</li>
 *   <li>Search — a field to type in; only its orb opens Ask Orphos, with what was typed.</li>
 * </ul>
 * The values come from the app-wide {@link HeaderState}, so all headers stay in step: while
 * a screen is resumed its header refreshes that state on the admin-configured interval, and
 * every other header repaints from the same values. Any of the views may be absent from a
 * layout; that part is simply skipped.
 */
public final class HeaderActions {

    /** Business host inner tab for Notifications (see AdvisorsHostFragment). */
    private static final int BUSINESS_NOTIFICATIONS = 2;

    private final Activity activity;
    private final View root;
    private final Handler poll = new Handler(Looper.getMainLooper());
    private final Runnable pollTick = new Runnable() {
        @Override public void run() {
            state().refresh();
            poll.postDelayed(this, refreshIntervalMs());
        }
    };

    private HeaderActions(Activity activity, View root) {
        this.activity = activity;
        this.root = root;
    }

    /** Header inside a fragment; call from onViewCreated. */
    public static HeaderActions bind(@NonNull Fragment fragment, @NonNull View root,
                                     @Nullable View search) {
        return bind(fragment.requireActivity(), fragment.getViewLifecycleOwner(), root, search);
    }

    /** Header inside an activity; call from onCreate after setContentView. */
    public static HeaderActions bind(@NonNull ComponentActivity activity, @Nullable View search) {
        return bind(activity, activity, activity.findViewById(android.R.id.content), search);
    }

    private static HeaderActions bind(Activity activity, LifecycleOwner owner, View root,
                                      @Nullable View search) {
        HeaderActions h = new HeaderActions(activity, root);

        View bell = root.findViewById(R.id.btn_bell);
        if (bell != null) {
            bell.setOnClickListener(v -> h.openNotifications());
            // Long-press the bell to open Settings (role, app lock, endpoint).
            bell.setOnLongClickListener(v -> {
                activity.startActivity(new Intent(activity, SettingsActivity.class));
                return true;
            });
        }

        if (search != null) bindSearch(activity, search);

        HeaderState state = state();
        state.openTasks().observe(owner, h::paintStatus);
        state.unreadNotifications().observe(owner, h::paintBell);

        // Refresh on every return to the screen, then on the admin-configured interval.
        owner.getLifecycle().addObserver(new DefaultLifecycleObserver() {
            @Override public void onResume(@NonNull LifecycleOwner o) {
                h.poll.removeCallbacks(h.pollTick);
                h.pollTick.run();
            }
            @Override public void onPause(@NonNull LifecycleOwner o) {
                h.poll.removeCallbacks(h.pollTick);
            }
        });
        return h;
    }

    /**
     * The search bar is a field the user types in; only the Orphos-AI orb opens Ask Orphos,
     * asking what was typed (or just opening it when the field is empty). A tap anywhere
     * else on the bar focuses the field; the keyboard's action only closes the keyboard.
     */
    private static void bindSearch(Activity activity, View search) {
        android.widget.EditText input = search.findViewById(R.id.search_input);
        View orb = search.findViewById(R.id.search_orb);
        android.view.inputmethod.InputMethodManager ime =
                ContextCompat.getSystemService(activity, android.view.inputmethod.InputMethodManager.class);
        if (input != null) {
            search.setOnClickListener(v -> {
                input.requestFocus();
                if (ime != null) ime.showSoftInput(input, 0);
            });
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (ime != null) ime.hideSoftInputFromWindow(v.getWindowToken(), 0);
                v.clearFocus();
                return true;
            });
        }
        if (orb != null) {
            orb.setOnClickListener(v -> {
                String typed = input != null ? input.getText().toString().trim() : "";
                if (input != null) {
                    if (ime != null) ime.hideSoftInputFromWindow(input.getWindowToken(), 0);
                    input.setText(null);
                    input.clearFocus();
                }
                activity.startActivity(typed.isEmpty()
                        ? new Intent(activity, AskOrphosActivity.class)
                        : AskOrphosActivity.askIntent(activity, typed));
            });
        }
    }

    /**
     * Re-reads the open-task count and notifications now. Resumed headers already do this
     * on their own; use it after an action that changes either.
     */
    public void refreshStatus() {
        state().refresh();
    }

    /**
     * For screens that already fetched the open tasks (Home's Tasks widget): publishes the
     * count to every header instead of fetching it a second time.
     */
    public void bindStatus(int openTasks) {
        state().publishOpenTasks(openTasks);
    }

    /** Bell: Business ▸ Notifications — in place on the main screen, else back to it. */
    private void openNotifications() {
        if (activity instanceof MainActivity m) {
            m.openBusinessTab(BUSINESS_NOTIFICATIONS);
        } else {
            activity.startActivity(MainActivity.businessTabIntent(activity, BUSINESS_NOTIFICATIONS));
        }
    }

    /**
     * Executive availability dot. Three-colour status from the admin-managed thresholds
     * (Platform Admin → Mobile Access): >= red → Red (Busy), >= blue → Blue (Moderate),
     * else Green (Available). Tapping explains the current state.
     */
    private void paintStatus(Integer openTasks) {
        View dot = root.findViewById(R.id.status_dot);
        View btn = root.findViewById(R.id.btn_status);
        if (openTasks == null || dot == null || btn == null) return;

        AppConfig cfg = OrphosApp.get().config();
        int colorRes; int stateRes;
        if (openTasks >= cfg.statusRed())       { colorRes = R.color.red;   stateRes = R.string.home_status_busy; }
        else if (openTasks >= cfg.statusBlue())  { colorRes = R.color.link;  stateRes = R.string.home_status_moderate; }
        else                                     { colorRes = R.color.green; stateRes = R.string.home_status_available; }

        dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(activity, colorRes)));

        // Tap the dot for the open-task count + state (the word is not shown inline).
        btn.setOnClickListener(v -> Snackbar.make(root, activity.getString(
                R.string.home_status_reason_fmt, openTasks, activity.getString(stateRes)),
                Snackbar.LENGTH_SHORT).show());
    }

    /** The bell's unread badge: shown while any notification has not been seen yet. */
    private void paintBell(Integer unread) {
        View badge = root.findViewById(R.id.bell_badge);
        if (unread == null || badge == null) return;
        badge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
    }

    private static HeaderState state() {
        return OrphosApp.get().headerState();
    }

    private static long refreshIntervalMs() {
        return Math.max(1, OrphosApp.get().config().refreshMinutes()) * 60_000L;
    }
}
