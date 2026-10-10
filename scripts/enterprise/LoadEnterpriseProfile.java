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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Loads one or more {@code *-enterprise-profile.json} files straight into
 * ArcadeDB over the HTTP API, for the case where okg-ingest-service (which
 * normally owns this via {@code PUT /api/okg-ingest/admin/enterprise}) is not
 * available.
 *
 * <p>Deliberately a faithful mirror of the service-side code, so a load done
 * here is indistinguishable from one done through the API:</p>
 * <ul>
 *   <li>schema DDL — {@code EnterpriseSchemaBootstrap} (Ent* vertex/edge types,
 *       properties, {@code (tenantId, externalId)} UNIQUE indexes);</li>
 *   <li>writes — {@code EnterpriseGraphRepository}: identity is
 *       {@code (tenantId, externalId)}, SELECT-then-UPDATE-or-INSERT, only
 *       non-null properties written, {@code id} minted as
 *       {@code ent-<type>-<uuid>}, {@code source=ADMIN};</li>
 *   <li>traversal order — {@code EnterpriseGraphService}: all vertices first,
 *       then all edges, with optional references skipped (and warned about)
 *       rather than failing the load.</li>
 * </ul>
 *
 * <p>Usage (JDK 21 single-file source launch, Jackson on the classpath):</p>
 * <pre>
 *   java -cp "&lt;jackson-core&gt;;&lt;jackson-databind&gt;;&lt;jackson-annotations&gt;" \
 *        LoadEnterpriseProfile.java &lt;tenantId&gt; &lt;database&gt; &lt;profile.json&gt;...
 * </pre>
 *
 * <p>Connection details come from the repo {@code .env} ({@code ARCADEDB_URL},
 * {@code ARCADEDB_USER}, {@code ARCADEDB_PASSWORD}), falling back to env vars of
 * the same name. The password is never printed.</p>
 */
public final class LoadEnterpriseProfile {

    /** Matches EnterpriseGraphService.SOURCE — marks rows as admin-submitted. */
    private static final String SOURCE = "ADMIN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String baseUrl;
    private static String authHeader;
    private static String db;
    private static String tenantId;

