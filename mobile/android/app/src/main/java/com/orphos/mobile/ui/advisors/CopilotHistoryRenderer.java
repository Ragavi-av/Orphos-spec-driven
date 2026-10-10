package com.orphos.mobile.ui.advisors;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.orphos.mobile.R;
import com.orphos.mobile.data.model.CopilotSession;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Draws AI Co-pilot history the way the web dashboard's History ▸ AI tab does
 * (ai-copilot/CopilotHistory.jsx):
 * <ul>
 *   <li>sessions are grouped under Today, Yesterday or a full localised date;</li>
 *   <li>each session collapses to its label, question count, start time and "ended";</li>
 *   <li>expanding a session shows its Q&amp;A with a time · model footer and a delete action.</li>
 * </ul>
 * It also binds single messages for the live answer card, so the two always look alike.
 */
final class CopilotHistoryRenderer {

    private CopilotHistoryRenderer() { }

    /**
     * Rebuilds {@code list} from {@code sessions} (newest first, as the server returns them).
     * {@code expanded} holds the ids of open sessions and is updated as the user taps, so
     * a refresh after a save does not fold everything shut.
     */
    static void renderSessions(@NonNull LinearLayout list, @NonNull List<CopilotSession> sessions,
                               @NonNull Set<String> expanded,
                               @NonNull Consumer<CopilotSession> onDelete) {
        Context ctx = list.getContext();
        LayoutInflater inflater = LayoutInflater.from(ctx);
        list.removeAllViews();

        for (Map.Entry<String, List<CopilotSession>> group : groupByDate(ctx, sessions).entrySet()) {
            TextView heading = new TextView(ctx);
            heading.setText(group.getKey());
            heading.setAllCaps(true);
            heading.setLetterSpacing(0.08f);
            heading.setTextSize(11f);
            heading.setTypeface(heading.getTypeface(), android.graphics.Typeface.BOLD);
            heading.setTextColor(ContextCompat.getColor(ctx, R.color.text_muted));
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            hp.topMargin = dp(ctx, 16);
            list.addView(heading, hp);

            for (CopilotSession s : group.getValue()) {
                list.addView(sessionView(inflater, list, s, expanded, onDelete));
            }
        }
    }

    private static View sessionView(LayoutInflater inflater, LinearLayout parent, CopilotSession s,
                                    Set<String> expanded, Consumer<CopilotSession> onDelete) {
        Context ctx = parent.getContext();
        View v = inflater.inflate(R.layout.item_copilot_session, parent, false);

        TextView label = v.findViewById(R.id.session_label);
        label.setText(s.label != null && !s.label.isEmpty() ? s.label : ctx.getString(R.string.copilot_history_session));
        ((TextView) v.findViewById(R.id.session_count))
                .setText(ctx.getString(R.string.copilot_history_count, s.questionCount()));
        ((TextView) v.findViewById(R.id.session_time)).setText(time(s.startedAt));
        v.findViewById(R.id.session_ended).setVisibility(s.endedAt != null ? View.VISIBLE : View.GONE);

        LinearLayout messages = v.findViewById(R.id.session_messages);
        View details = v.findViewById(R.id.session_details);
        View chevron = v.findViewById(R.id.session_chevron);

        Runnable apply = () -> {
            boolean open = expanded.contains(s.id);
            details.setVisibility(open ? View.VISIBLE : View.GONE);
            chevron.setRotation(open ? 180f : 0f);
            // Inflated on first open only: a long history would otherwise build every
            // answer bubble up front just to hide it.
            if (open && messages.getChildCount() == 0 && s.messages != null) {
                for (int i = 0; i < s.messages.size(); i++) {
                    View m = inflater.inflate(R.layout.item_copilot_message, messages, false);
                    bindMessage(m, s.messages.get(i), false, null);
                    messages.addView(m);
                    if (i < s.messages.size() - 1) messages.addView(divider(ctx));
                }
            }
        };
        apply.run();

        v.findViewById(R.id.session_header).setOnClickListener(x -> {
            if (!expanded.remove(s.id)) expanded.add(s.id);
            apply.run();
        });
        v.findViewById(R.id.session_delete).setOnClickListener(x -> onDelete.accept(s));
        return v;
    }

