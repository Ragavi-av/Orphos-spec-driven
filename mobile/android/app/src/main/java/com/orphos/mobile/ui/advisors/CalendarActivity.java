package com.orphos.mobile.ui.advisors;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.orphos.mobile.R;

/**
 * Today's Calendar (Figma "ORPHOS-1") — the day's meetings and next travel, each with a
 * countdown, location and attendees. Tapping a meeting opens its detail. Reached from
 * Home's "Today's Meetings" / "View Calendar".
 *
 * Rows follow the web Upcoming Meetings rail (MeetingsRail.jsx): earliest first, a
 * leading bar for where the meeting sits in the day — grey once finished, amber for the
 * next one to walk into, blue for the rest still ahead — and finished meetings stay
 * listed but dimmed, so the eye lands on what is still ahead.
 */
public class CalendarActivity extends AppCompatActivity {

    /** Where a meeting sits relative to now. */
    private enum Phase { ENDED, IN_PROGRESS, NEXT, AHEAD }

    /** Opacity of a finished row — the web rail's 0.55. */
    private static final float ENDED_ALPHA = 0.55f;
    /** How often the rows re-check the clock, so a meeting dims as it ends. */
    private static final long TICK_MS = 60_000L;

    private static class Item {
        final String time, title, countdown, sub, loc, more;
        final boolean travel;
        final Phase phase;
        /** The live meeting behind this row (null for travel), handed to the detail view. */
        com.orphos.mobile.data.model.MeetingBrief src;
        Item(String time, String title, String countdown, String sub, String loc, String more,
             boolean travel, Phase phase) {
            this.time = time; this.title = title; this.countdown = countdown;
            this.sub = sub; this.loc = loc; this.more = more; this.travel = travel;
            this.phase = phase;
        }
    }

