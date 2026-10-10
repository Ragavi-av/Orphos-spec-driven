package com.orphos.mobile.ui.advisors;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.orphos.mobile.R;
import com.orphos.mobile.core.role.Role;
import com.orphos.mobile.data.model.DailyDigest;
import com.orphos.mobile.data.model.DecisionItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds the Advisors tab's proactive prompt, AI recommendation and suggested questions
 * from the signed-in executive's role and their live data: pending decisions from
 * decision-log, and today's briefing from briefing-service.
 *
 * <p>There is no backend endpoint that suggests questions, and the web co-pilot's starter
 * prompts are static, so the choice is made here, from data the app already loads.</p>
 * <ul>
 *   <li>Every data-driven line names a real decision or briefing item.</li>
 *   <li>When there is no data, the tab falls back to role-appropriate questions, never to
 *       invented facts.</li>
 *   <li>Role changes the ranking: a CFO sees finance decisions first, a COO operations,
 *       and a CHRO people. The questions that aren't tied to data are the role's own.</li>
 * </ul>
 */
final class AdvisorsInsights {

    /** Suggestion category; picks the chip colours. */
    enum Tag { REVENUE, OPERATIONS, PEOPLE }

    static final class Suggestion {
        final String question;
        final String label;
        final Tag tag;

        Suggestion(String question, String label, Tag tag) {
            this.question = question;
            this.label = label;
            this.tag = tag;
        }
    }

    /** The purple proactive card: a line about the day, and the question a tap asks. */
    static final class Prompt {
        final String text;
        @Nullable final String question;

        Prompt(String text, @Nullable String question) {
            this.text = text;
            this.question = question;
        }
    }

    /** Titles are quoted inside questions, so keep them short enough for a 2-line card. */
    private static final int TITLE_MAX = 34;

    private final Context ctx;
    private final Role role;
    /** Pending decisions, most relevant to this role first. */
    private final List<DecisionItem> pending;
    /** Critical/warning briefing items, critical first. */
    private final List<DailyDigest.DigestItem> briefing;
    @Nullable private final DailyDigest digest;

    AdvisorsInsights(@NonNull Context ctx, @NonNull Role role,
                     @Nullable List<DecisionItem> decisions, @Nullable DailyDigest digest) {
        this.ctx = ctx;
        this.role = role;
        this.digest = digest;
        this.pending = rankPending(decisions, role);
        this.briefing = urgentBriefing(digest);
    }

    // ── Proactive prompt ──────────────────────────────────────────────────────

    Prompt prompt() {
        int decisions = pending.size();
        long critical = briefing.stream().filter(i -> "critical".equalsIgnoreCase(i.severity)).count();

        if (decisions > 0 && critical > 0) {
            return new Prompt(ctx.getResources().getQuantityString(R.plurals.advisors_prompt_decisions_and_briefing,
                    decisions, decisions, (int) critical),
                    ctx.getString(R.string.advisors_prompt_q_prioritise));
        }
        if (decisions > 0) {
            return new Prompt(ctx.getResources().getQuantityString(R.plurals.advisors_prompt_decisions,
                    decisions, decisions, shorten(pending.get(0).title)),
                    decisions > 1 ? ctx.getString(R.string.advisors_prompt_q_prioritise)
                            : ctx.getString(R.string.advisors_q_decide, shorten(pending.get(0).title)));
        }
        if (!briefing.isEmpty()) {
            String headline = digest != null && notBlank(digest.headline) ? digest.headline
                    : ctx.getString(R.string.advisors_prompt_briefing, briefing.size());
            return new Prompt(headline, ctx.getString(R.string.advisors_prompt_q_briefing));
        }
        // Nothing pressing: say so plainly, and offer the role's own opening question.
        return new Prompt(ctx.getString(R.string.advisors_prompt_clear), roleQuestions().get(0).question);
    }

    // ── AI recommendation ─────────────────────────────────────────────────────

    /** The decision the recommendation card is about, or null to hide the card. */
    @Nullable
    DecisionItem topDecision() {
        return pending.isEmpty() ? null : pending.get(0);
    }

    /**
     * Card copy for the top decision until its AI annotation arrives, which is an LLM call
     * and can take half a minute. It shows the decision's impact summary where there is one,
     * otherwise just that the recommendation is on its way. It never shows {@code rationale}:
     * that field is the detector's own reasoning about why something counts as a decision,
     * and it reads as nonsense on a recommendation card.
     */
    static String provisionalRecommendation(@NonNull Context ctx, @NonNull DecisionItem d) {
        if (notBlank(d.impactSummary)) return d.title + " — " + d.impactSummary;
        return ctx.getString(R.string.advisors_rec_preparing, d.title);
    }

