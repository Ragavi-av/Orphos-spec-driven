package io.orphos.eip.briefing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.orphos.eip.briefing.component.NlQueryClient;
import io.orphos.eip.common.access.DataDomains;
import io.orphos.eip.common.access.PersonaDomainPolicy;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Composes the dashboard's "what needs you today" digest: a one-line headline
 * plus the two or three things that need the user personally.
 *
 * <p>Two surfaces read this — the greeting banner (compact) and the AI Summary
 * tab (expanded, with per-item actions) — so every item carries BOTH the prose
 * and a reference back to the record it came from.</p>
 *
 * <p><b>How the model is used.</b> The candidate list is built deterministically
 * from ArcadeDB rows, each with a stable index. The model is asked only to pick
 * indices and write the wording for them; ids, types and severities are merged
 * back from the candidates afterwards. So a model that hallucinates a record
 * cannot introduce one — an unknown index is simply dropped — and the "Open"
 * action always points at a row that really exists.</p>
 *
 * <p>Every failure path falls back to the candidates' own titles with a
 * translated kind label, so the surfaces render with or without a model.</p>
 */
@Service
public class DailyDigestService {

    private static final Logger log = LogManager.getLogger(DailyDigestService.class);

    /** Matches AiUsageTypes.BRIEFING in nl-query-service. */
    private static final String USAGE_TYPE = "briefing";

    private static final int MAX_ITEMS = 3;

    /** Cap for the rule-based sections beneath "Critical now". */
    private static final int MAX_SECTION = 5;

    /** How far ahead a contract expiry counts as something to act on. */
    private static final int CONTRACT_HORIZON_DAYS = 180;

    private record CachedDigest(Map<String, Object> digest, long builtAt) {}

    private final ConcurrentHashMap<String, CachedDigest> cache = new ConcurrentHashMap<>();

    private final BriefingDataService data;
    private final NlQueryClient nlQuery;
    private final ObjectMapper mapper = new ObjectMapper();
    private final long cacheTtlMs;

    public DailyDigestService(BriefingDataService data,
                              NlQueryClient nlQuery,
                              @Value("${briefing.digest.cache-ttl-seconds:900}") long cacheTtlSeconds) {
        this.data = data;
        this.nlQuery = nlQuery;
        this.cacheTtlMs = Math.max(0, cacheTtlSeconds) * 1000L;
    }

    /**
     * @param tenantId tenant to read facts for
     * @param lang     UI language ("en" / "ja") the prose should be written in
     * @param force    skip the cache (the header's refresh button)
     * @param scope    the caller's persona scope: only facts in its business domains
     *                 are read into the digest, and the cache is keyed by it so one
     *                 persona's digest is never served to another
     */
    public Map<String, Object> digest(String tenantId, String lang, boolean force,
                                      PersonaDomainPolicy.Scope scope) {
        String language = normaliseLang(lang);
        String key = tenantId + "|" + language + "|" + scope.cacheKey();
        long now = System.currentTimeMillis();

        if (!force && cacheTtlMs > 0) {
            CachedDigest hit = cache.get(key);
            if (hit != null && now - hit.builtAt() < cacheTtlMs) {
                return hit.digest();
            }
        }

        Facts facts = readFacts(tenantId, scope);
        Map<String, Object> digest = compose(tenantId, facts, language);

        // The two rule-based sections beneath "Critical now". They are read
        // straight from the records and not sent to the model: their value is
        // in being complete and exact, and narrating a list of nine things
        // would cost a second completion to say less.
        digest.put("important", importantItems(tenantId, scope));
        digest.put("updates", recentUpdates(tenantId, scope));

        cache.put(key, new CachedDigest(digest, now));
        return digest;
    }

    // -- "Important" ---------------------------------------------------------