    private float density;
    private final java.util.Calendar selected = java.util.Calendar.getInstance();
    /** The last live answer for the selected day, re-drawn on each tick without a refetch. */
    private java.util.List<com.orphos.mobile.data.model.MeetingBrief> shown = new java.util.ArrayList<>();
    private boolean shownFromMock;
    private int renderSeq;
    private final android.os.Handler ticker = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            draw();
            ticker.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        // RBAC: block entry if this persona can't access meetings/calendar.
        if (!new com.orphos.mobile.core.role.MobileAccess(
                com.orphos.mobile.OrphosApp.get().accessState())
                .canSee(com.orphos.mobile.core.role.MobileAccess.MEETINGS)) {
            android.widget.Toast.makeText(this, R.string.access_denied, android.widget.Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        setContentView(R.layout.activity_calendar);
        density = getResources().getDisplayMetrics().density;

        findViewById(R.id.cal_back).setOnClickListener(v -> finish());
        // Date picker (top-right) — choose the day the calendar shows.
        findViewById(R.id.cal_date_btn).setOnClickListener(v -> pickDate());
    }

    /** Refetches on every return — a meeting may have been added, or the day rolled over. */
    @Override
    protected void onResume() {
        super.onResume();
        render();
        ticker.postDelayed(tick, TICK_MS);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ticker.removeCallbacks(tick);
    }

    /** Opens a themed date picker (visible day numbers + border); the chosen day drives the
     *  header and re-renders that day's meetings. */
    private void pickDate() {
        android.app.DatePickerDialog dlg = new android.app.DatePickerDialog(this,
                R.style.Orphos_DatePicker,
                (view, year, month, day) -> {
                    selected.set(year, month, day);
                    render();
                },
                selected.get(java.util.Calendar.YEAR), selected.get(java.util.Calendar.MONTH),
                selected.get(java.util.Calendar.DAY_OF_MONTH));
        dlg.show();
    }

    private void render() {
        // Header subtitle reflects the selected date.
        String label = new java.text.SimpleDateFormat("EEEE, dd MMM yyyy", java.util.Locale.getDefault())
                .format(selected.getTime());
        ((TextView) findViewById(R.id.cal_subtitle))
                .setText(getString(R.string.cal_subtitle_fmt, label,
                        java.util.TimeZone.getDefault().getDisplayName(false, java.util.TimeZone.SHORT)));
        ((TextView) findViewById(R.id.cal_heading))
                .setText(isToday(selected) ? R.string.cal_title : R.string.cal_title_day);

        LinearLayout container = findViewById(R.id.cal_container);
        container.removeAllViews();
        // Drop the previous day's rows so a tick cannot redraw them under the new date, and
        // tag this request so a slow answer for an earlier pick cannot land on top of it.
        shown = new java.util.ArrayList<>();
        final int request = ++renderSeq;

        // The selected day, midnight to midnight in THIS device's zone — the same window
        // the web dashboard asks for. The weekend special-case that used to live here is
        // gone: with a live feed an empty day is simply an empty day, whatever its name.
        java.util.Calendar from = (java.util.Calendar) selected.clone();
        from.set(java.util.Calendar.HOUR_OF_DAY, 0);
        from.set(java.util.Calendar.MINUTE, 0);
        from.set(java.util.Calendar.SECOND, 0);
        from.set(java.util.Calendar.MILLISECOND, 0);
        java.util.Calendar to = (java.util.Calendar) from.clone();
        to.add(java.util.Calendar.DAY_OF_MONTH, 1);

        com.orphos.mobile.OrphosApp.get().repo().meetingBriefs(
                java.time.Instant.ofEpochMilli(from.getTimeInMillis()).toString(),
                java.time.Instant.ofEpochMilli(to.getTimeInMillis()).toString(),
                (briefs, fromMock) -> {
            if (isFinishing() || isDestroyed() || request != renderSeq) return;
            // Already earliest first — the repository sorts every meetings answer.
            shown = briefs != null ? briefs : new java.util.ArrayList<>();
            shownFromMock = fromMock;
            draw();
        });
    }

    /** Rebuilds the rows from {@link #shown} against the current clock. */
    private void draw() {
        LinearLayout container = findViewById(R.id.cal_container);
        if (container == null) return;
        container.removeAllViews();
        java.util.List<com.orphos.mobile.data.model.MeetingBrief> briefs = shown;
        if (shownFromMock) {
            TextView note = text(getString(R.string.cal_offline), 12, R.color.orange, false);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.bottomMargin = dp(10);
            note.setLayoutParams(nlp);
            container.addView(note);
        }

        if (briefs == null || briefs.isEmpty()) {
            TextView empty = text(getString(R.string.cal_no_meetings), 14, R.color.text_muted, false);
            empty.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(28);
            empty.setLayoutParams(lp);
            container.addView(empty);
            return;
        }

        long now = System.currentTimeMillis();
        // "Next" is the web's nextUpcoming(): the first meeting not yet started. It is
        // only meaningful on today's page — on a future day the first entry is not the
        // next thing you walk into, so every row there is simply "ahead".
        // Same rule as the Home card, so the two always name the same meeting.
        com.orphos.mobile.data.model.MeetingBrief next = isToday(selected)
                ? com.orphos.mobile.data.model.MeetingBrief.nextUpcoming(briefs, now) : null;
        for (com.orphos.mobile.data.model.MeetingBrief b : briefs) {
            container.addView(buildRow(toItem(b, phaseOf(b, b == next, now))));
        }
    }

    /**
     * ENDED per {@link com.orphos.mobile.data.model.MeetingBrief#hasEnded}. Started-but-not-
     * ended stays at full strength: it is where you are now.
     */
    private static Phase phaseOf(com.orphos.mobile.data.model.MeetingBrief b, boolean isNext, long now) {
        if (b.hasEnded(now)) return Phase.ENDED;
        long start = b.startMillis();
        if (start >= 0 && start < now) return Phase.IN_PROGRESS;
        return isNext ? Phase.NEXT : Phase.AHEAD;
    }

    /** Maps a live meeting brief onto the row model this screen already renders. */
    private Item toItem(com.orphos.mobile.data.model.MeetingBrief b, Phase phase) {
        String sub = (b.briefPoints != null && !b.briefPoints.isEmpty()) ? b.briefPoints.get(0) : "";
        String more = b.attendeeCount > 0 ? "+" + b.attendeeCount : "";
        // Counting down is a today thing; "IN 68H 46M" on a later day's page is noise.
        String countdown = phase == Phase.IN_PROGRESS ? getString(R.string.cal_now)
                : phase == Phase.ENDED || !isToday(selected) ? "" : countdown(b.startMillis());
        Item item = new Item(timeRange(b),
                b.title != null ? b.title : "",
                countdown,
                sub,
                b.location != null ? b.location : "",
                more, false, phase);
        item.src = b;
        return item;
    }

    /** "2:00 PM – 2:45 PM" from the actual start and end, or the start alone without an end. */
    private static String timeRange(com.orphos.mobile.data.model.MeetingBrief b) {
        String start = b.time != null ? b.time : "";
        long end = b.endMillis();
        if (end < 0 || start.isEmpty()) return start;
        return start + " – " + java.time.format.DateTimeFormatter.ofPattern("h:mm a")
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.ofEpochMilli(end));
    }

