package com.orphos.mobile.ui.common;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.data.model.MailInsightDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App-wide state behind the shared header (see {@link HeaderActions}): the open-task count
 * that colours the availability dot, and the notifications feed that drives the bell's
 * unread badge. One instance per process, so every screen's header reads the same values —
 * a refresh from any screen, or reading the notifications on Business ▸ Notifications,
 * updates the dot and the bell everywhere at once.
 *
 * <p>Sources are the same ones Home always used: the open mail-insight task list
 * ({@code actionTasks}) and {@code /api/mail-insight/notifications}. A failed live call
 * keeps the last good value rather than flipping the dot to green or clearing the badge
 * on the strength of a network error.</p>
 */
public final class HeaderState {

    /** A snapshot of the notifications feed; {@code offline} when the last call failed. */
    public static final class Notifications {
        public final List<MailInsightDto> items;
        public final boolean offline;
        Notifications(List<MailInsightDto> items, boolean offline) {
            this.items = Collections.unmodifiableList(items);
            this.offline = offline;
        }
    }

    private static final String FILE = "orphos_header_state";
    /** Keys of the notifications the user has already seen on the Notifications screen. */
    private static final String K_SEEN = "seen_notification_keys";

    private final SharedPreferences prefs;
    private final MutableLiveData<Integer> openTasks = new MutableLiveData<>();
    private final MutableLiveData<Integer> doneTasks = new MutableLiveData<>();
    private final MutableLiveData<Notifications> notifications = new MutableLiveData<>();
    private final MutableLiveData<Integer> unread = new MutableLiveData<>();

    // Repository callbacks arrive on the main thread, so these need no synchronisation.
    private boolean tasksInFlight;
    private boolean notificationsInFlight;

    public HeaderState(@NonNull Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public LiveData<Integer> openTasks()             { return openTasks; }
    /** Completed (DONE) tasks — with openTasks, Home's task-progress ring. */
    public LiveData<Integer> doneTasks()             { return doneTasks; }
    public LiveData<Notifications> notifications()   { return notifications; }
    public LiveData<Integer> unreadNotifications()   { return unread; }

    /** Re-reads both sources. Calls already in flight are not duplicated. */
    public void refresh() {
        refreshTasks();
        refreshNotifications();
    }

    public void refreshTasks() {
        if (tasksInFlight) return;
        tasksInFlight = true;
        OrphosApp.get().repo().actionTasks((tasks, fromMock) -> {
            tasksInFlight = false;
            if (tasks == null) return;
            if (failed(fromMock) && openTasks.getValue() != null) return;
            openTasks.setValue(tasks.size());
        });
        OrphosApp.get().repo().doneTaskCount((count, fromMock) -> {
            // A failed read keeps the last good count rather than dropping the ring to 0.
            if (count != null) doneTasks.setValue(count);
        });
    }

    public void refreshNotifications() {
        if (notificationsInFlight) return;
        notificationsInFlight = true;
        OrphosApp.get().repo().mailNotifications((items, fromMock) -> {
            notificationsInFlight = false;
            if (failed(fromMock)) {
                Notifications last = notifications.getValue();
                notifications.setValue(new Notifications(
                        last != null ? last.items : new ArrayList<>(), true));
                if (last == null) unread.setValue(0);
                return;
            }
            List<MailInsightDto> list = items != null ? items : new ArrayList<>();
            notifications.setValue(new Notifications(list, false));
            unread.setValue(countUnread(list));
        });
    }

    /** For screens that already hold a fresh open-task count. */
    public void publishOpenTasks(int count) {
        openTasks.setValue(count);
    }

    /**
     * Marks every current notification as read — called while the Notifications screen is
     * showing them. Only the current keys are kept, so the stored set never outgrows the feed.
     */
    public void markNotificationsSeen() {
        Notifications n = notifications.getValue();
        if (n == null) return;
        Set<String> keys = new HashSet<>();
        for (MailInsightDto d : n.items) keys.add(key(d));
        prefs.edit().putStringSet(K_SEEN, keys).apply();
        unread.setValue(0);
    }

    private int countUnread(List<MailInsightDto> items) {
        Set<String> seen = prefs.getStringSet(K_SEEN, Collections.emptySet());
        int n = 0;
        for (MailInsightDto d : items) if (!seen.contains(key(d))) n++;
        return n;
    }

    /** The item id; items without one fall back to title + received time. */
    private static String key(MailInsightDto d) {
        if (d.id != null && !d.id.isEmpty()) return d.id;
        return (d.title != null ? d.title : "") + "|" + (d.receivedAt != null ? d.receivedAt : "");
    }

    /** A live call that failed (demo data is never a failure). */
    private static boolean failed(boolean fromMock) {
        return fromMock && !OrphosApp.get().config().isDemoMode();
    }
}