    /**
     * What is real but not necessarily today: standing risks, milestones that
     * have slipped or been missed, contracts approaching expiry, and risky
     * changes still to land.
     */
    private List<Map<String, Object>> importantItems(String tenantId, PersonaDomainPolicy.Scope scope) {
        List<Map<String, Object>> out = new ArrayList<>();
        LocalDate today = LocalDate.now();

        for (Map<String, Object> r : data.rows(tenantId, BriefingDataService.Q_OPEN_RISKS)) {
            String name = str(r.get("name"));
            if (name.isBlank()) continue;
            double residual = num(r.get("residualSeverity"));
            if (!scope.allows(DataDomains.RISK, residual >= 15, false)) continue;
            out.add(row("risk", str(r.get("id")), name,
                    // A residual score of 15+ on the 25-point inherent scale is
                    // the band the risk register itself treats as escalation.
                    residual >= 15 ? "critical" : "warning",
                    meta("category", str(r.get("riskCategory")),
                         "status", str(r.get("riskStatus")),
                         "exposure", str(r.get("exposureAmount")),
                         "currency", str(r.get("currency")),
                         "score", residual > 0 ? String.valueOf((long) residual) : "",
                         "description", str(r.get("description")),
                         "dueDate", str(r.get("remediationDueDate")))));
        }

        for (Map<String, Object> m : data.rows(tenantId, BriefingDataService.Q_OPEN_MILESTONES)) {
            String name = str(m.get("name"));
            String state = str(m.get("milestoneStatus")).toUpperCase(Locale.ROOT);
            LocalDate planned = dateOf(str(m.get("plannedEndDate")));
            boolean overdue = planned != null && planned.isBefore(today);
            // A milestone that is merely pending and still in the future is on
            // track — it belongs on a plan, not on an executive's summary.
            if (name.isBlank() || (!overdue && !state.equals("MISSED") && !state.equals("SLIPPED"))) continue;
            if (!scope.allows(DataDomains.STRATEGY, state.equals("MISSED") || overdue, false)) continue;
            out.add(row("milestone", str(m.get("id")), name,
                    state.equals("MISSED") || overdue ? "critical" : "warning",
                    meta("status", state, "dueDate", str(m.get("plannedEndDate")))));
        }

        for (Map<String, Object> c : data.rows(tenantId, BriefingDataService.Q_ACTIVE_CONTRACTS)) {
            String name = str(c.get("name"));
            LocalDate expiry = dateOf(str(c.get("expiryDate")));
            if (name.isBlank() || expiry == null) continue;
            long daysOut = expiry.toEpochDay() - today.toEpochDay();
            if (daysOut < 0 || daysOut > CONTRACT_HORIZON_DAYS) continue;
            if (!scope.allows(DataDomains.COMMERCIAL, daysOut <= 30, false)) continue;
            out.add(row("contract", str(c.get("id")), name,
                    daysOut <= 30 ? "critical" : "warning",
                    meta("dueDate", str(c.get("expiryDate")),
                         "value", str(c.get("contractValue")),
                         "currency", str(c.get("currency")),
                         "daysOut", String.valueOf(daysOut))));
        }

        for (Map<String, Object> ch : data.rows(tenantId, BriefingDataService.Q_UPCOMING_CHANGES)) {
            String name = str(ch.get("name"));
            // Only the risky ones. A routine change request is project noise here.
            if (name.isBlank() || !"HIGH".equalsIgnoreCase(str(ch.get("changeRisk")))) continue;
            if (!scope.allows(DataDomains.IT, false, false)) continue;
            out.add(row("change", str(ch.get("id")), name, "warning",
                    meta("status", str(ch.get("changeStatus")),
                         "risk", str(ch.get("changeRisk")),
                         "dueDate", str(ch.get("scheduledDate")))));
        }

        // Critical band first, then the order the sources were read in.
        out.sort((a, b) -> Boolean.compare(
                !"critical".equals(a.get("severity")), !"critical".equals(b.get("severity"))));
        return out.size() > MAX_SECTION ? new ArrayList<>(out.subList(0, MAX_SECTION)) : out;
    }

    // -- "What changed" ------------------------------------------------------