    private static boolean isToday(java.util.Calendar day) {
        java.util.Calendar now = java.util.Calendar.getInstance();
        return day.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR)
                && day.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /**
     * "IN 50 MIN" / "IN 3H 50M" from the raw start instant, or empty when the meeting has
     * already begun or carries no parseable start. Empty rather than a negative countdown:
     * a past meeting still belongs on the day's agenda, it just has nothing to count to.
     */
    private String countdown(long startMillis) {
        if (startMillis < 0) return "";
        // Rounded, as the web's minutesUntil() and the Home card, so all three read the same.
        long mins = Math.round((startMillis - System.currentTimeMillis()) / 60000.0);
        if (mins <= 0) return "";
        if (mins < 60) return getString(R.string.cal_in_min_fmt, mins);
        return getString(R.string.cal_in_hm_fmt, mins / 60, mins % 60);
    }

    private View buildRow(Item it) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.card_bg);
        row.setPadding(dp(12), dp(14), dp(16), dp(14));
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(12);
        row.setLayoutParams(clp);
        // Finished meetings stay listed — the day's shape matters — but dim.
        if (it.phase == Phase.ENDED) row.setAlpha(ENDED_ALPHA);
        if (!it.travel) {
            row.setOnClickListener(v -> startActivity(
                    MeetingDetailActivity.intent(this, it.src, it.title, it.time, it.countdown)));
        }

        // The leading bar, coloured as the web rail's: done, next, or still ahead.
        View bar = new View(this);
        bar.setBackgroundResource(R.drawable.chip_soft);
        bar.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, barColor(it.phase))));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                dp(3), LinearLayout.LayoutParams.MATCH_PARENT);
        blp.rightMargin = dp(12);
        row.addView(bar, blp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        row.addView(card, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(text(it.time, 13, R.color.link, true));
        View sp = new View(this);
        top.addView(sp, new LinearLayout.LayoutParams(0, 1, 1f));
        // Amber for the next meeting's countdown, as the web rail's header: how soon it
        // starts is a deadline. No chip at all once there is nothing to count to.
        boolean soon = it.phase == Phase.NEXT || it.phase == Phase.IN_PROGRESS;
        TextView cd = text(it.countdown, 10, soon ? R.color.orange : R.color.link, true);
        cd.setBackgroundResource(R.drawable.chip_soft);
        cd.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, soon ? R.color.orange_deep : R.color.blue_deep)));
        cd.setPadding(dp(8), dp(4), dp(8), dp(4));
        cd.setAllCaps(true);
        cd.setVisibility(it.countdown.isEmpty() ? View.GONE : View.VISIBLE);
        top.addView(cd);
        card.addView(top);

        card.addView(marginTop(text(it.title, 16, R.color.text, true), 8));
        card.addView(marginTop(text(it.sub, 12, R.color.text_secondary, false), 3));

        LinearLayout locRow = new LinearLayout(this);
        locRow.setOrientation(LinearLayout.HORIZONTAL);
        locRow.setGravity(Gravity.CENTER_VERTICAL);
        android.widget.ImageView pin = new android.widget.ImageView(this);
        pin.setImageResource(it.travel ? R.drawable.ic_navigation : R.drawable.ic_calendar);
        pin.setColorFilter(ContextCompat.getColor(this, R.color.text_muted));
        locRow.addView(pin, new LinearLayout.LayoutParams(dp(13), dp(13)));
        TextView loc = text(it.loc, 12, R.color.text_muted, false);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        llp.leftMargin = dp(6);
        loc.setLayoutParams(llp);
        locRow.addView(loc);
        card.addView(marginTop(locRow, 6));
        return row;
    }

    private static int barColor(Phase phase) {
        switch (phase) {
            case ENDED: return R.color.text_muted;
            case NEXT:  return R.color.orange;
            default:    return R.color.link;
        }
    }

    private View marginTop(View v, int m) {
        LinearLayout.LayoutParams lp = v.getLayoutParams() instanceof LinearLayout.LayoutParams
                ? (LinearLayout.LayoutParams) v.getLayoutParams()
                : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(m);
        v.setLayoutParams(lp);
        return v;
    }

    private TextView text(String s, int size, int color, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(size);
        tv.setTextColor(ContextCompat.getColor(this, color));
        if (bold) tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
        return tv;
    }

    private int dp(int v) { return Math.round(v * density); }
}
