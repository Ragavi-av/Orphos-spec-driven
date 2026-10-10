package io.orphos.eip.common.access;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The business domains an item (email task, notification, meeting, decision, incident,
 * approval, briefing candidate…) can belong to, and how to tell which one it is.
 *
 * <p>Deliberately coarse — one key per executive audience — so a persona's scope is a
 * short, readable list (see {@link PersonaDomainPolicy}). The keys line up with the
 * top-level domains of {@code ontology/eip-governance.owl} (Finance, Hr, It, Commercial,
 * Strategy, Risk, Enterprise), plus {@link #OPERATIONS} for the COO.</p>
 *
 * <p>Two ways in:</p>
 * <ul>
 *   <li>{@link #normalize} — a free-text domain label already on a record (the ontology
 *       rows carry {@code domain = FINANCE | HR | IT | SECURITY | SALES | OPERATIONS …}).</li>
 *   <li>{@link #classify} — keyword classification of an item's text when it carries no
 *       label (emails, chat messages, meeting invites). Title words count double.
 *       Anything without a clear signal is {@link #ENTERPRISE}: company-wide, visible to
 *       every member.</li>
 * </ul>
 */
public final class DataDomains {

    private DataDomains() {}

    /** Company-wide / general: no specific domain. Visible to every member. */
    public static final String ENTERPRISE = "enterprise";
    public static final String STRATEGY   = "strategy";
    public static final String FINANCE    = "finance";
    public static final String COMMERCIAL = "commercial";
    public static final String RISK       = "risk";
    public static final String HR         = "hr";
    public static final String IT         = "it";
    public static final String OPERATIONS = "operations";

    /** Every domain, in display order. */
    public static final List<String> ALL = List.of(
            ENTERPRISE, STRATEGY, FINANCE, COMMERCIAL, RISK, HR, IT, OPERATIONS);

    /** Free-text labels (upper-cased) → domain. Covers the ontology row values and common synonyms. */
    private static final Map<String, String> LABELS = buildLabels();

    private static Map<String, String> buildLabels() {
        Map<String, String> m = new java.util.HashMap<>();
        label(m, FINANCE, "FINANCE", "FINANCIAL", "FIN", "ACCOUNTING", "ACCOUNTS", "TREASURY", "BUDGET",
                "GENERALLEDGER", "LEDGER", "TAX", "AUDIT", "PAYABLES", "RECEIVABLES", "PROCUREMENT_FINANCE");
        label(m, HR, "HR", "HUMAN RESOURCES", "HUMAN_RESOURCES", "PEOPLE", "PEOPLE OPS", "TALENT", "PAYROLL",
                "HEADCOUNT", "WORKFORCE", "RECRUITING", "RECRUITMENT");
        label(m, IT, "IT", "TECH", "TECHNOLOGY", "ENGINEERING", "INFRASTRUCTURE", "INFRA", "SECURITY",
                "CYBERSECURITY", "ITOPS", "IT_OPS", "IT OPERATIONS", "ITOPERATIONS", "ITASSETS", "ITSECURITY",
                "ITSPEND", "DEVOPS", "PLATFORM", "DATA", "SOFTWARE");
        label(m, COMMERCIAL, "COMMERCIAL", "SALES", "MARKETING", "CUSTOMER", "CUSTOMERS", "PIPELINE",
                "CONTRACTS", "BUSINESS DEVELOPMENT", "BD", "REVENUE OPS", "PARTNERS");
        label(m, OPERATIONS, "OPERATIONS", "OPS", "SUPPLY CHAIN", "SUPPLY_CHAIN", "LOGISTICS", "PROCUREMENT",
                "MANUFACTURING", "FACILITIES", "VENDOR", "VENDORS");
        label(m, STRATEGY, "STRATEGY", "PORTFOLIO", "EXECUTIVE", "BOARD", "CORPORATE", "LEADERSHIP");
        label(m, RISK, "RISK", "COMPLIANCE", "LEGAL", "REGULATORY", "GOVERNANCE_RISK");
        label(m, ENTERPRISE, "ENTERPRISE", "GENERAL", "COMPANY", "ALL", "ORGANISATION", "ORGANIZATION");
        return Map.copyOf(m);
    }

    private static void label(Map<String, String> m, String domain, String... labels) {
        for (String l : labels) m.put(l, domain);
    }

    /**
     * The domain for a free-text label, or {@code null} if the label is blank or unknown
     * (callers then fall back to {@link #classify} or a type default).
     */
    public static String normalize(String label) {
        if (label == null || label.isBlank()) return null;
        String key = label.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        String hit = LABELS.get(key);
        if (hit == null) hit = LABELS.get(key.replace('_', ' '));
        if (hit == null && ALL.contains(label.trim().toLowerCase(Locale.ROOT))) {
            hit = label.trim().toLowerCase(Locale.ROOT);
        }
        return hit;
    }

    /** Keyword patterns per domain. Whole-word, case-insensitive; stems end in \w* on purpose. */
    private static final Map<String, Pattern> KEYWORDS = buildKeywords();

    private static Map<String, Pattern> buildKeywords() {
        Map<String, Pattern> m = new LinkedHashMap<>();
        m.put(FINANCE, words("invoice\\w*", "budget\\w*", "revenue", "cash ?flow", "cash", "expense\\w*",
                "forecast\\w*", "p&l", "profit", "margin\\w*", "ebitda", "audit\\w*", "tax\\w*", "capex", "opex",
                "financ\\w*", "accounting", "accounts payable", "accounts receivable", "payment\\w*", "reimburse\\w*",
                "treasury", "ledger", "quarter(ly)? results", "earnings", "funding", "valuation", "cost overrun\\w*",
                "spend", "variance"));
        m.put(HR, words("hiring", "hire\\w*", "recruit\\w*", "candidate\\w*", "interview\\w*", "onboard\\w*",
                "offboard\\w*", "employee\\w*", "staff", "annual leave", "sick leave", "leave requests?", "vacation", "payroll", "salar\\w*", "appraisal\\w*",
                "performance review\\w*", "attrition", "headcount", "benefits", "training", "workforce", "people team",
                "hr", "human resources", "resignation\\w*", "promotion\\w*", "compensation", "culture", "wellbeing",
                "engagement survey"));
        m.put(IT, words("server\\w*", "outage\\w*", "downtime", "incident\\w*", "deploy\\w*", "release\\w*", "bug\\w*",
                "security", "vulnerabilit\\w*", "cve", "patch\\w*", "infrastructure", "cloud", "aws", "azure", "gcp",
                "network\\w*", "database\\w*", "apis?", "latency", "uptime", "kubernetes", "devops", "sprint\\w*",
                "github", "jira", "firewall", "phishing", "malware", "backup\\w*", "it support", "helpdesk",
                "slas?", "engineering", "architecture", "data pipeline\\w*"));
        m.put(COMMERCIAL, words("customer\\w*", "client\\w*", "deal\\w*", "contract\\w*", "proposal\\w*",
                "sales", "pipeline", "leads?", "renewal\\w*", "quote\\w*", "rfp", "pricing", "partner\\w*",
                "account manager", "upsell", "churn", "opportunit\\w*", "marketing", "campaign\\w*"));
        m.put(OPERATIONS, words("vendor\\w*", "supplier\\w*", "logistics", "procurement", "inventory",
                "shipment\\w*", "warehouse\\w*", "facilit\\w*", "supply chain", "manufactur\\w*", "fulfil\\w*",
                "operations", "operational", "purchase order\\w*"));
        m.put(STRATEGY, words("board", "strategy", "strategic", "roadmap\\w*", "acquisition\\w*", "merger\\w*",
                "investor\\w*", "okr\\w*", "vision", "offsite", "town ?hall", "all[- ]hands", "annual plan"));
        m.put(RISK, words("compliance", "legal", "regulat\\w*", "risks?", "litigation", "gdpr", "lawsuit\\w*",
                "policy breach", "sanction\\w*", "insurance", "whistleblow\\w*"));
        return m;
    }

    private static Pattern words(String... terms) {
        return Pattern.compile("\\b(?:" + String.join("|", terms) + ")\\b",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    }

    /**
     * Classifies an item from its text. {@code title} matches count double. Returns the
     * domain with the highest score, or {@link #ENTERPRISE} when nothing matches or the
     * top score is tied between domains (an ambiguous item is general, not hidden).
     *
     * @param title  the item's headline (email subject, task title, meeting subject)
     * @param others body text: summary, rationale, sender, organiser…
     */
    public static String classify(String title, String... others) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        addScores(scores, title, 2);
        if (others != null) for (String o : others) addScores(scores, o, 1);
        String best = ENTERPRISE;
        int bestScore = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> e : scores.entrySet()) {
            if (e.getValue() > bestScore) { best = e.getKey(); bestScore = e.getValue(); tie = false; }
            else if (e.getValue() == bestScore && bestScore > 0) tie = true;
        }
        return bestScore == 0 || tie ? ENTERPRISE : best;
    }

    private static void addScores(Map<String, Integer> scores, String text, int weight) {
        if (text == null || text.isBlank()) return;
        for (Map.Entry<String, Pattern> e : KEYWORDS.entrySet()) {
            Matcher m = e.getValue().matcher(text);
            int hits = 0;
            while (m.find()) hits++;
            if (hits > 0) scores.merge(e.getKey(), hits * weight, Integer::sum);
        }
    }

    /**
     * An item's domain: its own label when it has a recognised one, otherwise the
     * keyword classification of its text.
     */
    public static String of(String label, String title, String... others) {
        String labelled = normalize(label);
        return labelled != null ? labelled : classify(title, others);
    }

    private static final Set<String> KNOWN = Set.copyOf(ALL);

    /** True if {@code domain} is one of {@link #ALL}. */
    public static boolean isKnown(String domain) {
        return domain != null && KNOWN.contains(domain);
    }
}