    /**
     * Recent activity in the organisation's own records — decisions the
     * platform logged, milestones met, releases shipped.
     *
     * Deliberately NOT windowed to "the last N days": on a tenant whose most
     * recent release was five weeks ago, a 7-day window renders an empty
     * section that reads as "nothing is happening" rather than "nothing
     * happened this week". The N most recent are shown with their dates, so
     * staleness is visible instead of hidden.
     */
    private List<Map<String, Object>> recentUpdates(String tenantId, PersonaDomainPolicy.Scope scope) {
        List<Map<String, Object>> out = new ArrayList<>();

        for (Map<String, Object> d : data.rows(tenantId, BriefingDataService.Q_RECENT_DECISIONS)) {
            String title = str(d.get("title"));
            if (title.isBlank()) continue;
            if (!scope.allows(DataDomains.of(str(d.get("domain")), title), false, false)) continue;
            out.add(row("decisionLogged", str(d.get("id")), title, "info",
                    meta("when", str(d.get("decidedAt")), "status", str(d.get("status")),
                         "domain", str(d.get("domain")))));
        }

        for (Map<String, Object> m : data.rows(tenantId, BriefingDataService.Q_MET_MILESTONES)) {
            String name = str(m.get("name"));
            if (name.isBlank() || !scope.allows(DataDomains.STRATEGY, false, false)) continue;
            out.add(row("milestoneMet", str(m.get("id")), name, "info",
                    meta("when", str(m.get("actualEndDate")))));
        }

        for (Map<String, Object> r : data.rows(tenantId, BriefingDataService.Q_RECENT_RELEASES)) {
            String name = str(r.get("name"));
            if (name.isBlank() || !scope.allows(DataDomains.IT, false, false)) continue;
            out.add(row("release", str(r.get("id")), name, "info",
                    meta("when", str(r.get("scheduledDate")),
                         "version", str(r.get("releaseVersion")))));
        }

        // Newest first across all three sources, undated rows last.
        out.sort((a, b) -> whenOf(b).compareTo(whenOf(a)));
        return out.size() > MAX_SECTION ? new ArrayList<>(out.subList(0, MAX_SECTION)) : out;
    }

    /** Sort key for an update row — the raw date string, or "" when absent. */
    @SuppressWarnings("unchecked")
    private String whenOf(Map<String, Object> row) {
        Object meta = row.get("meta");
        if (meta instanceof Map<?, ?> m) {
            Object when = ((Map<String, Object>) m).get("when");
            if (when != null) return when.toString();
        }
        return "";
    }

