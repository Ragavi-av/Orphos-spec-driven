package com.orphos.mobile.ui.tasks;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.snackbar.Snackbar;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.ActionTask;

import java.util.ArrayList;
import java.util.List;

/**
 * Tasks — the same list as the web dashboard's "Tasks & Approvals" panel. Tasks linked to a
 * BFF approval get approve/reject actions that record the decision on that shared approval
 * (closing the task everywhere). Split into Critical and High tabs by priority, plus the
 * Approval tab.
 */
public class TasksActivity extends AppCompatActivity {

    private final List<ActionTask> critical = new ArrayList<>();
    private final List<ActionTask> high = new ArrayList<>();
    private final List<ActionTask> approval = new ArrayList<>();
    private int tab = 0;
    private float density;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // RBAC: block entry if this persona can't access Tasks for Action.
        if (!new com.orphos.mobile.core.role.MobileAccess(OrphosApp.get().accessState())
                .canSee(com.orphos.mobile.core.role.MobileAccess.TASKS)) {
            android.widget.Toast.makeText(this, R.string.access_denied, android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_tasks);
        density = getResources().getDisplayMetrics().density;

        findViewById(R.id.tasks_back).setOnClickListener(v -> finish());
        findViewById(R.id.tasks_tab_critical).setOnClickListener(v -> { tab = 0; render(); });
        findViewById(R.id.tasks_tab_high).setOnClickListener(v -> { tab = 1; render(); });
        findViewById(R.id.tasks_tab_approval).setOnClickListener(v -> { tab = 2; render(); });
    }

