package com.orphos.mobile.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.snackbar.Snackbar;
import com.orphos.mobile.MainActivity;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.HomeSummary;
import com.orphos.mobile.databinding.FragmentHomeBinding;
import com.orphos.mobile.ui.common.HeaderActions;
import com.orphos.mobile.ui.common.HeaderState;

/**
 * Home — the executive dashboard (Figma "ORPHOS-1"): greeting + brand, universal
 * search, Board Meeting / Next Travel cards, the Tasks / Approvals / Alerts summary,
 * and today's task-progress ring. Data comes from the mobile BFF via the repository.
 */
public class HomeFragment extends Fragment {

    private FragmentHomeBinding binding;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentHomeBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.progressRing.setIndeterminate(false);

        // Shared header: availability dot, bell (→ Notifications, long-press → Settings) and
        // the Orphos-AI search (→ Ask Orphos) — the same on every main tab.
        HeaderActions.bind(this, binding.getRoot(), binding.homeSearch);

        // Tasks and Alerts read the header's shared state (refreshed by the header while this
        // screen is visible), so they always match the dot and the bell on every screen.
        // Tasks — the same open mail-insight list the Tasks screen and the web panel show.
        HeaderState state = OrphosApp.get().headerState();
        state.openTasks().observe(getViewLifecycleOwner(), count -> {
            binding.statTasks.setText(String.valueOf(count));
            bindTaskProgress();
        });
        // Today's task progress — completed today / (open + completed today). Dismissed and
        // snoozed tasks are in neither count, so dismissing one shrinks the total.
        state.doneTasks().observe(getViewLifecycleOwner(), done -> bindTaskProgress());
        // Alerts — the count in Business ▸ Notifications' Alerts section, from the same
        // /api/mail-insight/notifications feed and the same grouping rule. Not /api/mobile/home's
        // severity tally: that is a different source (it read 0 while the section listed 3).
        state.notifications().observe(getViewLifecycleOwner(), n ->
                binding.statAlerts.setText(String.valueOf(
                        com.orphos.mobile.ui.business.NotificationsFragment.alertCount(n.items))));

        // Board Meeting card → the day's calendar (also the "View Calendar" link within it).
        binding.cardBoard.setOnClickListener(v ->
                startActivity(new Intent(requireContext(),
                        com.orphos.mobile.ui.advisors.CalendarActivity.class)));
        // Next Travel card → the Travel tab in Business (details + full upcoming list).
        binding.cardTravel.setOnClickListener(v -> openBusiness(3));

        // Home stat widgets deep-link into their details in the Business tab:
        // Approvals(0) · Tasks(1) · Notifications/Alerts(2).
        binding.statCardTasks.setOnClickListener(v -> openBusiness(1));
        binding.statCardApprovals.setOnClickListener(v -> openBusiness(0));
        binding.statCardAlerts.setOnClickListener(v -> openBusiness(2));

        View.OnClickListener toTasks = v ->
                startActivity(new Intent(requireContext(),
                        com.orphos.mobile.ui.tasks.TasksActivity.class));
        binding.homeViewTasks.setOnClickListener(toTasks);
        // The whole Today's Task Progress card is a tap target, not just its link.
        binding.cardTaskProgress.setOnClickListener(toTasks);