    /** A plain section row — same shape as a digest item, minus the model wording. */
    private Map<String, Object> row(String kind, String id, String title,
                                    String severity, Map<String, Object> meta) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("id", id == null ? "" : id);
        m.put("title", title);
        m.put("detail", "");
        m.put("severity", severity);
        m.put("sourceTitle", title);
        m.put("meta", meta);
        return m;
    }

    /** Numeric field that may arrive as Number or String. 0 when absent/unparseable. */
    private double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o == null || o.toString().isBlank() ? 0 : Double.parseDouble(o.toString().trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Lenient date parse for ArcadeDB values that may be date-only or full instants. */
    private LocalDate dateOf(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return LocalDate.parse(value.trim().substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }

    // -- Facts ---------------------------------------------------------------

    /** The raw material, read once and shared by the model prompt and the fallback. */
    private record Facts(List<Map<String, Object>> incidents,
                         List<Map<String, Object>> alerts,
                         List<Map<String, Object>> breachedSlas,
                         List<Map<String, Object>> overruns,
                         List<Map<String, Object>> pendingDecisions,
                         Map<String, Object> cashflow) {}

    /**
     * One actionable record, flattened to what both the prompt and the UI need.
     * {@code kind} drives the UI's badge, its "Open" destination and the
     * fallback wording; {@code id} lets the tab it opens select the row.
     *
     * <p>{@code context} is the English one-liner the model reads. {@code meta}
     * is the SAME facts unformatted, returned to the UI so a card always has
     * substance under its title — a thin model reply, or no model at all, must
     * not leave the row blank. The split exists because a sentence composed
     * here would be English-only on a Japanese dashboard.</p>
     */
    private record Candidate(int ref, String kind, String id, String title,
                             String context, Map<String, Object> meta, String severity) {}

    /**
     * Reads the facts, keeping only those in the caller's persona domains — the model
     * never sees, and so can never phrase, an item the persona isn't meant to have.
     * Rows carrying a {@code domain} label are placed by it; the rest by their kind.
     */
    private Facts readFacts(String tenantId, PersonaDomainPolicy.Scope scope) {
        List<Map<String, Object>> cash = data.rows(tenantId, BriefingDataService.Q_LATEST_CASHFLOW);
        return new Facts(
                keep(data.rows(tenantId, BriefingDataService.Q_ACTIVE_INCIDENTS), scope,
                        r -> labelOr(r, DataDomains.IT), r -> true),
                keep(data.rows(tenantId, BriefingDataService.Q_CRITICAL_ALERTS), scope,
                        r -> labelOr(r, DataDomains.IT),
                        r -> "critical".equals(severityOf(str(r.get("severity"))))),
                keep(data.rows(tenantId, BriefingDataService.Q_BREACHED_SLAS), scope,
                        r -> DataDomains.IT, r -> true),
                keep(data.rows(tenantId, BriefingDataService.Q_BUDGET_OVERRUNS), scope,
                        r -> DataDomains.FINANCE, r -> false),
                keep(data.rows(tenantId, BriefingDataService.Q_PENDING_DECISIONS), scope,
                        r -> DataDomains.of(str(r.get("domain")), str(r.get("title")), str(r.get("rationale"))),
                        r -> isOverdue(str(r.get("dueDate")))
                                || "CRITICAL".equalsIgnoreCase(str(r.get("priorityLevel")))),
                cash.isEmpty() || !scope.allows(DataDomains.FINANCE, false, false) ? Map.of() : cash.get(0));
    }

    /**
     * The raw result sets behind {@code /api/briefing/latest}, filtered to the persona
     * exactly as the digest is, in ArcadeDB's {@code {"result": [...]}} envelope.
     */
    public Map<String, Object> latestSets(String tenantId, PersonaDomainPolicy.Scope scope) {
        Facts f = readFacts(tenantId, scope);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("activeIncidents", Map.of("result", f.incidents()));
        out.put("criticalAlerts", Map.of("result", f.alerts()));
        out.put("breachedSLAs", Map.of("result", f.breachedSlas()));
        out.put("budgetOverruns", Map.of("result", f.overruns()));
        out.put("pendingDecisions", Map.of("result", f.pendingDecisions()));
        return out;
    }

    private static List<Map<String, Object>> keep(List<Map<String, Object>> rows, PersonaDomainPolicy.Scope scope,
                                                  java.util.function.Function<Map<String, Object>, String> domain,
                                                  java.util.function.Predicate<Map<String, Object>> critical) {
        if (scope.unrestricted()) return rows;
        return rows.stream().filter(r -> scope.allows(domain.apply(r), critical.test(r), false)).toList();
    }

    /** The row's own {@code domain} label when recognised, else the kind's default. */
    private String labelOr(Map<String, Object> row, String fallback) {
        String d = DataDomains.normalize(str(row.get("domain")));
        return d != null ? d : fallback;
    }

    /**
     * Flatten the facts into one ranked candidate list. Critical kinds lead:
     * an unresolved incident or a breached SLA outranks a pending decision,
     * and the model is told the order is already meaningful.
     */
    private List<Candidate> candidates(Facts facts) {
        List<Candidate> out = new ArrayList<>();

        for (Map<String, Object> a : facts.alerts()) {
            String severity = severityOf(str(a.get("severity")));
            add(out, "alert", str(a.get("id")), str(a.get("title")),
                str(a.get("message")),
                meta("message", str(a.get("message")), "domain", str(a.get("domain")),
                     "occurredAt", str(a.get("createdAt"))),
                severity);
        }
        for (Map<String, Object> s : facts.breachedSlas()) {
            String title = (str(s.get("system")) + " " + str(s.get("metricName"))).trim();
            String context = "actual " + str(s.get("actual")) + str(s.get("unit"))
                           + " against target " + str(s.get("target")) + str(s.get("unit"));
            add(out, "sla", str(s.get("id")), title, context,
                meta("actual", str(s.get("actual")), "target", str(s.get("target")),
                     "unit", str(s.get("unit")), "system", str(s.get("system"))),
                "critical");
        }
        for (Map<String, Object> i : facts.incidents()) {
            add(out, "incident", str(i.get("id")), str(i.get("title")),
                str(i.get("affectedSystem")),
                meta("system", str(i.get("affectedSystem")), "status", str(i.get("status")),
                     "occurredAt", str(i.get("createdAt"))),
                "critical");
        }
        for (Map<String, Object> c : facts.overruns()) {
            String context = "spent " + str(c.get("spent")) + " of " + str(c.get("budget"))
                           + " " + str(c.get("currency"));
            add(out, "budget", str(c.get("id")), str(c.get("name")), context,
                meta("spent", str(c.get("spent")), "budget", str(c.get("budget")),
                     "currency", str(c.get("currency")), "variance", str(c.get("variance"))),
                "warning");
        }
        for (Map<String, Object> d : facts.pendingDecisions()) {
            // A decision already past its due date is not a "warning" any more.
            String severity = isOverdue(str(d.get("dueDate")))
                    || "CRITICAL".equalsIgnoreCase(str(d.get("priorityLevel")))
                    ? "critical" : "warning";
            add(out, "decision", str(d.get("id")), str(d.get("title")),
                str(d.get("rationale")),
                meta("rationale", str(d.get("rationale")), "domain", str(d.get("domain")),
                     "dueDate", str(d.get("dueDate")), "occurredAt", str(d.get("createdAt")),
                     "impact", str(d.get("impactSummary")), "budget", str(d.get("linkedBudget")),
                     "priority", str(d.get("priorityLevel"))),
                severity);
        }
        return out;
    }

    /**
     * Source severity to the three bands the UI colours by.
     *
     * The old rule was "CRITICAL is red, everything else is a warning", which
     * flattened HIGH, MEDIUM and LOW into one amber and made every row look
     * equally urgent. HIGH earns red; LOW/INFO drop to the calm band so a
     * routine notice reads as one.
     */
    private String severityOf(String raw) {
        String s = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        return switch (s) {
            case "CRITICAL", "HIGH", "SEV1", "SEV2" -> "critical";
            case "LOW", "INFO", "INFORMATIONAL" -> "info";
            default -> "warning";
        };
    }

    /**
     * Pick the items to show, spreading them across kinds and business domains.
     *
     * Taken in raw order the list is alerts-first, so a tenant with three open
     * alerts got three alerts and the banner read as an IT status page. Walking
     * the kinds round-robin, and preferring a domain not already on screen,
     * surfaces the finance, delivery and governance items that were always in
     * the list but never reached the top three.
     */
    private List<Candidate> pickDiverse(List<Candidate> candidates, int max) {
        List<Candidate> picked = new ArrayList<>();
        Set<String> usedKinds = new LinkedHashSet<>();
        Set<String> usedDomains = new LinkedHashSet<>();
        List<Candidate> remaining = new ArrayList<>(candidates);

        // Pass 1: at most one per kind, preferring an unseen domain within it.
        while (picked.size() < max) {
            Candidate best = null;
            for (Candidate c : remaining) {
                if (usedKinds.contains(c.kind())) continue;
                String domain = domainOf(c);
                if (best == null) best = c;
                // A candidate whose domain is new wins over one already covered.
                if (!domain.isEmpty() && !usedDomains.contains(domain)) { best = c; break; }
            }
            if (best == null) break;
            picked.add(best);
            remaining.remove(best);
            usedKinds.add(best.kind());
            String d = domainOf(best);
            if (!d.isEmpty()) usedDomains.add(d);
        }

        // Pass 2: top up in the original (urgency) order if kinds ran out.
        for (Candidate c : remaining) {
            if (picked.size() >= max) break;
            picked.add(c);
        }
        return picked;
    }

    /** The business area a candidate belongs to, for the spread above. */
    private String domainOf(Candidate c) {
        Object d = c.meta() == null ? null : c.meta().get("domain");
        if (d != null && !d.toString().isBlank()) return d.toString().trim().toUpperCase(Locale.ROOT);
        // Kinds without a domain field still carry an implicit area.
        return switch (c.kind()) {
            case "budget" -> "FINANCE";
            case "sla", "incident" -> "IT";
            default -> "";
        };
    }

    /** Small ordered map builder — pairs, dropping any whose value is blank. */
    private Map<String, Object> meta(String... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String value = pairs[i + 1];
            // A blank field would render as "Target: " with nothing after it.
            if (value != null && !value.isBlank()) m.put(pairs[i], value.trim());
        }
        return m;
    }

    private void add(List<Candidate> out, String kind, String id, String title,
                     String context, Map<String, Object> meta, String severity) {
        // A row with no title cannot be shown or acted on, so it never becomes a
        // candidate — it would render as an empty bullet with a dead button.
        if (title == null || title.isBlank()) return;
        out.add(new Candidate(out.size(), kind, id, title.trim(),
                context == null ? "" : context.trim(), meta, severity));
    }

    // -- Composition ---------------------------------------------------------

    private Map<String, Object> compose(String tenantId, Facts facts, String language) {
        List<Candidate> candidates = candidates(facts);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now().toString());
        out.put("period", periodLabel(facts, language));
        out.put("allClear", candidates.isEmpty());
        out.put("totalCount", candidates.size());

        if (candidates.isEmpty()) {
            out.put("source", "empty");
            out.put("headline", "");
            out.put("items", List.of());
            out.put("otherCount", 0);
            return out;
        }

        Optional<Map<String, Object>> written = nlQuery.isEnabled()
                ? askModel(tenantId, candidates, language)
                : Optional.empty();

        if (written.isPresent()) {
            out.put("source", "ai");
            out.putAll(written.get());
        } else {
            out.put("source", "fallback");
            out.putAll(fallback(candidates));
        }

        int shown = ((List<?>) out.get("items")).size();
        // "3 items · everything else can wait" — the AI Summary needs to say how
        // much it is holding back, not just what it chose to show.
        out.put("otherCount", Math.max(0, candidates.size() - shown));
        return out;
    }

    /** Ask the tenant's model to select and phrase. Empty on any failure or unusable reply. */
    private Optional<Map<String, Object>> askModel(String tenantId, List<Candidate> candidates, String language) {
        try {
            String reply = nlQuery.complete(tenantId, prompt(candidates, language), USAGE_TYPE).orElse(null);
            if (reply == null) return Optional.empty();
            return parseReply(reply, candidates);
        } catch (Exception e) {
            log.debug("digest model call failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String prompt(List<Candidate> candidates, String language) {
        String candidatesJson;
        try {
            ArrayNode arr = mapper.createArrayNode();
            for (Candidate c : candidates) {
                ObjectNode n = mapper.createObjectNode();
                n.put("ref", c.ref());
                n.put("kind", c.kind());
                n.put("title", c.title());
                n.put("context", c.context());
                n.put("severity", c.severity());
                arr.add(n);
            }
            candidatesJson = mapper.writeValueAsString(arr);
        } catch (Exception e) {
            candidatesJson = "[]";
        }

        String languageRule = "ja".equals(language)
                ? "Write the headline and every title and detail in Japanese."
                : "Write the headline and every title and detail in English.";

        return "You are briefing a company executive at the start of their working day.\n\n"
             + "Below are the open items from their enterprise systems, already ordered by\n"
             + "urgency. Each has a numeric \"ref\".\n\n"
             + candidatesJson + "\n\n"
             + "Return ONLY a JSON object, with no code fence and no commentary, shaped exactly:\n"
             + "{\"headline\":\"...\",\"items\":[{\"ref\":0,\"title\":\"...\",\"detail\":\"...\"}]}\n\n"
             + "Rules:\n"
             + "- Choose at most " + MAX_ITEMS + " items, the ones the executive must personally\n"
             + "  act on today, most urgent first. Fewer is better than padding.\n"
             + "- Spread the choice across different business areas (finance, people, delivery,\n"
             + "  IT, security, governance) rather than three items from the same area, unless\n"
             + "  one area genuinely dominates today.\n"
             + "- Every \"ref\" MUST be one of the refs above. Never invent a ref.\n"
             + "- \"headline\" is one sentence, at most 120 characters, naming the single most\n"
             + "  important thing and whether it can still be fixed today.\n"
             + "- \"title\" is an imperative action, at most 60 characters.\n"
             + "- \"detail\" is a short clause explaining why it matters, at most 90 characters.\n"
             + "- Use ONLY the titles, numbers and context given above. Never invent a figure,\n"
             + "  a person, a deadline or a system that is not there.\n"
             + "- No markdown, no bullet characters, no text outside the JSON object.\n"
             + "- " + languageRule + "\n";
    }

    /**
     * Pull the JSON object out of a model reply and merge it back onto the
     * candidates. Models routinely wrap JSON in a code fence or add a sentence
     * before it, so the object is located by brace span rather than assuming
     * the whole reply parses.
     */
    private Optional<Map<String, Object>> parseReply(String reply, List<Candidate> candidates) {
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) return Optional.empty();

        try {
            JsonNode root = mapper.readTree(reply.substring(start, end + 1));
            String headline = root.path("headline").asText("").trim();
            JsonNode itemsNode = root.path("items");
            if (headline.isEmpty() || !itemsNode.isArray()) return Optional.empty();

            List<Map<String, Object>> items = new ArrayList<>();
            List<Integer> used = new ArrayList<>();
            for (JsonNode node : itemsNode) {
                int ref = node.path("ref").asInt(-1);
                // Out-of-range or repeated refs are dropped rather than guessed at:
                // the point of the ref is that every rendered row maps to a real record.
                if (ref < 0 || ref >= candidates.size() || used.contains(ref)) continue;
                String title = node.path("title").asText("").trim();
                if (title.isEmpty()) continue;

                used.add(ref);
                items.add(item(candidates.get(ref), title, node.path("detail").asText("").trim(), null));
                if (items.size() == MAX_ITEMS) break;
            }
            if (items.isEmpty()) return Optional.empty();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("headline", headline);
            out.put("items", items);
            return Optional.of(out);
        } catch (Exception e) {
            log.debug("digest reply was not usable JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Deterministic digest, used whenever the model is disabled, unreachable or
     * returns something unusable: the top candidates under their own titles,
     * with the kind carried as a translation key so the fallback stays
     * bilingual like the rest of the UI.
     */
    private Map<String, Object> fallback(List<Candidate> candidates) {
        List<Map<String, Object>> items = new ArrayList<>();
        for (Candidate c : pickDiverse(candidates, MAX_ITEMS)) {
            items.add(item(c, c.title(), "", "dashboard.digest.kind." + c.kind()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headline", "");
        // The shell renders this key when `headline` is blank, so the fallback
        // headline is translated rather than English-only.
        out.put("headlineKey", "dashboard.digest.fallbackHeadline");
        out.put("items", items);
        return out;
    }

    /** One rendered row: model wording (or the record's own) plus its provenance. */
    private Map<String, Object> item(Candidate c, String title, String detail, String detailKey) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ref", c.ref());
        m.put("kind", c.kind());
        m.put("id", c.id() == null ? "" : c.id());
        m.put("title", title);
        m.put("detail", detail == null ? "" : detail);
        if (detailKey != null) m.put("detailKey", detailKey);
        m.put("severity", c.severity());
        // The record's own title, always — the AI Summary shows it under the
        // model's imperative wording so the user can see what was rephrased.
        m.put("sourceTitle", c.title());
        // The record's facts, so the card carries a substantive line whatever
        // the model did or did not write. The UI formats and translates these.
        m.put("meta", c.meta() == null ? Map.of() : c.meta());
        return m;
    }

    // -- Small helpers -------------------------------------------------------

    /**
     * "FY2027 Q2 · WEEK 34" / "FY2027 Q2・第34週": fiscal year from the latest
     * cash-flow row, ISO week from today.
     *
     * The week label is localised here rather than sent as a catalog key because
     * the fiscal year and quarter beside it are tenant data with no key — the
     * shell renders the whole string verbatim, so an English "WEEK" baked in
     * here is what left the Japanese banner reading "FY2027 Q2 · WEEK 34".
     */
    private String periodLabel(Facts facts, String language) {
        int week = LocalDate.now().get(WeekFields.ISO.weekOfWeekBasedYear());
        boolean ja = "ja".equals(language);
        String weekLabel = ja ? "第" + week + "週" : "WEEK " + week;

        Object fy = facts.cashflow().get("fiscalYear");
        if (fy == null || fy.toString().isBlank()) {
            return weekLabel;
        }
        String quarter = quarterOf(facts.cashflow().get("monthIndex"));
        return quarter.isEmpty()
                ? fy + " · " + weekLabel
                : fy + " " + quarter + " · " + weekLabel;
    }

    /** Fiscal month index (1-12) to quarter label. Blank when missing or out of range. */
    private String quarterOf(Object monthIndex) {
        if (monthIndex == null) return "";
        try {
            int m = Integer.parseInt(monthIndex.toString().trim());
            if (m < 1 || m > 12) return "";
            return "Q" + ((m - 1) / 3 + 1);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    /**
     * True when a due date has already passed. Dates arrive as strings from
     * ArcadeDB in whatever shape the source wrote them, so an unparseable value
     * is treated as "not overdue" rather than escalating a row on a bad field.
     */
    private boolean isOverdue(String dueDate) {
        if (dueDate == null || dueDate.isBlank()) return false;
        try {
            String v = dueDate.trim();
            // Date-only values ("2026-08-24") are due at the end of that day.
            LocalDate due = v.length() >= 10
                    ? LocalDate.parse(v.substring(0, 10))
                    : LocalDate.parse(v);
            return due.isBefore(LocalDate.now());
        } catch (Exception e) {
            return false;
        }
    }

    private String normaliseLang(String lang) {
        return lang != null && lang.toLowerCase(Locale.ROOT).startsWith("ja") ? "ja" : "en";
    }
}