    /** Re-read on every return to the screen, so a change made on the web shows up here. */
    @Override
    protected void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        // Tasks arrive already joined to their BFF approval (task.approvalId), so the
        // Approval tab is exactly the Approvals screen's set and the web panel's.
        OrphosApp.get().repo().actionTasks((tasks, fromMock) -> {
            if (isFinishing()) return;
            critical.clear();
            high.clear();
            approval.clear();
            for (ActionTask t : tasks) {
                if ("CRITICAL".equalsIgnoreCase(t.severity)) critical.add(t);
                else high.add(t); // HIGH / MEDIUM / LOW
                if (t.requiresApproval) approval.add(t);
            }
            render();
            if (fromMock && !OrphosApp.get().config().isDemoMode()) toast(getString(R.string.data_unavailable));
        });
    }

    private void render() {
        ((TextView) findViewById(R.id.tasks_tab_critical))
                .setText(getString(R.string.tasks_tab_critical_fmt, critical.size()));
        ((TextView) findViewById(R.id.tasks_tab_high))
                .setText(getString(R.string.tasks_tab_high_fmt, high.size()));
        ((TextView) findViewById(R.id.tasks_tab_approval))
                .setText(getString(R.string.tasks_tab_approval_fmt, approval.size()));
        styleTab(findViewById(R.id.tasks_tab_critical), tab == 0);
        styleTab(findViewById(R.id.tasks_tab_high), tab == 1);
        styleTab(findViewById(R.id.tasks_tab_approval), tab == 2);

        int sectionRes = tab == 0 ? R.string.tasks_section_critical
                : tab == 1 ? R.string.tasks_section_high : R.string.tasks_section_approval;
        int accentColor = tab == 0 ? R.color.red : tab == 1 ? R.color.orange : R.color.ai_purple;
        TextView section = findViewById(R.id.tasks_section);
        section.setText(sectionRes);
        section.setTextColor(ContextCompat.getColor(this, accentColor));
        section.setAllCaps(true);

        LinearLayout container = findViewById(R.id.tasks_container);
        container.removeAllViews();
        List<ActionTask> list = tab == 0 ? critical : tab == 1 ? high : approval;
        if (list.isEmpty()) {
            TextView empty = text(getString(R.string.tasks_empty), 14, R.color.text_muted, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(24);
            empty.setGravity(Gravity.CENTER);
            empty.setLayoutParams(lp);
            container.addView(empty);
            return;
        }
        int accent = ContextCompat.getColor(this, accentColor);
        for (ActionTask t : list) {
            container.addView(tab == 2 ? buildApprovalCard(t) : buildCard(t, accent));
        }
    }

    private void styleTab(TextView tab, boolean on) {
        tab.setBackgroundResource(on ? R.drawable.pill_active : R.drawable.pill_inactive);
        tab.setTextColor(ContextCompat.getColor(this, on ? R.color.on_primary : R.color.text_secondary));
    }

    private View buildCard(ActionTask task, int accent) {
        boolean critical = tab == 0;
        int boxColor = ContextCompat.getColor(this, critical ? R.color.red : R.color.orange);
        int badgeBg = ContextCompat.getColor(this, R.color.purple_deep);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        card.setLayoutParams(clp);

        // Header row: colored icon box + title/meta + overflow menu
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);

        android.widget.ImageView iconBox = new android.widget.ImageView(this);
        iconBox.setImageResource(iconFor(task.icon));
        iconBox.setColorFilter(android.graphics.Color.WHITE);
        iconBox.setBackgroundResource(R.drawable.icon_box_bg);
        iconBox.setBackgroundTintList(android.content.res.ColorStateList.valueOf(boxColor));
        iconBox.setPadding(dp(9), dp(9), dp(9), dp(9));
        head.addView(iconBox, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        tlp.gravity = Gravity.CENTER_VERTICAL;
        titleCol.setLayoutParams(tlp);
        titleCol.addView(text(task.title, 16, R.color.text, true));
        if (task.meta != null) titleCol.addView(text(task.meta, 12, R.color.text_muted, false));
        head.addView(titleCol);

        android.widget.ImageView more = new android.widget.ImageView(this);
        more.setImageResource(R.drawable.ic_more_vert);
        more.setColorFilter(ContextCompat.getColor(this, R.color.text_muted));
        more.setBackgroundResource(R.drawable.card_bg_2);
        more.setPadding(dp(6), dp(6), dp(6), dp(6));
        more.setClickable(true);
        more.setOnClickListener(v -> showMenu(v, task));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(30), dp(30));
        mlp.gravity = Gravity.CENTER_VERTICAL;
        head.addView(more, mlp);
        card.addView(head);

        // Badges: AI (purple) and, once picked up, In progress (green)
        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams bglp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bglp.topMargin = dp(14);
        badges.setLayoutParams(bglp);
        if (task.kind != null) {
            badges.addView(badge(task.kind, R.color.ai_purple, badgeBg));
        }
        if (inProgress(task)) {
            TextView progress = badge(getString(R.string.tasks_in_progress), R.color.green,
                    ContextCompat.getColor(this, R.color.surface_3));
            if (badges.getChildCount() > 0) {
                ((LinearLayout.LayoutParams) progress.getLayoutParams()).leftMargin = dp(8);
            }
            badges.addView(progress);
        }
        if (badges.getChildCount() > 0) card.addView(badges);

        // Reason inside a nested darker box
        if (task.reason != null) {
            TextView reason = text(task.reason, 13, R.color.text_secondary, false);
            reason.setLineSpacing(0f, 1.35f);
            reason.setBackgroundResource(R.drawable.card_bg_2);
            reason.setPadding(dp(12), dp(11), dp(12), dp(11));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(10);
            reason.setLayoutParams(rlp);
            card.addView(reason);
        }

        // Actions — Critical: outlined + blue primary; High: one full-width orange action.
        if (task.singleAction) {
            TextView action = orangeAction(
                    task.actionLabel != null ? task.actionLabel : getString(R.string.tasks_approve),
                    () -> showDetails(task));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            alp.topMargin = dp(14);
            action.setLayoutParams(alp);
            card.addView(action);
        } else {
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(14);
            buttons.setLayoutParams(blp);
            buttons.addView(button(task.secondary != null ? task.secondary : "View", false,
                    () -> showDetails(task)));
            buttons.addView(gap());
            // Approval tasks keep their approve action; every other task is closed with Done.
            boolean approve = task.requiresApproval;
            String primaryLabel = approve && task.primary != null ? task.primary
                    : getString(R.string.tasks_done);
            buttons.addView(button(primaryLabel, true,
                    () -> {
                        if (!approve) completeTask(task);
                        else if (task.approvalId != null) decideApproval(task, "APPROVE", true);
                        else decide(task, "APPROVED");
                    }));
            card.addView(buttons);
        }
        return card;
    }

    /**
     * Approval-Required card. Shows action buttons appropriate to the task's approvalType
     * (Approve/Reject, Yes/No, Go ahead/Hold). Once decided, the buttons are replaced by the
     * recorded decision and the time it was made.
     */
    private View buildApprovalCard(ActionTask task) {
        boolean critical = "CRITICAL".equalsIgnoreCase(task.severity);
        int boxColor = ContextCompat.getColor(this, critical ? R.color.red : R.color.orange);
        int badgeBg = ContextCompat.getColor(this, R.color.purple_deep);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.card_bg);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        card.setLayoutParams(clp);

        // Header row: colored icon box + title/meta
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);

        android.widget.ImageView iconBox = new android.widget.ImageView(this);
        iconBox.setImageResource(iconFor(task.icon));
        iconBox.setColorFilter(android.graphics.Color.WHITE);
        iconBox.setBackgroundResource(R.drawable.icon_box_bg);
        iconBox.setBackgroundTintList(android.content.res.ColorStateList.valueOf(boxColor));
        iconBox.setPadding(dp(9), dp(9), dp(9), dp(9));
        head.addView(iconBox, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        tlp.gravity = Gravity.CENTER_VERTICAL;
        titleCol.setLayoutParams(tlp);
        titleCol.addView(text(task.title, 16, R.color.text, true));
        if (task.meta != null) titleCol.addView(text(task.meta, 12, R.color.text_muted, false));
        head.addView(titleCol);
        card.addView(head);

        // AI badge (purple)
        if (task.kind != null) {
            TextView kind = text(task.kind, 10, R.color.ai_purple, true);
            kind.setBackgroundResource(R.drawable.chip_soft);
            kind.setBackgroundTintList(android.content.res.ColorStateList.valueOf(badgeBg));
            kind.setPadding(dp(8), dp(3), dp(8), dp(3));
            kind.setAllCaps(true);
            kind.setLetterSpacing(0.05f);
            LinearLayout.LayoutParams klp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            klp.topMargin = dp(14);
            kind.setLayoutParams(klp);
            card.addView(kind);
        }

        // Reason inside a nested darker box
        if (task.reason != null) {
            TextView reason = text(task.reason, 13, R.color.text_secondary, false);
            reason.setLineSpacing(0f, 1.35f);
            reason.setBackgroundResource(R.drawable.card_bg_2);
            reason.setPadding(dp(12), dp(11), dp(12), dp(11));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(10);
            reason.setLayoutParams(rlp);
            card.addView(reason);
        }

        // Decided state, or approve/reject-style actions by approvalType.
        if (task.decision != null) {
            card.addView(decidedView(task));
        } else {
            String[] labels = approvalLabels(task.approvalType);
            LinearLayout buttons = new LinearLayout(this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.topMargin = dp(14);
            buttons.setLayoutParams(blp);
            buttons.addView(button(labels[1], false, () -> decideApproval(task, labels[3], false)));
            buttons.addView(gap());
            buttons.addView(button(labels[0], true, () -> decideApproval(task, labels[2], true)));
            card.addView(buttons);
        }
        return card;
    }

    /** {positiveLabel, negativeLabel, positiveDecision, negativeDecision} for an approvalType. */
    private String[] approvalLabels(String approvalType) {
        String t = approvalType == null ? "APPROVE_REJECT" : approvalType;
        switch (t) {
            case "YES_NO":
                return new String[]{ getString(R.string.tasks_yes), getString(R.string.tasks_no), "YES", "NO" };
            case "GO_AHEAD":
                return new String[]{ getString(R.string.tasks_go_ahead), getString(R.string.tasks_hold), "GO", "HOLD" };
            case "APPROVE_REJECT":
            default:
                return new String[]{ getString(R.string.tasks_approve), getString(R.string.tasks_reject), "APPROVE", "REJECT" };
        }
    }

    /** The recorded-decision row shown once an approval has been decided. */
    private View decidedView(ActionTask task) {
        String time = task.decidedAt != null
                ? new java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                        .format(new java.util.Date(task.decidedAt))
                : "";
        boolean positive = "APPROVED".equalsIgnoreCase(task.approvalStatus);
        TextView tv = text(getString(R.string.tasks_decided_fmt, task.approvalStatus, time),
                13, positive ? R.color.green : R.color.red, true);
        tv.setAllCaps(true);
        tv.setLetterSpacing(0.03f);
        tv.setBackgroundResource(R.drawable.card_bg_2);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        tv.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** Records the approval decision via the BFF (or locally in demo) and shows the decided state. */
    private void decideApproval(ActionTask task, String decision, boolean positive) {
        // The BFF Approval envelope linked to this task; falls back to the task id in
        // demo/offline, where decideApproval resolves locally.
        String approvalId = task.approvalId != null ? task.approvalId : task.id;
        OrphosApp.get().repo().decideApproval(approvalId, decision, (ok, fromMock) -> {
            if (isFinishing()) return;
            if (!Boolean.TRUE.equals(ok)) {
                // Not recorded server-side: leave the task pending rather than show a decision
                // the web (and this app's next load) will not have.
                toast(getString(R.string.action_not_saved));
                return;
            }
            task.decision = decision;
            task.decidedAt = System.currentTimeMillis();
            task.approvalStatus = positive ? "APPROVED" : "REJECTED";
            toast(getString(R.string.tasks_decided_fmt, task.approvalStatus, task.title));
            render();
            // Demo decisions exist only locally; a live one is re-read so the list matches
            // the server (the decided task leaves the open list, as it does on the web).
            if (!fromMock) load();
        });
    }

    private int iconFor(String key) {
        if (key == null) return R.drawable.ic_person_check;
        switch (key) {
            case "handshake": return R.drawable.ic_handshake;
            case "megaphone": return R.drawable.ic_megaphone;
            case "people":    return R.drawable.ic_people;
            case "person-check":
            default:          return R.drawable.ic_person_check;
        }
    }

    /**
     * Overflow menu for a task card (••• top-right). Built from the app's own surface/text
     * colors rather than a themed PopupMenu, whose platform styling rendered dark text on
     * a dark sheet.
     */
    private void showMenu(View anchor, ActionTask task) {
        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setBackgroundResource(R.drawable.card_bg_2);
        sheet.setPadding(0, dp(6), 0, dp(6));

        android.widget.PopupWindow popup = new android.widget.PopupWindow(sheet,
                dp(200), LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(dp(8));
        popup.setOutsideTouchable(true);

        boolean started = inProgress(task);
        sheet.addView(menuItem(started ? R.string.tasks_menu_mark_open : R.string.tasks_menu_in_progress,
                R.color.text, () -> { popup.dismiss(); setProgress(task, !started); }));
        sheet.addView(menuItem(R.string.tasks_menu_snooze, R.color.text,
                () -> { popup.dismiss(); chooseSnooze(task); }));
        sheet.addView(menuItem(R.string.tasks_menu_dismiss, R.color.red,
                () -> { popup.dismiss(); dismissTask(task); }));

        popup.showAsDropDown(anchor, -dp(200) + anchor.getWidth(), dp(4));
    }

    private TextView menuItem(int label, int color, Runnable onClick) {
        TextView item = text(getString(label), 15, color, false);
        item.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.util.TypedValue ripple = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        item.setForeground(ContextCompat.getDrawable(this, ripple.resourceId));
        item.setClickable(true);
        item.setOnClickListener(v -> onClick.run());
        item.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return item;
    }

    /** Full task: title, source, priority and the complete AI reasoning (cards may truncate). */
    private void showDetails(ActionTask task) {
        StringBuilder msg = new StringBuilder();
        if (task.meta != null) msg.append(task.meta).append('\n');
        if (task.severity != null) msg.append(getString(R.string.tasks_detail_priority_fmt, task.severity));
        if (task.requiresApproval) {
            msg.append('\n').append(getString(R.string.tasks_detail_approval_fmt, task.approvalStatus));
        }
        if (task.reason != null) msg.append("\n\n").append(task.reason);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(task.title)
                .setMessage(msg.toString().trim())
                .setPositiveButton(R.string.tasks_detail_close, null)
                .setNeutralButton(R.string.tasks_menu_snooze, (d, w) -> chooseSnooze(task))
                .setNegativeButton(R.string.tasks_menu_dismiss, (d, w) -> dismissTask(task))
                .show();
    }

    /** Snooze durations; the task leaves the list until the reminder comes due. */
    private void chooseSnooze(ActionTask task) {
        String[] labels = {
                getString(R.string.tasks_snooze_1h),
                getString(R.string.tasks_snooze_4h),
                getString(R.string.tasks_snooze_tomorrow),
                getString(R.string.tasks_snooze_next_week),
        };
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.tasks_snooze_title)
                .setItems(labels, (d, which) -> snoozeTask(task, snoozeUntil(which), labels[which]))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private java.time.Instant snoozeUntil(int choice) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        switch (choice) {
            case 0: return now.plusHours(1).toInstant();
            case 1: return now.plusHours(4).toInstant();
            case 2: return now.toLocalDate().plusDays(1).atTime(9, 0).atZone(now.getZone()).toInstant();
            case 3:
            default: return now.toLocalDate().plusWeeks(1).atTime(9, 0).atZone(now.getZone()).toInstant();
        }
    }

    private void snoozeTask(ActionTask task, java.time.Instant until, String label) {
        OrphosApp.get().repo().snoozeTask(task.id, until.toString(), (ok, fromMock) -> {
            if (isFinishing()) return;
            if (!Boolean.TRUE.equals(ok)) { toast(getString(R.string.action_not_saved)); return; }
            removeLocally(task);
            Snackbar.make(findViewById(android.R.id.content),
                            getString(R.string.tasks_snoozed_fmt, label), Snackbar.LENGTH_LONG)
                    .setAction(R.string.tasks_undo, v -> OrphosApp.get().repo()
                            .snoozeTask(task.id, null, (undone, m) -> afterUndo(undone)))
                    .show();
            if (!fromMock) load();
        });
    }

    /** Dismiss — the web panel's Ignore; closes the task on both surfaces. Undoable. */
    private void dismissTask(ActionTask task) {
        OrphosApp.get().repo().setTaskStatus(task.id, "DISMISSED", (ok, fromMock) -> {
            if (isFinishing()) return;
            if (!Boolean.TRUE.equals(ok)) { toast(getString(R.string.action_not_saved)); return; }
            removeLocally(task);
            Snackbar.make(findViewById(android.R.id.content),
                            getString(R.string.tasks_dismissed_fmt, task.title), Snackbar.LENGTH_LONG)
                    .setAction(R.string.tasks_undo, v -> OrphosApp.get().repo()
                            .setTaskStatus(task.id, "OPEN", (undone, m) -> afterUndo(undone)))
                    .show();
            if (!fromMock) load();
        });
    }

    /** Done — completes the task (status DONE): it leaves the list and counts as completed on Home. */
    private void completeTask(ActionTask task) {
        OrphosApp.get().repo().setTaskStatus(task.id, "DONE", (ok, fromMock) -> {
            if (isFinishing()) return;
            if (!Boolean.TRUE.equals(ok)) { toast(getString(R.string.action_not_saved)); return; }
            removeLocally(task);
            Snackbar.make(findViewById(android.R.id.content),
                            getString(R.string.tasks_completed_fmt, task.title), Snackbar.LENGTH_LONG)
                    .setAction(R.string.tasks_undo, v -> OrphosApp.get().repo()
                            .setTaskStatus(task.id, "OPEN", (undone, m) -> afterUndo(undone)))
                    .show();
            if (!fromMock) load();
        });
    }

    private boolean inProgress(ActionTask task) {
        return "IN_PROGRESS".equalsIgnoreCase(task.status);
    }

    /** Mark in progress (or back to open) — the task stays listed, now badged, on web and mobile. */
    private void setProgress(ActionTask task, boolean start) {
        String status = start ? "IN_PROGRESS" : "OPEN";
        OrphosApp.get().repo().setTaskStatus(task.id, status, (ok, fromMock) -> {
            if (isFinishing()) return;
            if (!Boolean.TRUE.equals(ok)) { toast(getString(R.string.action_not_saved)); return; }
            task.status = status;
            render();
            toast(getString(start ? R.string.tasks_marked_in_progress_fmt : R.string.tasks_marked_open_fmt,
                    task.title));
            if (!fromMock) load();
        });
    }

    private TextView badge(String label, int textColor, int bg) {
        TextView b = text(label, 10, textColor, true);
        b.setBackgroundResource(R.drawable.chip_soft);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
        b.setPadding(dp(8), dp(3), dp(8), dp(3));
        b.setAllCaps(true);
        b.setLetterSpacing(0.05f);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private void afterUndo(Boolean ok) {
        if (isFinishing()) return;
        if (!Boolean.TRUE.equals(ok)) toast(getString(R.string.action_not_saved));
        load();
        OrphosApp.get().headerState().refreshTasks();
    }

    /** Drop the task from every tab right away, ahead of the server re-read. */
    private void removeLocally(ActionTask task) {
        critical.remove(task);
        high.remove(task);
        approval.remove(task);
        OrphosApp.get().headerState().refreshTasks(); // Home: counts + progress ring
        render();
    }

    /** Full-width orange outlined action used on High-priority cards. */
    private TextView orangeAction(String label, Runnable onClick) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(13f);
        b.setAllCaps(true);
        b.setLetterSpacing(0.04f);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setTextColor(ContextCompat.getColor(this, R.color.orange));
        b.setBackgroundResource(R.drawable.btn_outline_orange);
        b.setPadding(dp(14), dp(13), dp(14), dp(13));
        b.setClickable(true);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    /** Approve/reject: transition the decision, confirm, and refresh the list. */
    private void decide(ActionTask task, String status) {
        if (task.id == null) { toast(task.title); return; }
        OrphosApp.get().repo().updateDecisionStatus(task.id, status, (d, fromMock) -> {
            if (isFinishing()) return;
            if (d == null) { toast(getString(R.string.action_not_saved)); return; }
            toast(getString("APPROVED".equals(status)
                    ? R.string.tasks_approved_msg : R.string.tasks_rejected_msg, task.title));
            load();
        });
    }

    private View gap() {
        View g = new View(this);
        g.setLayoutParams(new LinearLayout.LayoutParams(dp(10), 1));
        return g;
    }

    private TextView button(String label, boolean primary, Runnable onClick) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(13f);
        b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
        b.setTextColor(ContextCompat.getColor(this, primary ? R.color.on_primary : R.color.text_secondary));
        b.setBackgroundResource(primary ? R.drawable.bg_btn_primary_sm : R.drawable.card_bg_2);
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setClickable(true);
        b.setOnClickListener(v -> onClick.run());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(ContextCompat.getColor(this, color));
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private void toast(String msg) {
        Snackbar.make(findViewById(android.R.id.content), msg, Snackbar.LENGTH_SHORT).show();
    }

    private int dp(int v) { return Math.round(v * density); }
}