    /** Fallback when the annotation has no recommendation to offer. */
    static String reviewRecommendation(@NonNull Context ctx, @NonNull DecisionItem d) {
        return ctx.getString(R.string.advisors_rec_review, d.title);
    }

    // ── Suggested questions ───────────────────────────────────────────────────

    /**
     * Exactly three suggestions. Up to two come from the data: the top decision, then the
     * top briefing item, or the second decision if the briefing is empty. The rest are the
     * role's own questions, so a persona-specific one always appears.
     */
    List<Suggestion> suggestions() {
        List<Suggestion> out = new ArrayList<>(3);
        if (!pending.isEmpty()) {
            DecisionItem d = pending.get(0);
            out.add(new Suggestion(ctx.getString(R.string.advisors_q_decide, shorten(d.title)),
                    label(d.decisionArea != null ? d.decisionArea : d.domain), tagFor(d.domain, d.decisionArea)));
        }
        DailyDigest.DigestItem item = firstBriefingNotAlreadyADecision();
        if (item != null) {
            out.add(new Suggestion(ctx.getString(R.string.advisors_q_briefing, shorten(item.title)),
                    label(item.kind), tagFor(item.kind, null)));
        } else if (pending.size() > 1) {
            DecisionItem d = pending.get(1);
            out.add(new Suggestion(ctx.getString(R.string.advisors_q_delay, shorten(d.title)),
                    label(d.decisionArea != null ? d.decisionArea : d.domain), tagFor(d.domain, d.decisionArea)));
        }
        for (Suggestion s : roleQuestions()) {
            if (out.size() >= 3) break;
            out.add(s);
        }
        return out;
    }

    /** Three standing questions per role, most important first. */
    List<Suggestion> roleQuestions() {
        switch (role) {
            case CFO:
                return Arrays.asList(
                        q(R.string.advisors_cfo_q1, R.string.tag_finance, Tag.REVENUE),
                        q(R.string.advisors_cfo_q2, R.string.tag_finance, Tag.REVENUE),
                        q(R.string.advisors_cfo_q3, R.string.tag_revenue, Tag.REVENUE));
            case COO:
                return Arrays.asList(
                        q(R.string.advisors_coo_q1, R.string.tag_operations, Tag.OPERATIONS),
                        q(R.string.advisors_coo_q2, R.string.tag_operations, Tag.OPERATIONS),
                        q(R.string.advisors_coo_q3, R.string.tag_operations, Tag.OPERATIONS));
            case CHRO:
                return Arrays.asList(
                        q(R.string.advisors_chro_q1, R.string.tag_people, Tag.PEOPLE),
                        q(R.string.advisors_chro_q2, R.string.tag_people, Tag.PEOPLE),
                        q(R.string.advisors_chro_q3, R.string.tag_people, Tag.PEOPLE));
            case CTO:
                return Arrays.asList(
                        q(R.string.advisors_cto_q1, R.string.tag_technology, Tag.OPERATIONS),
                        q(R.string.advisors_cto_q2, R.string.tag_technology, Tag.OPERATIONS),
                        q(R.string.advisors_cto_q3, R.string.tag_technology, Tag.OPERATIONS));
            case VIEWER:
                return Arrays.asList(
                        q(R.string.advisors_viewer_q1, R.string.tag_general, Tag.OPERATIONS),
                        q(R.string.advisors_viewer_q2, R.string.tag_general, Tag.OPERATIONS),
                        q(R.string.advisors_viewer_q3, R.string.tag_general, Tag.OPERATIONS));
            case CEO:
            default:
                return Arrays.asList(
                        q(R.string.advisors_ceo_q1, R.string.tag_strategy, Tag.OPERATIONS),
                        q(R.string.advisors_ceo_q2, R.string.tag_revenue, Tag.REVENUE),
                        q(R.string.advisors_ceo_q3, R.string.tag_people, Tag.PEOPLE));
        }
    }

    private Suggestion q(int question, int label, Tag tag) {
        return new Suggestion(ctx.getString(question), ctx.getString(label), tag);
    }

    // ── Ranking ───────────────────────────────────────────────────────────────

