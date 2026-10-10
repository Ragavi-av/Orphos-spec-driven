import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.access.PersonaGraph;
import io.orphos.eip.common.access.PersonaPolicy;
import io.orphos.eip.common.access.PersonaPolicy.Classification;
import io.orphos.eip.common.access.PersonaPolicy.Snapshot;

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
 * Prints the LLM context sections each persona would receive — running the REAL
 * {@link PersonaPolicy} from {@code eip-common} against the REAL tenant data, with
 * no mocks and no Spring.
 *
 * <p>Written because "the policy rows loaded" and "the co-pilot actually tells a
 * CTO less than a CFO" are different claims, and only the second one matters. The
 * five queries below are copied from {@code PersonaPolicyStore.load} and the
 * section list from {@code ArcadeDbContextBuilder.buildLiveContext}, so what this
 * prints is what nl-query-service will assemble for a caller holding that role.
 *
 * <p>Requires {@code libs/eip-common/target/classes} on the classpath (build it
 * with {@code mvnw -pl libs/eip-common -am package -DskipTests}).</p>
 *
 * <pre>
 *   java -cp "&lt;jackson&gt;;libs/eip-common/target/classes" ShowPersonaContext.java &lt;tenantId&gt; &lt;db&gt;
 * </pre>
 */
public final class ShowPersonaContext {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String baseUrl;
    private static String authHeader;
    private static String db;
    private static String tenantId;

    /**
     * The graph types {@code buildLiveContext} gates, in the order it emits them,
     * with the section name each one produces.
     */
    private static final List<String[]> SECTIONS = List.of(
            new String[]{"Incident", "Active Incidents"},
            new String[]{"Decision", "Recent Decisions"},
            new String[]{"SLAMetric", "SLA Metrics"},
            new String[]{"ITSystem", "IT Systems"},
            new String[]{"CostCentre", "Cost Centres"},
            new String[]{"Alert", "Active Alerts"},
            new String[]{"Employee", "Workforce (HR)"},
            new String[]{"CashFlowMonth", "Cash Flow (Finance)"},
            new String[]{"FinCashFlowEntry", "Cash Flow by Period"},
            new String[]{"FinBudgetLine", "Budget Lines"},
            new String[]{"HrHeadcountSnapshot", "Headcount Snapshots"},
            new String[]{"HrPayrollRun", "Payroll Cost (aggregate)"},
            new String[]{"RskRisk", "Risk Register"},
            new String[]{"StrObjective", "Strategic Objectives"},
            new String[]{"StrInitiative", "Initiatives"});

    private static final List<String> ROLES =
            List.of("ceo", "cfo", "cto", "chro", "coo", "executive", "admin", "viewer", "guest");

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java ShowPersonaContext.java <tenantId> <database>");
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

        Snapshot snapshot = loadSnapshot();
        System.out.printf("database %s, tenant %s%n", db, tenantId);
        System.out.printf("policy: %d personas, %d classified types, enforced=%b%n%n",
                snapshot.personaByRole().size(), snapshot.classificationByType().size(),
                PersonaPolicy.enforced(snapshot));