        setGreetingName();
        applyGating();
        // The first load happens in onResume, which also re-reads on every return.
    }

    /**
     * Greets the signed-in user by name. The name comes from the Keycloak identity
     * (set at login from the token, refreshed from /api/mobile/profile); when unknown
     * (e.g. demo mode) the layout's generic fallback is kept.
     */
    private void setGreetingName() {
        String name = OrphosApp.get().config().userName();
        if (name != null && !name.trim().isEmpty()) {
            binding.homeGreetingName.setText(name.trim());
        }
    }

    /** Ring = done / (open + done), once both counts have arrived. */
    private void bindTaskProgress() {
        if (binding == null) return;
        HeaderState state = OrphosApp.get().headerState();
        Integer open = state.openTasks().getValue(), done = state.doneTasks().getValue();
        if (open == null || done == null) return;
        int total = open + done;
        binding.progressFrac.setText(done + "/" + total);
        binding.progressRing.setProgressCompat(total == 0 ? 0 : done * 100 / total, true);
    }

    /** Opens the Business tab at a given inner tab (0=Approvals, 1=Tasks, 2=Notifications). */
    private void openBusiness(int inner) {
        if (getActivity() instanceof MainActivity m) m.openBusinessTab(inner);
    }

    /** Hide Home cards/sections the current persona can't access (Home itself stays visible). */
    private void applyGating() {
        com.orphos.mobile.core.role.MobileAccess access =
                new com.orphos.mobile.core.role.MobileAccess(OrphosApp.get().accessState());
        setVisible(binding.cardBoard,
                access.canSee(com.orphos.mobile.core.role.MobileAccess.MEETINGS));
        setVisible(binding.cardTravel,
                access.canSee(com.orphos.mobile.core.role.MobileAccess.TRAVEL));
    }

    private void setVisible(View v, boolean visible) {
        if (v != null) v.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void load() {
        OrphosApp.get().repo().homeSummary((h, fromMock) -> {
            if (binding == null || h == null) return;
            if (fromMock && !OrphosApp.get().config().isDemoMode()) toast(getString(R.string.data_unavailable));

            String msg = firstSummaryText(h);
            // The digest's own headline; an empty brief says so instead of sample text.
            if (msg != null) binding.progressMsg.setText(msg);
            else if (!fromMock) binding.progressMsg.setText(R.string.home_summary_empty);
        });

        // Approvals — only the PENDING items in the BFF Approval store (/api/mobile/approvals),
        // i.e. exactly the cards Business ▸ Approvals lists; once decided an item is done and
        // drops out of both.
        OrphosApp.get().repo().approvals((approvals, fromMock) -> {
            if (binding == null || approvals == null) return;
            int pending = 0;
            for (com.orphos.mobile.data.model.ApprovalItem a : approvals) if (a.isPending()) pending++;
            binding.statApprovals.setText(String.valueOf(pending));
        });

        // Next meeting — today's calendar, the same list and the same "next" rule as the
        // web greeting band (meetings.js nextUpcoming: the first one not yet started).
        OrphosApp.get().repo().meetingBriefs((briefs, fromMock) -> {
            if (binding == null) return;
            // A failed call resolves empty-but-not-live. Keep the last good day rather than
            // claiming "No more meetings today" on the strength of a network error.
            if (fromMock) {
                if (todaysMeetings == null) bindMeetingUnavailable();
                else bindNextMeeting();
                return;
            }
            todaysMeetings = briefs != null ? briefs : new java.util.ArrayList<>();
            bindNextMeeting();
        });

        // Next Travel — the first upcoming trip; the repository has already dropped past
        // trips and sorted soonest first, exactly as the web Travel rail does.
        OrphosApp.get().repo().travel((trips, fromMock) -> {
            if (binding == null || trips == null) return;
            if (trips.isEmpty()) {
                binding.homeTravelRoute.setText(R.string.home_travel_none);
                binding.homeTravelTime.setText(R.string.home_pending);
                return;
            }
            com.orphos.mobile.data.model.TravelItem t = trips.get(0);
            binding.homeTravelRoute.setText(t.route());
            StringBuilder when = new StringBuilder();
            if (t.date != null && !t.date.isEmpty()) when.append(t.date);
            if (t.time != null && !t.time.isEmpty()) when.append(when.length() > 0 ? " • " : "").append(t.time);
            binding.homeTravelTime.setText(when.length() > 0 ? when.toString() : getString(R.string.home_pending));
        });
    }

    /** Today's meetings from the last successful load; null until one has succeeded. */
    private java.util.List<com.orphos.mobile.data.model.MeetingBrief> todaysMeetings;

    /**
     * The meeting card: only the next meeting that has not started yet. A meeting drops
     * off the moment it starts — it is no longer "upcoming", and the calendar is where the
     * one in progress lives. Re-run every minute against {@link #todaysMeetings}, so the
     * countdown stays true and a started meeting disappears between network refreshes.
     */
    private void bindNextMeeting() {
        if (binding == null || todaysMeetings == null) return;
        long now = System.currentTimeMillis();
        com.orphos.mobile.data.model.MeetingBrief next =
                com.orphos.mobile.data.model.MeetingBrief.nextUpcoming(todaysMeetings, now);
        if (next == null) {
            binding.homeMeetingTitle.setText(R.string.home_next_meeting);
            binding.homeMeetingSub.setText(R.string.home_meeting_none);
            return;
        }
        binding.homeMeetingTitle.setText(next.title != null && !next.title.isEmpty()
                ? next.title : getString(R.string.home_next_meeting));
        binding.homeMeetingSub.setText(getString(R.string.home_meeting_next_fmt,
                timeUntil(next.startMillis() - now), next.time));
    }

    private void bindMeetingUnavailable() {
        binding.homeMeetingTitle.setText(R.string.home_next_meeting);
        binding.homeMeetingSub.setText(R.string.home_meeting_unavailable);
    }

    /** Minute tick: re-picks the next meeting from the cached day, no network. */
    private final Runnable meetingTick = new Runnable() {
        @Override public void run() {
            bindNextMeeting();
            refresh.postDelayed(this, 60_000L);
        }
    };

    /** "45 min" / "2 h 10 min" — the countdown the web greeting band shows as "in 45 min". */
    private String timeUntil(long millis) {
        int mins = (int) Math.round(millis / 60000.0);
        return mins < 60 ? getString(R.string.home_minutes_fmt, mins)
                : getString(R.string.home_hours_fmt, mins / 60, mins % 60);
    }

    /** The most important AI-summary line (critical first), for the progress message. */
    private String firstSummaryText(HomeSummary h) {
        if (h.summary == null || h.summary.isEmpty()) return null;
        for (HomeSummary.Point p : h.summary) {
            if ("CRITICAL".equals(p.severity) || "WARNING".equals(p.severity)) return p.text;
        }
        return h.summary.get(0).text;
    }

    private void toast(String msg) {
        if (binding != null) Snackbar.make(binding.getRoot(), msg, Snackbar.LENGTH_SHORT).show();
    }

    // --- Auto-refresh on the admin-configured interval (Mobile Access → refreshMinutes) ---

    private final android.os.Handler refresh = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable refreshTick = new Runnable() {
        @Override public void run() {
            if (binding == null) return;
            load();
            refresh.postDelayed(this, refreshIntervalMs());
        }
    };

    private long refreshIntervalMs() {
        return Math.max(1, OrphosApp.get().config().refreshMinutes()) * 60_000L;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Load now (not one interval later), then keep polling while visible.
        load();
        refresh.postDelayed(refreshTick, refreshIntervalMs());
        refresh.postDelayed(meetingTick, 60_000L);
    }

    @Override
    public void onPause() {
        super.onPause();
        refresh.removeCallbacks(refreshTick);
        refresh.removeCallbacks(meetingTick);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        refresh.removeCallbacks(refreshTick);
        refresh.removeCallbacks(meetingTick);
        binding = null;
    }
}