    /**
     * Fills one message view. A pending message shows the "thinking" copy, and a failed one
     * shows the service's reason (or the generic unavailable copy). When {@code onFollowup}
     * is given, the answer's follow-up suggestions become tappable questions.
     */
    static void bindMessage(@NonNull View v, @NonNull CopilotSession.Message m, boolean failed,
                            @Nullable Consumer<String> onFollowup) {
        Context ctx = v.getContext();
        ((TextView) v.findViewById(R.id.msg_question)).setText(m.question);

        TextView answer = v.findViewById(R.id.msg_answer);
        if (m.loading) {
            answer.setText(R.string.ask_thinking);
        } else if (failed) {
            answer.setText(m.answer != null && !m.answer.isEmpty() ? m.answer : ctx.getString(R.string.ask_unavailable));
        } else {
            answer.setText(m.answer != null ? m.answer : "");
        }
        answer.setTextColor(ContextCompat.getColor(ctx, failed ? R.color.text_secondary : R.color.text));

        TextView meta = v.findViewById(R.id.msg_meta);
        String t = time(m.timestamp);
        String footer = m.provider != null && !m.provider.isEmpty() && !m.loading && !failed
                ? (t.isEmpty() ? m.provider : t + " · " + m.provider) : t;
        meta.setText(footer);
        meta.setVisibility(footer.isEmpty() ? View.GONE : View.VISIBLE);

        LinearLayout followups = v.findViewById(R.id.msg_followups);
        followups.removeAllViews();
        boolean show = onFollowup != null && !m.loading && !failed
                && m.followups != null && !m.followups.isEmpty();
        followups.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        for (String f : m.followups) {
            if (f == null || f.trim().isEmpty()) continue;
            TextView chip = new TextView(ctx);
            chip.setText(f);
            chip.setTextSize(12f);
            chip.setTextColor(ContextCompat.getColor(ctx, R.color.link));
            chip.setBackgroundResource(R.drawable.chip_soft);
            chip.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
            chip.setOnClickListener(x -> onFollowup.accept(f));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(ctx, 6);
            followups.addView(chip, lp);
        }
    }

    /** Same buckets as the web's groupByDate(): Today, Yesterday, else the full local date. */
    private static Map<String, List<CopilotSession>> groupByDate(Context ctx, List<CopilotSession> sessions) {
        Map<String, List<CopilotSession>> groups = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        DateTimeFormatter full = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
                .withLocale(Locale.getDefault());
        for (CopilotSession s : sessions) {
            LocalDate d = localDate(s.startedAt);
            String key;
            if (d == null) key = "";
            else if (d.equals(today)) key = ctx.getString(R.string.copilot_history_today);
            else if (d.equals(today.minusDays(1))) key = ctx.getString(R.string.copilot_history_yesterday);
            else key = d.format(full);
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(s);
        }
        return groups;
    }

    @Nullable
    private static LocalDate localDate(@Nullable String iso) {
        Instant i = instant(iso);
        return i == null ? null : i.atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private static String time(@Nullable String iso) {
        Instant i = instant(iso);
        if (i == null) return "";
        return i.atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()));
    }

    @Nullable
    private static Instant instant(@Nullable String iso) {
        if (iso == null || iso.trim().isEmpty()) return null;
        try { return Instant.parse(iso.trim()); } catch (Exception ignored) { }
        // Instant.parse only takes a trailing 'Z' on older Android; accept an explicit offset too.
        try { return java.time.OffsetDateTime.parse(iso.trim()).toInstant(); } catch (Exception e) { return null; }
    }

    private static View divider(Context ctx) {
        View d = new View(ctx);
        d.setBackgroundColor(ContextCompat.getColor(ctx, R.color.border_soft));
        d.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(ctx, 1)));
        return d;
    }

    private static int dp(Context ctx, int v) {
        return Math.round(v * ctx.getResources().getDisplayMetrics().density);
    }
}
