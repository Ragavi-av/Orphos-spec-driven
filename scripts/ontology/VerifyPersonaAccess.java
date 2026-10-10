import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asserts that the persona/RBAC data loaded by {@link LoadPersonaDomainProfile}
 * actually produces the access decisions the design promises — the ArcadeDB
 * counterpart of {@code ValidateOntology}, which does the same job for the OWL
 * side.
 *
 * <p>This exists because "the rows loaded" and "the policy works" are different
 * claims. A persona can have every edge present and still resolve to the wrong
 * domain set if an inheritance link points the wrong way or a sub-domain parent
 * is missing. Each check below is a decision a service will make at request time
 * in step 3, so a failure here is a failure that would otherwise show up as
 * silently-too-much access.</p>
 *
 * <h2>The decision under test</h2>
 * A persona may read a record iff:
 * <ol>
 *   <li>the record's data domain is in the persona's EFFECTIVE domain set —
 *       the domains granted to the persona or any persona it inherits from,
 *       expanded down the sub-domain tree; and</li>
 *   <li>the record's sensitivity rank is at most the persona's maxSensitivity rank.</li>
 * </ol>
 * Both closures are computed by ArcadeDB {@code TRAVERSE} in a single query, so
 * enforcement needs no application-side graph walking.
 *
 * <h2>Usage</h2>
 * <pre>
 *   java -cp "&lt;jackson jars&gt;" VerifyPersonaAccess.java &lt;tenantId&gt; &lt;database&gt;
 * </pre>
 * Exit 0 = all checks passed, 1 = at least one failed.
 */
public final class VerifyPersonaAccess {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String baseUrl;
    private static String authHeader;
    private static String db;
    private static String tenantId;

    private static int checks;
    private static int failures;

