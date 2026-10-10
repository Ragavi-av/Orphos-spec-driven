package io.orphos.eip.mailinsight.inference;

import io.orphos.eip.mailinsight.graph.GraphItem;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic, explainable classifier — always available, no external calls.
 * Produces a composite 0-100 score from signals (sender importance, direct
 * addressing, action/criticality keywords, deadline proximity) and maps it to a
 * TASK or NOTIFICATION with priority/criticality and an extracted due date.
 *
 * This is the always-on baseline; the LLM layer may override the verdict when a
 * provider is configured, but the tabs work fully with rules alone.
 */
@Component
public class RuleBasedClassifier implements InsightClassifier {

    // Words that signal an action is requested of the reader → TASK.
    private static final Pattern ACTION = Pattern.compile(
            "\\b(action required|please (review|approve|confirm|respond|complete|send|provide|sign)|" +
            "sign[- ]?off|approval needed|awaiting your|can you|could you|need your|" +
            "follow up|to-?do|deadline|due|by eod|by end of|submit|rsvp)\\b",
            Pattern.CASE_INSENSITIVE);

    // Words that signal high criticality → ALERT.
    private static final Pattern ALERT = Pattern.compile(
            "\\b(urgent|asap|critical|outage|breach|incident|p1|sev[- ]?1|escalat|down|" +
            "immediately|emergency|security)\\b",
            Pattern.CASE_INSENSITIVE);

    // Words that signal a warning → WARNING.
    private static final Pattern WARNING = Pattern.compile(
            "\\b(reminder|overdue|at risk|delayed|past due|expir|warning|attention|" +
            "follow[- ]?up|pending|escalation)\\b",
            Pattern.CASE_INSENSITIVE);

    // Japanese equivalents — NO \b word boundaries (Japanese has no spaces, so \b
    // never matches between kanji/kana). Matched in addition to the English sets so
    // bilingual (JA+EN) mailboxes classify correctly.
    private static final Pattern ACTION_JA = Pattern.compile(
            "承認|確認|ご確認|返信|ご返信|回答|ご回答|対応|ご対応|要対応|提出|ご提出|署名|記入|" +
            "依頼|ご依頼|お願い|期限|締切|〆切|期日|までに|期日まで|出欠|可否");
    private static final Pattern ALERT_JA = Pattern.compile(
            "緊急|至急|大至急|重大|障害|停止|ダウン|インシデント|セキュリティ|侵害|即時|即対応|" +
            "エスカレ|危険|重要");
    private static final Pattern WARNING_JA = Pattern.compile(
            "注意|警告|リマインド|リマインダー|期限切れ|期限超過|遅延|未対応|超過|保留|催促|未完了|要注意");

    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b");
    private static final Pattern BY_WEEKDAY = Pattern.compile(
            "\\bby\\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b",
            Pattern.CASE_INSENSITIVE);

    // Japanese date expressions. Full-width digits are normalised to ASCII first.
    private static final Pattern JP_FULL_DATE  = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern JP_MONTH_DATE = Pattern.compile("(\\d{1,2})月(\\d{1,2})日");
    private static final Pattern JP_WEEKDAY    = Pattern.compile("([月火水木金土日])曜");

    @Override
    public Classification classify(GraphItem item) {
        String text = (safe(item.subject()) + " " + safe(item.bodyPreview())).toLowerCase();
        Map<String, Integer> breakdown = new LinkedHashMap<>();

        boolean alertHit = ALERT.matcher(text).find() || ALERT_JA.matcher(text).find();
        boolean warningHit = WARNING.matcher(text).find() || WARNING_JA.matcher(text).find();
        boolean actionHit = ACTION.matcher(text).find() || ACTION_JA.matcher(text).find();

        int score = 0;
        if (item.importanceHigh())  { score += 25; breakdown.put("importanceHigh", 25); }
        if (item.directRecipient()) { score += 10; breakdown.put("directRecipient", 10); }
        if (alertHit)               { score += 40; breakdown.put("alertKeyword", 40); }
        if (warningHit)             { score += 20; breakdown.put("warningKeyword", 20); }
        if (actionHit)              { score += 20; breakdown.put("actionKeyword", 20); }
        if (item.isCalendar())      { score += 10; breakdown.put("calendar", 10); }

        // Due-date extraction + deadline-proximity boost.
        Instant due = extractDueDate(text, item);
        if (due != null) {
            long hours = (due.toEpochMilli() - Instant.now().toEpochMilli()) / 3_600_000L;
            int proximity = hours < 0 ? 30 : hours < 24 ? 25 : hours < 72 ? 15 : hours < 168 ? 8 : 3;
            score += proximity;
            breakdown.put("deadlineProximity", proximity);
        }

        score = Math.min(100, score);

        // Kind: actionable items / meetings / things with a due date are TASKs.
        boolean isTask = actionHit || item.isCalendar() || due != null;
        String kind = isTask ? Classification.KIND_TASK : Classification.KIND_NOTIFICATION;

        String priority = score >= 70 ? "CRITICAL" : score >= 50 ? "HIGH" : score >= 30 ? "MEDIUM" : "LOW";
        String criticality = alertHit ? "ALERT" : warningHit ? "WARNING" : "INFORMATION";

        String rationale = buildRationale(item, alertHit, warningHit, actionHit, due, score);
        String summary = buildSummary(item);

        return new Classification(kind, priority, criticality, score, breakdown,
                rationale, due, summary, "RULES");
    }