    private static List<DecisionItem> rankPending(@Nullable List<DecisionItem> decisions, Role role) {
        List<DecisionItem> out = new ArrayList<>();
        if (decisions == null) return out;
        for (DecisionItem d : decisions) {
            if (d == null || !notBlank(d.title)) continue;
            String s = d.status == null ? "" : d.status.toUpperCase(Locale.ROOT);
            if (s.equals("PENDING") || s.equals("DETECTED") || s.equals("OPEN")) out.add(d);
        }
        // Urgency first. Within the same urgency, decisions in the role's own area come
        // first, then the engine's priority score, then AI confidence.
        out.sort(Comparator
                .comparingInt((DecisionItem d) -> priorityRank(d.priorityLevel))
                .thenComparingInt(d -> matchesRole(d, role) ? 0 : 1)
                .thenComparing(d -> d.priorityScore == null ? 0.0 : d.priorityScore, Comparator.reverseOrder())
                .thenComparing(DecisionItem::confidencePercent, Comparator.reverseOrder()));
        return out;
    }

    private static int priorityRank(@Nullable String level) {
        if (level == null) return 4;
        switch (level.toUpperCase(Locale.ROOT)) {
            case "CRITICAL": return 0;
            case "HIGH":     return 1;
            case "MEDIUM":   return 2;
            case "LOW":      return 3;
            default:         return 4;
        }
    }

    /** Whether a decision sits in this role's own area. The CEO owns every area. */
    private static boolean matchesRole(DecisionItem d, Role role) {
        String area = ((d.domain == null ? "" : d.domain) + " " + (d.decisionArea == null ? "" : d.decisionArea))
                .toUpperCase(Locale.ROOT);
        switch (role) {
            case CFO:  return containsAny(area, "FINANC", "BUDGET", "CAPEX", "COST", "SPEND", "INVEST", "REVENUE");
            case COO:  return containsAny(area, "OPERAT", "VENDOR", "SUPPLY", "INCIDENT", "SLA", "IT");
            case CHRO: return containsAny(area, "PEOPLE", "HR", "HIRING", "TALENT", "ATTRITION");
            case CTO:  return containsAny(area, "IT", "TECH", "SECUR", "INFRA", "ENGINEER", "SYSTEM", "SLA");
            default:   return true;
        }
    }

    private static List<DailyDigest.DigestItem> urgentBriefing(@Nullable DailyDigest digest) {
        List<DailyDigest.DigestItem> out = new ArrayList<>();
        if (digest == null || digest.items == null) return out;
        for (DailyDigest.DigestItem i : digest.items) {
            if (i == null || !notBlank(i.title)) continue;
            if ("critical".equalsIgnoreCase(i.severity) || "warning".equalsIgnoreCase(i.severity)) out.add(i);
        }
        out.sort(Comparator.comparingInt(i -> "critical".equalsIgnoreCase(i.severity) ? 0 : 1));
        return out;
    }

    /** Skips briefing items that only restate the top decision, so the two cards differ. */
    @Nullable
    private DailyDigest.DigestItem firstBriefingNotAlreadyADecision() {
        String top = pending.isEmpty() ? null : pending.get(0).id;
        for (DailyDigest.DigestItem i : briefing) {
            if (top != null && top.equals(i.id)) continue;
            return i;
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static Tag tagFor(@Nullable String a, @Nullable String b) {
        String s = ((a == null ? "" : a) + " " + (b == null ? "" : b)).toUpperCase(Locale.ROOT);
        if (containsAny(s, "PEOPLE", "HR", "HIRING", "TALENT", "ATTRITION")) return Tag.PEOPLE;
        if (containsAny(s, "FINANC", "BUDGET", "REVENUE", "SALES", "CUSTOMER", "INVESTOR", "CAPEX", "MARKETING"))
            return Tag.REVENUE;
        return Tag.OPERATIONS;
    }

    /** "CUSTOMER_SUCCESS" → "Customer success"; blank → "Decision". */
    private String label(@Nullable String raw) {
        if (!notBlank(raw)) return ctx.getString(R.string.tag_decision);
        String s = raw.trim().replace('_', ' ').toLowerCase(Locale.getDefault());
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String shorten(@Nullable String title) {
        if (title == null) return "";
        String t = title.trim();
        return t.length() <= TITLE_MAX ? t : t.substring(0, TITLE_MAX - 1).trim() + "…";
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) {
            // Whole-word match for the short keys, so "IT" does not fire on "PROFIT".
            if (n.length() <= 3 ? (" " + s + " ").matches(".*[^A-Z]" + n + "[^A-Z].*") : s.contains(n)) return true;
        }
        return false;
    }

    private static boolean notBlank(@Nullable String s) {
        return s != null && !s.trim().isEmpty();
    }
}