    private static int verticesWritten;
    private static int verticesInserted;
    private static int edgesWritten;
    private static final List<String> warnings = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("Usage: java LoadEnterpriseProfile.java <tenantId> <database> <profile.json>...");
            System.exit(2);
        }
        tenantId = args[0];
        db = args[1];
        List<Path> profiles = new ArrayList<>();
        for (int i = 2; i < args.length; i++) profiles.add(Path.of(args[i]));

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

        System.out.printf("ArcadeDB %s, database %s, tenant %s%n", baseUrl, db, tenantId);
        System.out.println("── Applying enterprise ontology schema ─────────────────────────");
        applySchema();
        System.out.println("Enterprise ontology schema ready.");

        for (Path profile : profiles) {
            System.out.printf("%n── Loading %s ──────────────────────────────────%n", profile.getFileName());
            int v0 = verticesWritten, e0 = edgesWritten, w0 = warnings.size();
            JsonNode root = MAPPER.readTree(Files.readString(profile, StandardCharsets.UTF_8));
            upsertProfile(root);
            System.out.printf("  company=%s → %d vertices, %d edges, %d warnings%n",
                    root.path("externalId").asText(), verticesWritten - v0, edgesWritten - e0,
                    warnings.size() - w0);
        }

        System.out.printf("%nTotal: %d vertices (%d new, %d updated), %d edges, %d warnings.%n",
                verticesWritten, verticesInserted, verticesWritten - verticesInserted,
                edgesWritten, warnings.size());
        for (String w : warnings) System.out.println("  [warn] " + w);

        System.out.println("\n── Counts for this tenant ──────────────────────────────────────");
        for (String type : List.of("EntCompany", "EntMission", "EntVision", "EntCoreValue",
                "EntFunctionalArea", "EntIndustry", "EntClient", "EntEngagement",
                "EntExecutive", "EntOrgUnit", "EntBranch", "EntLocation")) {
            System.out.printf("  %-18s %d%n", type, countOfType(type));
        }
    }

    // ── Profile → graph (mirrors EnterpriseGraphService.upsert) ────────────────

    private static void upsertProfile(JsonNode req) throws Exception {
        String companyId = text(req, "externalId");
        JsonNode co = req.path("company");

        // Pass 1: vertices.
        vertex("EntCompany", companyId, props(
                "name", co.path("name"), "legalName", co.path("legalName"), "tradeName", co.path("tradeName"),
                "foundedYear", co.path("foundedYear"), "registrationNumber", co.path("registrationNumber"),
                "website", co.path("website"), "companyType", co.path("companyType"),
                "stockSymbol", co.path("stockSymbol"), "employeeCount", co.path("employeeCount"),
                "annualRevenue", co.path("annualRevenue"), "currency", co.path("currency"),
                "description", co.path("description")));

        String missionId = null, visionId = null;
        if (notBlank(text(req, "mission"))) {
            missionId = companyId + "::mission";
            vertex("EntMission", missionId, strings("name", "Mission", "statement", text(req, "mission")));
        }
        if (notBlank(text(req, "vision"))) {
            visionId = companyId + "::vision";
            vertex("EntVision", visionId, strings("name", "Vision", "statement", text(req, "vision")));
        }

        Set<String> values = new LinkedHashSet<>();
        for (JsonNode v : arr(req, "values")) {
            String id = notBlank(text(v, "externalId"))
                    ? text(v, "externalId")
                    : companyId + "::value::" + slug(text(v, "name"));
            vertex("EntCoreValue", id, props("name", v.path("name"), "statement", v.path("statement")));
            values.add(id);
        }
        Set<String> areas = new LinkedHashSet<>();
        for (JsonNode a : arr(req, "functionalAreas")) {
            vertex("EntFunctionalArea", text(a, "externalId"),
                    props("name", a.path("name"), "description", a.path("description")));
            areas.add(text(a, "externalId"));
        }
        Set<String> industries = new LinkedHashSet<>();
        for (JsonNode i : arr(req, "industries")) {
            vertex("EntIndustry", text(i, "externalId"), props(
                    "name", i.path("name"), "sectorCode", i.path("sectorCode"),
                    "description", i.path("description")));
            industries.add(text(i, "externalId"));
        }
        Set<String> locations = new LinkedHashSet<>();
        for (JsonNode l : arr(req, "locations")) {
            vertex("EntLocation", text(l, "externalId"), props(
                    "name", l.path("name"), "addressLine", l.path("addressLine"), "city", l.path("city"),
                    "stateProvince", l.path("stateProvince"), "country", l.path("country"),
                    "postalCode", l.path("postalCode"), "latitude", l.path("latitude"),
                    "longitude", l.path("longitude"), "timezone", l.path("timezone")));
            locations.add(text(l, "externalId"));
        }
        for (JsonNode b : arr(req, "branches")) {
            vertex("EntBranch", text(b, "externalId"), props(
                    "name", b.path("name"), "branchType", b.path("branchType"),
                    "openedDate", b.path("openedDate"), "description", b.path("description")));
        }
        Set<String> orgUnits = new LinkedHashSet<>();
        for (JsonNode u : arr(req, "orgUnits")) {
            vertex("EntOrgUnit", text(u, "externalId"), props(
                    "name", u.path("name"), "unitType", u.path("unitType"),
                    "unitCode", u.path("unitCode"), "description", u.path("description")));
            orgUnits.add(text(u, "externalId"));
        }
        Set<String> executives = new LinkedHashSet<>();
        for (JsonNode e : arr(req, "executives")) {
            vertex("EntExecutive", text(e, "externalId"), props(
                    "displayName", e.path("displayName"), "jobTitle", e.path("jobTitle"),
                    "appointedDate", e.path("appointedDate")));
            executives.add(text(e, "externalId"));
        }
        Set<String> clients = new LinkedHashSet<>();
        for (JsonNode cl : arr(req, "clients")) {
            vertex("EntClient", text(cl, "externalId"), props(
                    "name", cl.path("name"), "clientTier", cl.path("clientTier"),
                    "clientSince", cl.path("clientSince"),
                    "relationshipStatus", cl.path("relationshipStatus"),
                    "description", cl.path("description")));
            clients.add(text(cl, "externalId"));
        }
        for (JsonNode en : arr(req, "engagements")) {
            vertex("EntEngagement", text(en, "externalId"), props(
                    "name", en.path("name"), "startDate", en.path("startDate"), "endDate", en.path("endDate"),
                    "engagementStatus", en.path("engagementStatus"), "contractValue", en.path("contractValue"),
                    "currency", en.path("currency"), "description", en.path("description")));
        }

        // Pass 2: edges.
        if (missionId != null) edge("HAS_MISSION", "EntCompany", companyId, "EntMission", missionId);
        if (visionId != null) edge("HAS_VISION", "EntCompany", companyId, "EntVision", visionId);
        for (String vid : values) edge("UPHOLDS_VALUE", "EntCompany", companyId, "EntCoreValue", vid);
        for (String aid : areas) edge("OPERATES_IN_AREA", "EntCompany", companyId, "EntFunctionalArea", aid);
        for (String iid : industries) edge("SERVES_INDUSTRY", "EntCompany", companyId, "EntIndustry", iid);

        String hq = text(co, "headquartersLocationExternalId");
        if (notBlank(hq)) {
            refEdge("HEADQUARTERED_AT", "EntCompany", companyId, "EntLocation", hq, locations,
                    "company.headquartersLocationExternalId");
        }
        for (JsonNode b : arr(req, "branches")) {
            String id = text(b, "externalId");
            edge("HAS_BRANCH", "EntCompany", companyId, "EntBranch", id);
            refEdge("LOCATED_AT", "EntBranch", id, "EntLocation", text(b, "locationExternalId"),
                    locations, "branch " + id + " locationExternalId");
        }
        for (JsonNode u : arr(req, "orgUnits")) {
            String id = text(u, "externalId");
            edge("HAS_ORG_UNIT", "EntCompany", companyId, "EntOrgUnit", id);
            refEdge("SUB_UNIT_OF", "EntOrgUnit", id, "EntOrgUnit", text(u, "parentExternalId"),
                    orgUnits, "orgUnit " + id + " parentExternalId");
            refEdge("HEADED_BY", "EntOrgUnit", id, "EntExecutive", text(u, "headExecutiveExternalId"),
                    executives, "orgUnit " + id + " headExecutiveExternalId");
            for (JsonNode areaId : u.path("responsibleForAreaExternalIds")) {
                refEdge("RESPONSIBLE_FOR", "EntOrgUnit", id, "EntFunctionalArea", areaId.asText(),
                        areas, "orgUnit " + id + " responsibleForAreaExternalIds");
            }
        }
        for (JsonNode e : arr(req, "executives")) {
            String id = text(e, "externalId");
            if (e.path("companyLeader").asBoolean(false)) {
                edge("LED_BY", "EntCompany", companyId, "EntExecutive", id);
            }
            refEdge("MEMBER_OF", "EntExecutive", id, "EntOrgUnit", text(e, "memberOfOrgUnitExternalId"),
                    orgUnits, "executive " + id + " memberOfOrgUnitExternalId");
            refEdge("REPORTS_TO", "EntExecutive", id, "EntExecutive", text(e, "reportsToExternalId"),
                    executives, "executive " + id + " reportsToExternalId");
        }
        for (JsonNode cl : arr(req, "clients")) {
            String id = text(cl, "externalId");
            edge("HAS_CLIENT", "EntCompany", companyId, "EntClient", id);
            refEdge("LOCATED_AT", "EntClient", id, "EntLocation", text(cl, "locationExternalId"),
                    locations, "client " + id + " locationExternalId");
            for (JsonNode indId : cl.path("industryExternalIds")) {
                refEdge("CLIENT_IN_INDUSTRY", "EntClient", id, "EntIndustry", indId.asText(),
                        industries, "client " + id + " industryExternalIds");
            }
        }
        for (JsonNode en : arr(req, "engagements")) {
            String id = text(en, "externalId");
            edge("HAS_ENGAGEMENT", "EntCompany", companyId, "EntEngagement", id);
            refEdge("ENGAGEMENT_WITH", "EntEngagement", id, "EntClient", text(en, "clientExternalId"),
                    clients, "engagement " + id + " clientExternalId");
            refEdge("ENGAGEMENT_IN_INDUSTRY", "EntEngagement", id, "EntIndustry",
                    text(en, "industryExternalId"), industries,
                    "engagement " + id + " industryExternalId");
            refEdge("DELIVERED_BY", "EntEngagement", id, "EntOrgUnit",
                    text(en, "deliveredByOrgUnitExternalId"), orgUnits,
                    "engagement " + id + " deliveredByOrgUnitExternalId");
        }
    }

    // ── Writes (mirrors EnterpriseGraphRepository) ─────────────────────────────

    /** Upserts one Ent* vertex by (tenantId, externalId); only non-null props are written. */
    private static void vertex(String type, String externalId, Map<String, JsonNode> props) throws Exception {
        if (!notBlank(externalId)) throw new IllegalArgumentException(type + " requires a non-blank externalId");
        String now = Instant.now().toString();

        ObjectNode key = MAPPER.createObjectNode().put("tenantId", tenantId).put("externalId", externalId);
        boolean exists = !query("SELECT FROM " + type
                + " WHERE tenantId = :tenantId AND externalId = :externalId LIMIT 1", key).isEmpty();

        ObjectNode params = MAPPER.createObjectNode();
        List<String> assignable = new ArrayList<>();
        props.forEach((k, v) -> {
            if (v != null && !v.isNull() && !v.isMissingNode()) {
                params.set(k, v);
                assignable.add(k);
            }
        });
        params.put("tenantId", tenantId).put("externalId", externalId);

        if (exists) {
            params.put("updatedAt", now);
            assignable.add("updatedAt");
            command("UPDATE " + type + " SET " + setClause(assignable)
                    + " WHERE tenantId = :tenantId AND externalId = :externalId", params);
        } else {
            params.put("id", idPrefix(type) + "-" + UUID.randomUUID())
                    .put("source", SOURCE).put("createdAt", now).put("updatedAt", now);
            assignable.addAll(List.of("tenantId", "externalId", "id", "source", "createdAt", "updatedAt"));
            command("INSERT INTO " + type + " SET " + setClause(assignable), params);
            verticesInserted++;
        }
        verticesWritten++;
    }

    /**
     * Creates an edge between two upserted vertices, idempotently.
     *
     * <p>The "already linked?" probe is anchored on the source <em>vertex</em>
     * and traverses out, rather than filtering the edge type by
     * {@code out.externalId} the way {@code EnterpriseGraphRepository.linkOnce}
     * does: on ArcadeDB 26.7.2 an {@code out.<property>} predicate in a WHERE
     * clause over an edge type matches nothing at all (verified with literals),
     * so that check silently never fires and every re-run duplicates the edge.
     * Scoping by tenant on the source vertex is sufficient — this loader only
     * ever links vertices within one tenant.</p>
     */
    private static void edge(String edgeType, String fromType, String fromId,
                             String toType, String toId) throws Exception {
        if (fromId == null || toId == null) return;
        ObjectNode p = MAPPER.createObjectNode()
                .put("tenantId", tenantId).put("from", fromId).put("to", toId);

        List<JsonNode> linkRows = query("SELECT count(*) AS c FROM " + fromType
                + " WHERE tenantId = :tenantId AND externalId = :from"
                + " AND out('" + edgeType + "').externalId CONTAINS :to", p);
        if (!linkRows.isEmpty() && linkRows.get(0).path("c").asLong() > 0) return;

        boolean endpointsExist = !query("SELECT FROM " + fromType
                + " WHERE tenantId = :tenantId AND externalId = :from LIMIT 1", p).isEmpty()
                && !query("SELECT FROM " + toType
                + " WHERE tenantId = :tenantId AND externalId = :to LIMIT 1", p).isEmpty();
        if (!endpointsExist) return;

        command("CREATE EDGE " + edgeType
                + " FROM (SELECT FROM " + fromType + " WHERE tenantId = :tenantId AND externalId = :from) "
                + "TO (SELECT FROM " + toType + " WHERE tenantId = :tenantId AND externalId = :to)", p);
        edgesWritten++;
    }

    /** Edge whose target is optional: null → skip; unknown → skip with a warning. */
    private static void refEdge(String edgeType, String fromType, String fromId, String toType,
                                String toId, Set<String> known, String fieldDesc) throws Exception {
        if (!notBlank(toId)) return;
        if (!known.contains(toId)) {
            warnings.add("Skipped " + edgeType + ": " + fieldDesc
                    + " references unknown id '" + toId + "'");
            return;
        }
        edge(edgeType, fromType, fromId, toType, toId);
    }

    private static long countOfType(String type) throws Exception {
        List<JsonNode> rows = query("SELECT count(*) AS c FROM " + type + " WHERE tenantId = :tenantId",
                MAPPER.createObjectNode().put("tenantId", tenantId));
        return rows.isEmpty() ? 0L : rows.get(0).path("c").asLong();
    }

    // ── Schema DDL (mirrors EnterpriseSchemaBootstrap) ─────────────────────────

    /** Every Ent* vertex type carrying the shared identity/label triple. */
    private static final List<String> BASE_TYPES = List.of(
            "EntCompany", "EntMission", "EntVision", "EntCoreValue",
            "EntFunctionalArea", "EntIndustry", "EntClient", "EntEngagement",
            "EntPerson", "EntOrgUnit", "EntBranch", "EntLocation");

    private static void applySchema() throws Exception {
        for (String type : BASE_TYPES) command("CREATE VERTEX TYPE " + type + " IF NOT EXISTS", null);
        // EntExecutive EXTENDS EntPerson — mirrors ent:Executive rdfs:subClassOf ent:Person.
        command("CREATE VERTEX TYPE EntExecutive IF NOT EXISTS EXTENDS EntPerson", null);

        for (String type : List.of(
                "HAS_MISSION", "HAS_VISION", "UPHOLDS_VALUE", "OPERATES_IN_AREA",
                "SERVES_INDUSTRY", "HAS_CLIENT", "HAS_ENGAGEMENT", "HAS_ORG_UNIT",
                "HAS_BRANCH", "HEADQUARTERED_AT", "LED_BY", "SUB_UNIT_OF",
                "HEADED_BY", "RESPONSIBLE_FOR", "MEMBER_OF", "REPORTS_TO",
                "LOCATED_AT", "ENGAGEMENT_WITH", "ENGAGEMENT_IN_INDUSTRY",
                "DELIVERED_BY", "CLIENT_IN_INDUSTRY")) {
            command("CREATE EDGE TYPE " + type + " IF NOT EXISTS", null);
        }

        // CREATE PROPERTY has no IF NOT EXISTS in ArcadeDB — tolerate "already exists".
        List<String> withExecutive = new ArrayList<>(BASE_TYPES);
        withExecutive.add("EntExecutive");
        for (String type : withExecutive) {
            for (String p : List.of("id", "tenantId", "externalId", "source", "createdAt", "updatedAt")) {
                tryExec("CREATE PROPERTY " + type + "." + p + " STRING");
            }
        }
        for (String type : List.of("EntCompany", "EntCoreValue", "EntFunctionalArea", "EntIndustry",
                "EntClient", "EntEngagement", "EntOrgUnit", "EntBranch", "EntLocation")) {
            tryExec("CREATE PROPERTY " + type + ".name STRING");
            tryExec("CREATE PROPERTY " + type + ".description STRING");
        }
        for (String p : List.of(
                "CREATE PROPERTY EntCompany.legalName STRING",
                "CREATE PROPERTY EntCompany.tradeName STRING",
                "CREATE PROPERTY EntCompany.foundedYear INTEGER",
                "CREATE PROPERTY EntCompany.registrationNumber STRING",
                "CREATE PROPERTY EntCompany.website STRING",
                "CREATE PROPERTY EntCompany.companyType STRING",
                "CREATE PROPERTY EntCompany.stockSymbol STRING",
                "CREATE PROPERTY EntCompany.employeeCount INTEGER",
                "CREATE PROPERTY EntCompany.annualRevenue DOUBLE",
                "CREATE PROPERTY EntCompany.currency STRING",
                "CREATE PROPERTY EntIndustry.sectorCode STRING",
                "CREATE PROPERTY EntClient.clientSince STRING",
                "CREATE PROPERTY EntClient.clientTier STRING",
                "CREATE PROPERTY EntClient.relationshipStatus STRING",
                "CREATE PROPERTY EntEngagement.startDate STRING",
                "CREATE PROPERTY EntEngagement.endDate STRING",
                "CREATE PROPERTY EntEngagement.engagementStatus STRING",
                "CREATE PROPERTY EntEngagement.contractValue DOUBLE",
                "CREATE PROPERTY EntEngagement.currency STRING",
                "CREATE PROPERTY EntPerson.displayName STRING",
                "CREATE PROPERTY EntPerson.jobTitle STRING",
                "CREATE PROPERTY EntExecutive.appointedDate STRING",
                "CREATE PROPERTY EntOrgUnit.unitType STRING",
                "CREATE PROPERTY EntOrgUnit.unitCode STRING",
                "CREATE PROPERTY EntBranch.branchType STRING",
                "CREATE PROPERTY EntBranch.openedDate STRING",
                "CREATE PROPERTY EntLocation.addressLine STRING",
                "CREATE PROPERTY EntLocation.city STRING",
                "CREATE PROPERTY EntLocation.stateProvince STRING",
                "CREATE PROPERTY EntLocation.country STRING",
                "CREATE PROPERTY EntLocation.postalCode STRING",
                "CREATE PROPERTY EntLocation.latitude DOUBLE",
                "CREATE PROPERTY EntLocation.longitude DOUBLE",
                "CREATE PROPERTY EntLocation.timezone STRING")) {
            tryExec(p);
        }
        for (String type : List.of("EntMission", "EntVision", "EntCoreValue")) {
            tryExec("CREATE PROPERTY " + type + ".statement STRING");
        }

        // EntExecutive is excluded: an index on the parent covers subtype records.
        for (String type : BASE_TYPES) {
            command("CREATE INDEX IF NOT EXISTS ON " + type + " (id) UNIQUE", null);
            command("CREATE INDEX IF NOT EXISTS ON " + type + " (tenantId) NOTUNIQUE", null);
            command("CREATE INDEX IF NOT EXISTS ON " + type + " (tenantId, externalId) UNIQUE", null);
        }
    }

    private static void tryExec(String sql) {
        try {
            command(sql, null);
        } catch (Exception e) {
            // Property already exists — the bootstrap tolerates this too.
        }
    }

    // ── ArcadeDB HTTP plumbing ────────────────────────────────────────────────

    /**
     * POSTs one statement to {@code /api/v1/<endpoint>/<db>} and returns the
     * parsed response. Uses {@link HttpURLConnection} rather than
     * {@code java.net.http.HttpClient} on purpose: the latter opens an internal
     * loopback selector pipe on startup, which is blocked in some sandboxed
     * shells ("Unable to establish loopback connection") even when the ArcadeDB
     * host itself is perfectly reachable.
     */
    private static JsonNode post(String endpoint, String sql, ObjectNode params) throws Exception {
        ObjectNode body = MAPPER.createObjectNode().put("language", "sql").put("command", sql);
        if (params != null) body.set("params", params);
        byte[] payload = MAPPER.writeValueAsBytes(body);

        HttpURLConnection conn = (HttpURLConnection) URI
                .create(baseUrl + "/api/v1/" + endpoint + "/" + db).toURL().openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            // Fixed length, not chunked: ArcadeDB's HTTP server stalls on a
            // chunked request body, which surfaces as a read timeout.
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
            return MAPPER.readTree(responseBody);
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

    private static List<JsonNode> query(String sql, ObjectNode params) throws Exception {
        List<JsonNode> rows = new ArrayList<>();
        post("query", sql, params).path("result").forEach(rows::add);
        return rows;
    }

    private static void command(String sql, ObjectNode params) throws Exception {
        post("command", sql, params);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Map<String, JsonNode> props(Object... kv) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), (JsonNode) kv[i + 1]);
        return m;
    }

    private static Map<String, JsonNode> strings(String... kv) {
        Map<String, JsonNode> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], MAPPER.getNodeFactory().textNode(kv[i + 1]));
        return m;
    }

    private static Iterable<JsonNode> arr(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isArray() ? n : List.of();
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.path(field);
        return n.isMissingNode() || n.isNull() ? null : n.asText();
    }

    private static String setClause(List<String> keys) {
        List<String> parts = new ArrayList<>();
        for (String k : new LinkedHashSet<>(keys)) parts.add(k + " = :" + k);
        return String.join(", ", parts);
    }

    /** EntCompany → "ent-company", matching EnterpriseGraphRepository.idPrefix. */
    private static String idPrefix(String type) {
        String base = type.startsWith("Ent") ? type.substring(3) : type;
        return "ent-" + base.toLowerCase();
    }

    private static String slug(String s) {
        return s == null ? "" : s.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return null;
    }

    /** Reads ARCADEDB_* settings from the repo .env (two levels up), if present. */
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

    private LoadEnterpriseProfile() {
    }
}
