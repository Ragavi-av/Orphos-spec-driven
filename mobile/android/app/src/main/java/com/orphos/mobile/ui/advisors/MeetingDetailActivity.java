package com.orphos.mobile.ui.advisors;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;

import com.google.android.material.snackbar.Snackbar;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.MeetingBrief;

import java.util.ArrayList;
import java.util.List;

/**
 * Meeting "Detailed view" (Figma "ORPHOS-1"): time/urgency chips, attendees, the AI
 * brief and quick actions — all taken from the live mail-insight meeting. Anything the
 * calendar entry does not carry is hidden rather than filled with sample values.
 */
public class MeetingDetailActivity extends AppCompatActivity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_TIME = "time";
    public static final String EXTRA_COUNTDOWN = "countdown";
    public static final String EXTRA_COUNT = "count";
    public static final String EXTRA_BRIEF = "brief";
    public static final String EXTRA_ATTENDEES = "attendees";
    public static final String EXTRA_ORGANIZER = "organizer";
    public static final String EXTRA_LOCATION = "location";
    public static final String EXTRA_JOIN_URL = "joinUrl";
    public static final String EXTRA_PRIORITY = "priority";

    /** Avatars shown before the "+N others" bubble. */
    private static final int MAX_AVATARS = 4;
    private static final int[] AVATAR_TINTS = {0xFF3A5BD0, 0xFF2E7D6B, 0xFF8A5AD0, 0xFFB0752E};

    /** Builds the launch intent from a live meeting; {@code m} may be null (title/time only). */
    public static Intent intent(Context ctx, @Nullable MeetingBrief m, String title, String time,
                                @Nullable String countdown) {
        Intent i = new Intent(ctx, MeetingDetailActivity.class)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TIME, time)
                .putExtra(EXTRA_COUNTDOWN, countdown);
        if (m != null) {
            i.putExtra(EXTRA_COUNT, m.attendeeCount);
            if (m.briefPoints != null) i.putStringArrayListExtra(EXTRA_BRIEF, new ArrayList<>(m.briefPoints));
            if (m.attendees != null) i.putStringArrayListExtra(EXTRA_ATTENDEES, new ArrayList<>(m.attendees));
            i.putExtra(EXTRA_ORGANIZER, m.organizer);
            i.putExtra(EXTRA_LOCATION, m.location);
            i.putExtra(EXTRA_JOIN_URL, m.joinUrl);
            i.putExtra(EXTRA_PRIORITY, m.priority);
        }
        return i;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_meeting_detail);
        // Shared header: status dot and bell behave exactly as on Home (see HeaderActions).
        com.orphos.mobile.ui.common.HeaderActions.bind(this, null);

        findViewById(R.id.md_back).setOnClickListener(v -> finish());
        Intent in = getIntent();

        String title = in.getStringExtra(EXTRA_TITLE);
        ((TextView) findViewById(R.id.md_title)).setText(
                title != null && !title.isEmpty() ? title : getString(R.string.md_title));

        showIfPresent(R.id.md_sub, joinNonBlank(in.getStringExtra(EXTRA_ORGANIZER),
                in.getStringExtra(EXTRA_LOCATION)));

        String countdown = in.getStringExtra(EXTRA_COUNTDOWN);
        showIfPresent(R.id.md_time_chip, countdown != null && !countdown.isEmpty()
                ? countdown : in.getStringExtra(EXTRA_TIME));

        String priority = in.getStringExtra(EXTRA_PRIORITY);
        boolean urgent = priority != null
                && (priority.equalsIgnoreCase("HIGH") || priority.equalsIgnoreCase("CRITICAL")
                    || priority.equalsIgnoreCase("URGENT"));
        findViewById(R.id.md_urgency_chip).setVisibility(urgent ? View.VISIBLE : View.GONE);

        List<String> attendees = in.getStringArrayListExtra(EXTRA_ATTENDEES);
        if (attendees == null) attendees = new ArrayList<>();
        int count = Math.max(in.getIntExtra(EXTRA_COUNT, 0), attendees.size());
        ((TextView) findViewById(R.id.md_attendees_count))
                .setText(getString(R.string.md_attendees_fmt, count));
        renderAttendees(attendees, count);

        renderBrief(in.getStringArrayListExtra(EXTRA_BRIEF));

        String joinUrl = in.getStringExtra(EXTRA_JOIN_URL);
        View join = findViewById(R.id.md_join);
        if (joinUrl != null && joinUrl.startsWith("http")) {
            join.setOnClickListener(v -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(joinUrl)));
                } catch (Exception e) {
                    Snackbar.make(v, R.string.md_join_meeting, Snackbar.LENGTH_SHORT).show();
                }
            });
        } else {
            join.setVisibility(View.GONE);
        }
        findViewById(R.id.md_add_notes).setOnClickListener(v ->
                Snackbar.make(v, R.string.md_add_notes, Snackbar.LENGTH_SHORT).show());
    }

    private void showIfPresent(int id, @Nullable String text) {
        TextView t = findViewById(id);
        boolean has = text != null && !text.isBlank();
        t.setText(has ? text : "");
        t.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    /** One avatar column per attendee name (up to four), then "+N" for the rest. */
    private void renderAttendees(List<String> names, int total) {
        LinearLayout row = findViewById(R.id.md_attendees);
        row.removeAllViews();
        int shown = Math.min(MAX_AVATARS, names.size());
        for (int i = 0; i < shown; i++) {
            row.addView(avatarColumn(initials(names.get(i)), names.get(i), AVATAR_TINTS[i % AVATAR_TINTS.length]));
        }
        int rest = total - shown;
        if (rest > 0) {
            row.addView(avatarColumn("+" + rest, getString(R.string.md_others),
                    getColor(R.color.surface_3)));
        }
        row.setVisibility(row.getChildCount() > 0 ? View.VISIBLE : View.GONE);
    }

    private View avatarColumn(String badge, String caption, int tint) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView avatar = new TextView(this, null, 0, R.style.MdAvatar);
        avatar.setText(badge);
        avatar.setBackgroundTintList(android.content.res.ColorStateList.valueOf(tint));
        int size = (int) (48 * d);
        col.addView(avatar, new LinearLayout.LayoutParams(size, size));

        TextView name = new TextView(this, null, 0, R.style.MdAvatarName);
        name.setText(caption);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = (int) (6 * d);
        col.addView(name, nlp);
        return col;
    }

    private static String initials(String name) {
        StringBuilder sb = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty() && sb.length() < 2) sb.append(Character.toUpperCase(part.charAt(0)));
        }
        return sb.length() > 0 ? sb.toString() : "?";
    }

    /** The meeting's own brief points; the brief card is hidden when there are none. */
    private void renderBrief(@Nullable List<String> points) {
        LinearLayout c = findViewById(R.id.md_brief);
        c.removeAllViews();
        View card = (View) c.getParent();
        if (points == null || points.isEmpty()) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        float d = getResources().getDisplayMetrics().density;
        for (String p : points) {
            TextView t = new TextView(this);
            t.setText("•  " + p);
            t.setTextColor(getColor(R.color.text));
            t.setTextSize(14f);
            t.setLineSpacing(0f, 1.2f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = (int) (5 * d);
            t.setLayoutParams(lp);
            c.addView(t);
        }
    }
}