    /** Cache: roleKey → effective domain set. */
    private static final Map<String, Set<String>> effectiveDomains = new LinkedHashMap<>();
    /** Cache: roleKey → maxSensitivity rank. */
    private static final Map<String, Integer> maxRank = new LinkedHashMap<>();
    /** Cache: graphType → [domain, rank]. */
    private static final Map<String, Object[]> classification = new LinkedHashMap<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java VerifyPersonaAccess.java <tenantId> <database>");
            System.exit(2);
        }
        tenantId = args[0];
        db = args[1];

        Map<String, String> env = readEnv();
        baseUrl = firstNonBlank(env.get("ARCADEDB_URL"), System.getenv("ARCADEDB_URL"), "http://localhost:2480");
        String user = firstNonBlank(env.get("ARCADEDB_USER"), System.getenv("ARCADEDB_USER"), "root");
        String password = firstNonBlank(env.get("ARCADEDB_PASSWORD"), System.getenv("ARCADEDB_PASSWORD"), null);
        if (password == null) {
            System.err.println("ARCADEDB_PASSWORD not found in .env or environment.");
            System.exit(3);
        }
        authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));

        System.out.printf("ArcadeDB %s, database %s, tenant %s%n%n", baseUrl, db, tenantId);

        // ── 1. Effective domain sets ────────────────────────────────────────
        System.out.println("== Effective domain sets (inheritance + sub-domain closure) ==");

        checkDomains("cfo",
                List.of("dom-finance", "dom-budget", "dom-treasury", "dom-gl", "dom-payroll",
                        "dom-it-spend", "dom-commercial", "dom-pipeline", "dom-contracts",
                        "dom-risk", "dom-strategy", "dom-enterprise"),
                List.of("dom-hr", "dom-headcount", "dom-talent", "dom-it", "dom-it-ops",
                        "dom-it-assets", "dom-it-security", "dom-governance"));

        checkDomains("cto",
                List.of("dom-it", "dom-it-ops", "dom-it-assets", "dom-it-security", "dom-it-spend",
                        "dom-portfolio", "dom-strategy", "dom-enterprise"),
                List.of("dom-finance", "dom-budget", "dom-treasury", "dom-gl", "dom-payroll",
                        "dom-hr", "dom-headcount", "dom-commercial", "dom-contracts",
                        "dom-pipeline", "dom-risk", "dom-governance"));

        checkDomains("ceo",
                List.of("dom-finance", "dom-budget", "dom-treasury", "dom-it-spend",
                        "dom-commercial", "dom-risk", "dom-strategy", "dom-enterprise"),
                // Executive and finance focus: technology and people detail are the CTO's and CHRO's.
                List.of("dom-governance", "dom-hr", "dom-payroll", "dom-it", "dom-it-ops"));

        checkDomains("chro",
                List.of("dom-hr", "dom-headcount", "dom-payroll", "dom-talent",
                        "dom-strategy", "dom-enterprise"),
                List.of("dom-finance", "dom-it", "dom-commercial", "dom-risk", "dom-governance"));

        // The deliberate tightening: an admin configures the platform but holds no
        // business domain beyond what Viewer gives.
        checkDomains("admin",
                List.of("dom-governance", "dom-enterprise"),
                List.of("dom-finance", "dom-budget", "dom-payroll", "dom-hr", "dom-it",
                        "dom-it-ops", "dom-commercial", "dom-risk", "dom-strategy"));

        checkDomains("viewer", List.of("dom-enterprise"),
                List.of("dom-finance", "dom-hr", "dom-it", "dom-commercial", "dom-strategy",
                        "dom-risk", "dom-governance"));

        // ── 2. The full decision, per graph type ────────────────────────────
        System.out.println("\n== Access decisions on real graph types ==");

        // Payroll: the CFO owns the cost line, the CHRO owns the people, the CTO
        // gets neither, and an admin does not get it just for being an admin.
        decide("cfo",    "HrPayrollRun", true);
        decide("chro",   "HrPayrollRun", true);
        decide("ceo",    "HrPayrollRun", false);
        decide("cto",    "HrPayrollRun", false);
        decide("admin",  "HrPayrollRun", false);
        decide("viewer", "HrPayrollRun", false);

        // BudgetItem is classified into the dual-parent IT Spend domain, so it must
        // be reachable from BOTH the CTO's IT grant and the CFO's Finance grant.
        // This is the single most load-bearing bit of the domain hierarchy.
        decide("cto",    "BudgetItem", true);
        decide("cfo",    "BudgetItem", true);
        decide("viewer", "BudgetItem", false);

        // ...but the rest of the ledger stays shut to the CTO.
        decide("cto",    "FinCashFlowEntry", false);
        decide("cto",    "FinGLAccount",     false);
        decide("cfo",    "FinCashFlowEntry", true);

        // IT ops: the CTO's. The CEO gets only critical ones, via the item-level escalation rule.
        decide("cto",    "Incident", true);
        decide("ceo",    "Incident", false);
        decide("cfo",    "Incident", false);

        // Vulnerabilities are Confidential IT Security, not plain Internal IT.
        decide("cto",    "ItopsVulnerability", true);
        decide("cfo",    "ItopsVulnerability", false);
        decide("viewer", "ItopsVulnerability", false);

        // Risk register.
        decide("cfo",    "RskRisk", true);
        decide("ceo",    "RskRisk", true);
        decide("cto",    "RskRisk", false);

        // Platform governance is the admin's, and NOT the CEO's.
        decide("admin",  "GovAccessGrant", true);
        decide("ceo",    "GovAccessGrant", false);

        // Pure sensitivity gate: Executive HOLDS dom-strategy, but Decision is
        // Confidential (rank 2) and Executive's ceiling is Internal (rank 1). This
        // is the check that proves the two axes are independent — a domain grant
        // alone is not enough.
        decide("executive", "Decision", false);
        decide("ceo",       "Decision", true);
        // Same persona, same domain, lower sensitivity → allowed.
        decide("executive", "StrObjective", true);

        // Public company data still needs a domain grant: Guest has none, so it is
        // denied even at rank 0. Worth knowing — if guests should see the mission
        // and values, Guest needs an explicit dom-enterprise grant.
        decide("viewer", "EntCompany", true);
        decide("guest",  "EntCompany", false);

        // ── 3. Persona-scoped default widgets ───────────────────────────────
        System.out.println("\n== Persona-scoped widget layouts (step 4 input) ==");
        // Counts include metrics inherited through the persona chain: every C-level
        // persona inherits Executive's pending-decisions. CEO is the exception at 6
        // rather than 7 because it already watches that metric directly, so the
        // inherited one dedupes away — which is itself worth pinning down.
        Map<String, Integer> expectedWidgetCounts = new LinkedHashMap<>();
        expectedWidgetCounts.put("cto", 8);   // 7 own + pending-decisions inherited
        expectedWidgetCounts.put("cfo", 7);   // 6 own + pending-decisions inherited
        expectedWidgetCounts.put("ceo", 8);   // 8 own, one of which IS pending-decisions
        expectedWidgetCounts.put("chro", 4);  // 3 own + pending-decisions inherited
        expectedWidgetCounts.put("admin", 0); // configures the platform, watches nothing
        for (Map.Entry<String, Integer> e : expectedWidgetCounts.entrySet()) {
            List<String> keys = widgetKeysFor(e.getKey());
            check(keys.size() == e.getValue(),
                    String.format("%-9s → %d widgets %s", e.getKey(), keys.size(), keys),
                    "expected " + e.getValue());
        }
        // Two personas must not resolve to the same board, or the whole exercise is moot.
        check(!new LinkedHashSet<>(widgetKeysFor("cfo")).equals(new LinkedHashSet<>(widgetKeysFor("cto"))),
                "CFO and CTO resolve to DIFFERENT widget sets", "they were identical");

        // ── 4. The k-anonymity floor on payroll aggregates ──────────────────
        System.out.println("\n== Payroll k-anonymity floor ==");
        List<JsonNode> below = query(
                "SELECT externalId, coveredHeadcount, minimumPopulation FROM HrPayrollRun "
                        + "WHERE tenantId = :tenantId AND coveredHeadcount < minimumPopulation",
                MAPPER.createObjectNode().put("tenantId", tenantId));
        check(below.size() == 1, "exactly 1 payroll run is below its k-anonymity floor",
                "found " + below.size());
        if (below.size() == 1) {
            check("pay-quality-q2".equals(below.get(0).path("externalId").asText()),
                    "the suppressible run is pay-quality-q2 (4 people, floor 5)",
                    "was " + below.get(0).path("externalId").asText());
        }
        System.out.println("  NOTE: this row is loaded ON PURPOSE. Step 3's read path must suppress it;");
        System.out.println("        if it renders on a dashboard, the k-anonymity rule is not wired up.");

        // ── 5. Persona → real person, for org-scoped filtering ──────────────
        System.out.println("\n== Persona embodiment (OWN_* access scopes) ==");
        checkEmbodiment("cto", "exec-dir-projtech");
        checkEmbodiment("ceo", "exec-dir-prodtech");
        checkEmbodiment("coo", "exec-dir-salesops");
        checkEmbodiment("executive", "exec-advisor");
        checkEmbodiment("cfo", null);   // no CFO among this tenant's executives
        checkEmbodiment("chro", null);

        // ── 6. Structural completeness ──────────────────────────────────────
        System.out.println("\n== Structural completeness ==");
        long personas = count("GovPersona");
        long withCeiling = count("GovPersona", "out('MAX_SENSITIVITY').size() > 0");
        check(personas == withCeiling,
                "all " + personas + " personas have a maxSensitivity ceiling",
                withCeiling + " of " + personas + " do");

        long classifications = count("GovClassification");
        long fullyClassified = count("GovClassification",
                "out('IN_DATA_DOMAIN').size() > 0 AND out('HAS_SENSITIVITY').size() > 0");
        check(classifications == fullyClassified,
                "all " + classifications + " classifications carry both a domain and a sensitivity",
                fullyClassified + " of " + classifications + " do");

        // Every graph type holding tenant rows should be classified, or records
        // exist that no access rule can reason about.
        List<String> unclassified = new ArrayList<>();
        for (String type : List.of("Incident", "ITSystem", "SLAMetric", "Alert", "CostCentre",
                "BudgetItem", "Project", "Decision", "EntCompany", "EntOrgUnit", "EntExecutive",
                "EntClient", "EntEngagement", "FinBudgetLine", "HrPayrollRun", "ComContract",
                "StrObjective", "RskRisk", "ItopsAsset")) {
            if (classificationFor(type) == null) unclassified.add(type);
        }
        check(unclassified.isEmpty(), "every populated graph type is classified",
                "unclassified: " + unclassified);

        System.out.println();
        System.out.printf("%d checks, %d failed.%n", checks, failures);
        if (failures == 0) System.out.println("ALL CHECKS PASSED");
        else System.exit(1);
    }

    // ── the queries under test ─────────────────────────────────────────────

    /**
     * A persona's effective domain set, in ONE query: walk the persona
     * inheritance chain, take every granted domain, then expand down the
     * sub-domain tree. TRAVERSE includes the starting vertices, which is what
     * makes the persona's own grants and each granted domain itself part of the
     * result.
     */
    private static Set<String> domainsFor(String roleKey) throws Exception {
        Set<String> cached = effectiveDomains.get(roleKey);
        if (cached != null) return cached;
        ObjectNode p = MAPPER.createObjectNode().put("tenantId", tenantId).put("roleKey", roleKey);
        List<JsonNode> rows = query(
                "SELECT externalId FROM (TRAVERSE in('SUB_DOMAIN_OF') FROM ("
                        + "  SELECT expand(out('CAN_ACCESS_DOMAIN')) FROM ("
                        + "    TRAVERSE out('INHERITS_FROM') FROM ("
                        + "      SELECT FROM GovPersona WHERE tenantId = :tenantId AND roleKey = :roleKey"
                        + "    )"
                        + "  )"
                        + ")) WHERE tenantId = :tenantId", p);
        Set<String> out = new LinkedHashSet<>();
        for (JsonNode r : rows) out.add(r.path("externalId").asText());
        effectiveDomains.put(roleKey, out);
        return out;
    }

    private static int maxRankFor(String roleKey) throws Exception {
        Integer cached = maxRank.get(roleKey);
        if (cached != null) return cached;
        ObjectNode p = MAPPER.createObjectNode().put("tenantId", tenantId).put("roleKey", roleKey);
        List<JsonNode> rows = query("SELECT out('MAX_SENSITIVITY').sensitivityRank AS rank "
                + "FROM GovPersona WHERE tenantId = :tenantId AND roleKey = :roleKey", p);
        int rank = -1;
        if (!rows.isEmpty()) {
            JsonNode r = rows.get(0).path("rank");
            rank = r.isArray() ? (r.isEmpty() ? -1 : r.get(0).asInt(-1)) : r.asInt(-1);
        }
        maxRank.put(roleKey, rank);
        return rank;
    }

    /** graphType → {domainExternalId, sensitivityRank}, or null if unclassified. */
    private static Object[] classificationFor(String graphType) throws Exception {
        if (classification.containsKey(graphType)) return classification.get(graphType);
        ObjectNode p = MAPPER.createObjectNode().put("tenantId", tenantId).put("graphType", graphType);
        List<JsonNode> rows = query("SELECT out('IN_DATA_DOMAIN').externalId AS dom, "
                + "out('HAS_SENSITIVITY').sensitivityRank AS rank "
                + "FROM GovClassification WHERE tenantId = :tenantId AND graphType = :graphType", p);
        Object[] result = null;
        if (!rows.isEmpty()) {
            String dom = first(rows.get(0).path("dom"));
            String rank = first(rows.get(0).path("rank"));
            if (dom != null && rank != null) result = new Object[]{dom, Integer.parseInt(rank)};
        }
        classification.put(graphType, result);
        return result;
    }

    /** The whole decision, exactly as a service would evaluate it. */
    private static boolean allows(String roleKey, String graphType) throws Exception {
        Object[] cls = classificationFor(graphType);
        if (cls == null) return false;  // unclassified data is not readable — fail closed
        String domain = (String) cls[0];
        int rank = (Integer) cls[1];
        return domainsFor(roleKey).contains(domain) && rank <= maxRankFor(roleKey);
    }

    private static List<String> widgetKeysFor(String roleKey) throws Exception {
        ObjectNode p = MAPPER.createObjectNode().put("tenantId", tenantId).put("roleKey", roleKey);
        List<JsonNode> rows = query(
                "SELECT DISTINCT(widgetKey) AS widgetKey FROM ("
                        + "  SELECT expand(out('RENDERED_BY')) FROM ("
                        + "    SELECT expand(out('WATCHES_METRIC')) FROM ("
                        + "      TRAVERSE out('INHERITS_FROM') FROM ("
                        + "        SELECT FROM GovPersona WHERE tenantId = :tenantId AND roleKey = :roleKey"
                        + "      )"
                        + "    )"
                        + "  )"
                        + ")", p);
        List<String> keys = new ArrayList<>();
        for (JsonNode r : rows) {
            String k = r.path("widgetKey").asText(null);
            if (k != null && !k.isBlank() && !keys.contains(k)) keys.add(k);
        }
        return keys;
    }

    // ── assertions ─────────────────────────────────────────────────────────

    private static void checkDomains(String roleKey, List<String> mustHave,
                                     List<String> mustNotHave) throws Exception {
        Set<String> actual = domainsFor(roleKey);
        List<String> missing = new ArrayList<>();
        for (String d : mustHave) if (!actual.contains(d)) missing.add(d);
        List<String> leaked = new ArrayList<>();
        for (String d : mustNotHave) if (actual.contains(d)) leaked.add(d);

        check(missing.isEmpty() && leaked.isEmpty(),
                String.format("%-9s → %d domains", roleKey, actual.size()),
                (missing.isEmpty() ? "" : "MISSING " + missing + " ")
                        + (leaked.isEmpty() ? "" : "LEAKED " + leaked));
    }

    private static void decide(String roleKey, String graphType, boolean expected) throws Exception {
        boolean actual = allows(roleKey, graphType);
        check(actual == expected,
                String.format("%-9s %-20s %s", roleKey, graphType, actual ? "ALLOW" : "DENY"),
                "expected " + (expected ? "ALLOW" : "DENY"));
    }

    private static void checkEmbodiment(String roleKey, String expectedExecutive) throws Exception {
        ObjectNode p = MAPPER.createObjectNode().put("tenantId", tenantId).put("roleKey", roleKey);
        List<JsonNode> rows = query("SELECT out('EMBODIED_BY').externalId AS exec "
                + "FROM GovPersona WHERE tenantId = :tenantId AND roleKey = :roleKey", p);
        String actual = rows.isEmpty() ? null : first(rows.get(0).path("exec"));
        boolean ok = expectedExecutive == null ? actual == null : expectedExecutive.equals(actual);
        check(ok, String.format("%-9s embodied by %s", roleKey,
                        actual == null ? "(nobody — OWN_* scopes fall back to tenant-wide)" : actual),
                "expected " + expectedExecutive);
    }

    private static void check(boolean ok, String what, String detail) {
        checks++;
        if (!ok) failures++;
        System.out.printf("  [%s] %s%s%n", ok ? "PASS" : "FAIL", what,
                ok ? "" : "  ← " + detail);
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private static long count(String type) throws Exception {
        return count(type, null);
    }

    private static long count(String type, String where) throws Exception {
        String sql = "SELECT count(*) AS c FROM " + type + " WHERE tenantId = :tenantId"
                + (where == null ? "" : " AND " + where);
        List<JsonNode> rows = query(sql, MAPPER.createObjectNode().put("tenantId", tenantId));
        return rows.isEmpty() ? 0L : rows.get(0).path("c").asLong();
    }

    /** ArcadeDB returns projected edge-traversal values as arrays; unwrap to the first. */
    private static String first(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isArray()) return n.isEmpty() ? null : n.get(0).asText();
        String s = n.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static List<JsonNode> query(String sql, ObjectNode params) throws Exception {
        ObjectNode body = MAPPER.createObjectNode().put("language", "sql").put("command", sql);
        if (params != null) body.set("params", params);
        byte[] payload = MAPPER.writeValueAsBytes(body);

        // HttpURLConnection, not java.net.http.HttpClient — the latter opens a
        // loopback selector pipe that is blocked in some sandboxed shells. Same
        // reason LoadEnterpriseProfile gives.
        HttpURLConnection conn = (HttpURLConnection) URI
                .create(baseUrl + "/api/v1/query/" + db).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(payload.length);
            conn.setConnectTimeout(15_000);
            conn.setReadTimeout(60_000);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Authorization", authHeader);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(payload);
            }
            int status = conn.getResponseCode();
            String responseBody = readAll(status / 100 == 2 ? conn.getInputStream() : conn.getErrorStream());
            if (status / 100 != 2) {
                throw new IOException("HTTP " + status + " on [" + sql + "] → " + responseBody);
            }
            List<JsonNode> rows = new ArrayList<>();
            MAPPER.readTree(responseBody).path("result").forEach(rows::add);
            return rows;
        } finally {
            conn.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream s = in) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    private static Map<String, String> readEnv() throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        Path env = Path.of("..", "..", ".env").normalize();
        if (!Files.exists(env)) return out;
        for (String line : Files.readAllLines(env, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) continue;
            out.put(t.substring(0, eq).strip(), t.substring(eq + 1).strip());
        }
        return out;
    }

    private VerifyPersonaAccess() {
    }
}