        // Which sections each persona's prompt would contain.
        int width = 26;
        StringBuilder header = new StringBuilder(pad("SECTION (graph type)", width));
        for (String r : ROLES) header.append(pad(r, 10));
        System.out.println(header);
        System.out.println("-".repeat(width + 10 * ROLES.size()));

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String[] section : SECTIONS) {
            StringBuilder row = new StringBuilder(pad(section[0], width));
            for (String role : ROLES) {
                boolean ok = PersonaPolicy.allows(snapshot, List.of(role), section[0]);
                if (ok) counts.merge(role, 1, Integer::sum);
                row.append(pad(ok ? "yes" : "-", 10));
            }
            System.out.println(row);
        }
        System.out.println("-".repeat(width + 10 * ROLES.size()));
        StringBuilder totals = new StringBuilder(pad("sections in prompt", width));
        for (String r : ROLES) totals.append(pad(String.valueOf(counts.getOrDefault(r, 0)), 10));
        System.out.println(totals);

        // The k-anonymity floor, as the context builder applies it.
        System.out.println("\nPayroll k-anonymity (as buildPayrollContext filters):");
        List<JsonNode> shown = query("SELECT name, coveredHeadcount, minimumPopulation FROM HrPayrollRun "
                + "WHERE tenantId = :tenantId AND coveredHeadcount >= minimumPopulation");
        List<JsonNode> withheld = query("SELECT name, coveredHeadcount, minimumPopulation FROM HrPayrollRun "
                + "WHERE tenantId = :tenantId AND coveredHeadcount < minimumPopulation");
        for (JsonNode r : shown) {
            System.out.printf("  shown    %-38s %s people (floor %s)%n",
                    r.path("name").asText(), r.path("coveredHeadcount").asText(),
                    r.path("minimumPopulation").asText());
        }
        for (JsonNode r : withheld) {
            System.out.printf("  WITHHELD %-38s %s people (floor %s)%n",
                    r.path("name").asText(), r.path("coveredHeadcount").asText(),
                    r.path("minimumPopulation").asText());
        }
        if (shown.isEmpty() && withheld.isEmpty()) {
            System.out.println("  (no HrPayrollRun rows in this database)");
        }

        // ── Dashboard: relevance vs permission, the step-4 split ──────────────
        PersonaGraph.WidgetBindings bindings = PersonaGraph.loadWidgetBindings(
                ShowPersonaContext::queryRows, tenantId);
        Snapshot widgetView = PersonaGraph.asWidgetSnapshot(snapshot, bindings);
        System.out.printf("%nDashboard (%d classified widgets)%n", bindings.widgetClassification().size());
        System.out.printf("  %-11s %-9s %-9s  %s%n", "persona", "default", "permitted", "default widgets");
        for (String role : ROLES) {
            Set<String> watched = bindings.watchedWidgetsByRole().getOrDefault(role, Set.of());
            List<String> permittedDefaults = new ArrayList<>();
            for (String w : watched) {
                if (PersonaPolicy.allows(widgetView, List.of(role), w)) permittedDefaults.add(w);
            }
            int permittedTotal = 0;
            for (String w : bindings.widgetClassification().keySet()) {
                if (PersonaPolicy.allows(widgetView, List.of(role), w)) permittedTotal++;
            }
            System.out.printf("  %-11s %-9d %-9d  %s%n", role, permittedDefaults.size(), permittedTotal,
                    permittedDefaults.isEmpty() ? "(falls through to system default)"
                            : String.join(", ", permittedDefaults));
            // A persona defaulting to a widget it may not see means the vocabulary
            // disagrees with itself — watchesMetric pointing outside canAccessDomain.
            if (permittedDefaults.size() != watched.size()) {
                System.out.printf("      WARNING: %d watched widget(s) are not permitted for this persona%n",
                        watched.size() - permittedDefaults.size());
            }
        }

        // Anything nobody may read must never reach the RAG corpus.
        System.out.println("\nExcluded from the RAG corpus (readable by no persona):");
        boolean any = false;
        for (String type : snapshot.classificationByType().keySet()) {
            if (PersonaPolicy.readableByNobody(snapshot, type)) {
                System.out.println("  " + type);
                any = true;
            }
        }
        if (!any) System.out.println("  (none — every classified type is readable by at least one persona)");
    }

    /** The five queries from PersonaPolicyStore.load, verbatim in intent. */
    private static Snapshot loadSnapshot() throws Exception {
        Map<String, Integer> ceilings = new LinkedHashMap<>();
        for (JsonNode r : query("SELECT roleKey, out('MAX_SENSITIVITY').sensitivityRank AS rank "
                + "FROM GovPersona WHERE tenantId = :tenantId")) {
            String role = text(r, "roleKey");
            if (role != null) ceilings.put(role, firstInt(r.path("rank")));
        }
        Map<String, Set<String>> inherits = multi(
                query("SELECT roleKey, out('INHERITS_FROM').roleKey AS parents "
                        + "FROM GovPersona WHERE tenantId = :tenantId"), "roleKey", "parents");
        Map<String, Set<String>> grants = multi(
                query("SELECT roleKey, out('CAN_ACCESS_DOMAIN').externalId AS domains "
                        + "FROM GovPersona WHERE tenantId = :tenantId"), "roleKey", "domains");
        Map<String, Set<String>> subDomains = multi(
                query("SELECT externalId, out('SUB_DOMAIN_OF').externalId AS parents "
                        + "FROM GovDataDomain WHERE tenantId = :tenantId"), "externalId", "parents");
        List<Classification> classifications = new ArrayList<>();
        for (JsonNode r : query("SELECT graphType, out('IN_DATA_DOMAIN').externalId AS dom, "
                + "out('HAS_SENSITIVITY').sensitivityRank AS rank "
                + "FROM GovClassification WHERE tenantId = :tenantId")) {
            String type = text(r, "graphType");
            String dom = firstStr(r.path("dom"));
            Integer rank = firstInt(r.path("rank"));
            if (type != null && dom != null && rank != null) {
                classifications.add(new Classification(type, dom, rank));
            }
        }
        return PersonaPolicy.buildSnapshot(ceilings, inherits, grants, subDomains, classifications);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Map<String, Set<String>> multi(List<JsonNode> rows, String keyField, String valueField) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (JsonNode r : rows) {
            String key = text(r, keyField);
            if (key == null) continue;
            Set<String> values = out.computeIfAbsent(key, k -> new LinkedHashSet<>());
            JsonNode v = r.path(valueField);
            if (v.isArray()) {
                for (JsonNode n : v) if (!n.isNull()) values.add(n.asText());
            } else if (!v.isMissingNode() && !v.isNull()) {
                values.add(v.asText());
            }
        }
        return out;
    }

    private static String pad(String s, int w) {
        if (s == null) s = "";
        return s.length() >= w ? s.substring(0, w - 1) + " " : s + " ".repeat(w - s.length());
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String firstStr(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        if (n.isArray()) return n.isEmpty() ? null : n.get(0).asText();
        return n.asText();
    }

    private static Integer firstInt(JsonNode n) {
        String s = firstStr(n);
        if (s == null) return null;
        try { return (int) Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    /**
     * Adapter to {@link PersonaGraph.GraphQuery} so this probe drives the exact
     * production loader. Params are ignored because every PersonaGraph query binds
     * only {@code tenantId}, which {@link #query} supplies. Jackson's convertValue
     * turns array projections into {@code List}, which is what PersonaGraph's row
     * helpers expect.
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> queryRows(String sql, Map<String, Object> params) {
        try {
            List<Map<String, Object>> out = new ArrayList<>();
            for (JsonNode r : query(sql)) out.add(MAPPER.convertValue(r, Map.class));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static List<JsonNode> query(String sql) throws Exception {
        var params = MAPPER.createObjectNode().put("tenantId", tenantId);
        var body = MAPPER.createObjectNode().put("language", "sql").put("command", sql);
        body.set("params", params);
        byte[] payload = MAPPER.writeValueAsBytes(body);

        // HttpURLConnection, not HttpClient — loopback selector pipes are blocked in
        // some sandboxed shells (same note as LoadEnterpriseProfile).
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
            if (status / 100 != 2) return List.of();   // missing type → empty, like the store
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

    private ShowPersonaContext() {}
}