    private static String buildRationale(GraphItem item, boolean alert, boolean warn, boolean action,
                                         Instant due, int score) {
        StringBuilder sb = new StringBuilder();
        if (item.importanceHigh()) sb.append("Flagged high-importance. ");
        if (alert) sb.append("Contains critical/urgent language. ");
        else if (warn) sb.append("Contains warning/reminder language. ");
        if (action) sb.append("Requests an action from the recipient. ");
        if (item.isCalendar()) sb.append("Calendar ").append("MEETING".equals(item.sourceType()) ? "meeting" : "event").append(". ");
        if (due != null) sb.append("Has an approaching due date. ");
        if (sb.length() == 0) sb.append("Routine informational item. ");
        sb.append("Score ").append(score).append("/100 (rule-based).");
        return sb.toString().trim();
    }

    private static String buildSummary(GraphItem item) {
        String preview = safe(item.bodyPreview()).trim();
        if (preview.isEmpty()) return safe(item.subject());
        // First sentence / 160 chars of the preview — never the full body.
        int cut = preview.indexOf(". ");
        String firstSentence = (cut > 20 && cut < 160) ? preview.substring(0, cut + 1) : preview;
        return firstSentence.length() > 200 ? firstSentence.substring(0, 200) + "…" : firstSentence;
    }

    /** Best-effort due-date extraction. Calendar items use their start time. */
    private static Instant extractDueDate(String text, GraphItem item) {
        if (item.eventStart() != null) return item.eventStart();

        // Normalise full-width digits (０-９) so both ISO and Japanese dates parse.
        String t = normalizeDigits(text);

        Matcher iso = ISO_DATE.matcher(t);
        if (iso.find()) {
            try {
                return LocalDate.parse(iso.group(1)).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) { /* fall through */ }
        }

        // ── Japanese absolute dates: 2026年6月24日 / 6月24日 ──────────────────────
        Matcher jf = JP_FULL_DATE.matcher(t);
        if (jf.find()) {
            try {
                return LocalDate.of(Integer.parseInt(jf.group(1)), Integer.parseInt(jf.group(2)),
                        Integer.parseInt(jf.group(3))).atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) { /* fall through */ }
        }
        Matcher jm = JP_MONTH_DATE.matcher(t);
        if (jm.find()) {
            try {
                LocalDate now = LocalDate.now(ZoneOffset.UTC);
                LocalDate cand = LocalDate.of(now.getYear(),
                        Integer.parseInt(jm.group(1)), Integer.parseInt(jm.group(2)));
                if (cand.isBefore(now)) cand = cand.plusYears(1);   // assume next occurrence
                return cand.atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) { /* fall through */ }
        }

        // ── Japanese relative day words (check 明後日 before 明日) ─────────────────
        if (t.contains("明後日")) {
            return LocalDate.now(ZoneOffset.UTC).plusDays(2).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if (t.contains("明日")) {
            return LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if (t.contains("本日") || t.contains("今日")) {
            return LocalDate.now(ZoneOffset.UTC).atTime(17, 0).toInstant(ZoneOffset.UTC);
        }
        if (t.contains("来週")) {
            return LocalDate.now(ZoneOffset.UTC).plusWeeks(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        }

        if (t.contains("tomorrow")) {
            return LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
        }
        if (t.contains("eod") || t.contains("end of day") || t.contains("today")) {
            return LocalDate.now(ZoneOffset.UTC).atTime(17, 0).toInstant(ZoneOffset.UTC);
        }

        // Weekday-by deadlines: English "by friday" or Japanese 金曜(日).
        Matcher wd = BY_WEEKDAY.matcher(t);
        if (wd.find()) {
            DayOfWeek target = englishWeekday(wd.group(1));
            if (target != null) return nextWeekdayAt17(target);
        }
        Matcher jw = JP_WEEKDAY.matcher(t);
        if (jw.find()) {
            DayOfWeek target = japaneseWeekday(jw.group(1).charAt(0));
            if (target != null) return nextWeekdayAt17(target);
        }
        return null;
    }

    private static Instant nextWeekdayAt17(DayOfWeek target) {
        LocalDate next = LocalDate.now(ZoneOffset.UTC).with(TemporalAdjusters.nextOrSame(target));
        return next.atTime(17, 0).toInstant(ZoneOffset.UTC);
    }

    private static DayOfWeek englishWeekday(String name) {
        try { return DayOfWeek.valueOf(name.toUpperCase()); }
        catch (Exception e) { return null; }
    }

    private static DayOfWeek japaneseWeekday(char c) {
        return switch (c) {
            case '月' -> DayOfWeek.MONDAY;
            case '火' -> DayOfWeek.TUESDAY;
            case '水' -> DayOfWeek.WEDNESDAY;
            case '木' -> DayOfWeek.THURSDAY;
            case '金' -> DayOfWeek.FRIDAY;
            case '土' -> DayOfWeek.SATURDAY;
            case '日' -> DayOfWeek.SUNDAY;
            default   -> null;
        };
    }

    /** Converts full-width digits (U+FF10–FF19) to ASCII so date regexes match. */
    private static String normalizeDigits(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0xFF10 && c <= 0xFF19) c = (char) (c - 0xFF10 + '0');
            b.append(c);
        }
        return b.toString();
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
