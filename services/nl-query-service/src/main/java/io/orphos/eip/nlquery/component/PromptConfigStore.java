package io.orphos.eip.nlquery.component;

import io.orphos.eip.nlquery.component.PromptDefaults.PromptDef;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tenant-scoped, versioned store for the LLM prompts and per-provider tuning
 * used by nl-query-service. One {@code PromptConfig} document per
 * {@code (tenantId, promptKey, version)}; exactly one version per key is
 * {@code active} and is the one used at runtime.
 *
 * <p>Source of truth is ArcadeDB. {@link PromptDefaults} seeds it on first
 * access for a tenant and is the fallback when the DB is empty or unreachable —
 * so the read path never breaks. Active rows are cached per tenant for a short
 * TTL and the cache is invalidated on every write, so admin edits go live
 * within seconds without a restart.</p>
 *
 * <p>Self-contained ArcadeDB HTTP plumbing mirrors {@code TenantProviderMetaStore}.</p>
 */
@Component
public class PromptConfigStore {

    private static final Logger log = LogManager.getLogger(PromptConfigStore.class);
    private static final String DB = "orphos";
    private static final String TYPE = "PromptConfig";
    private static final long CACHE_TTL_NANOS = 30_000_000_000L; // 30s

    // NOTE on reserved words: ArcadeDB SQL reserves several tokens that appear
    // both as COLUMN identifiers and as :PARAMETER names, and back-ticks don't
    // reliably escape them. We therefore avoid them entirely:
    //   columns: content→promptText, orderIndex→ordinal, version→revision,
    //            active→activeFlag   (orderIndex lexes as the ORDER keyword)
    //   params : :desc (DESC sort keyword) → :descr
    // The Java PromptRow field names are unchanged and mapped in toRow().
    private static final String INSERT_SQL =
            "INSERT INTO " + TYPE + " SET id = :id, tenantId = :t, kind = :kind, promptKey = :k, "
          + "name = :name, promptText = :pt, description = :descr, ordinal = :oi, "
          + "revision = :v, activeFlag = :a, createdAt = :ca, updatedAt = :ua, createdBy = :cb";

    private final RestTemplate rest;
    private final PromptDefaults defaults;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final java.util.Set<String> seededTenants = ConcurrentHashMap.newKeySet();

    @Value("${arcadedb.url:http://eip-arcadedb:2480}")
    private String arcadeUrl;
    @Value("${arcadedb.user:root}")
    private String arcadeUser;
    @Value("${arcadedb.password:}")
    private String arcadePassword;

    public PromptConfigStore(PromptDefaults defaults, RestTemplate rest) {
        this.defaults = defaults;
        this.rest = rest;
    }

    /** One stored prompt version. */
    public record PromptRow(String id, String tenantId, String kind, String promptKey,
                            String name, String content, String description,
                            int orderIndex, int version, boolean active,
                            String createdAt, String updatedAt, String createdBy) {}

    private record CacheEntry(long expiresNanos, Map<String, PromptRow> activeByKey) {}

    @PostConstruct
    public void ensureSchema() {
        try {
            command("CREATE DOCUMENT TYPE " + TYPE + " IF NOT EXISTS");
            command("CREATE PROPERTY " + TYPE + ".id          IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".tenantId    IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".kind        IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".promptKey   IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".name        IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".promptText  IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".description IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".ordinal     IF NOT EXISTS INTEGER");
            command("CREATE PROPERTY " + TYPE + ".revision    IF NOT EXISTS INTEGER");
            command("CREATE PROPERTY " + TYPE + ".activeFlag  IF NOT EXISTS BOOLEAN");
            command("CREATE PROPERTY " + TYPE + ".createdAt   IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".updatedAt   IF NOT EXISTS STRING");
            command("CREATE PROPERTY " + TYPE + ".createdBy   IF NOT EXISTS STRING");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId, promptKey, revision) UNIQUE");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId, kind) NOTUNIQUE");
            command("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId, activeFlag) NOTUNIQUE");
            log.info("PromptConfig schema ready on ArcadeDB");
        } catch (Exception e) {
            log.warn("Could not initialise PromptConfig schema (will retry per-call): {}", e.getMessage());
        }
    }

    // ── Runtime read API ───────────────────────────────────────────────────

    /** Active content for a key, falling back to the bundled default. */
    public String text(UUID tenant, String promptKey) {
        PromptRow r = activeRows(tenant).get(promptKey);
        if (r != null && r.content() != null) return r.content();
        return defaults.content(promptKey);
    }

    /** Active fragments of a kind, ordered; falls back to the bundled defaults of that kind. */
    public List<PromptRow> orderedByKind(UUID tenant, String kind) {
        List<PromptRow> rows = activeRows(tenant).values().stream()
                .filter(r -> kind.equals(r.kind()))
                .sorted(Comparator.comparingInt(PromptRow::orderIndex).thenComparing(PromptRow::promptKey))
                .toList();
        if (!rows.isEmpty()) return rows;
        return defaultRowsOfKind(kind);
    }

    /** Ordered fallback follow-up questions (kind=fallback, key fallback.followup.*). */
    public List<String> followups(UUID tenant) {
        List<String> qs = activeRows(tenant).values().stream()
                .filter(r -> r.promptKey() != null && r.promptKey().startsWith("fallback.followup."))
                .sorted(Comparator.comparingInt(PromptRow::orderIndex).thenComparing(PromptRow::promptKey))
                .map(PromptRow::content)
                .filter(s -> s != null && !s.isBlank())
                .toList();
        if (!qs.isEmpty()) return qs;
        return defaults.all().stream()
                .filter(d -> d.promptKey().startsWith("fallback.followup."))
                .sorted(Comparator.comparingInt(PromptDef::orderIndex))
                .map(PromptDef::content).toList();
    }

    public double tuningDouble(UUID tenant, String providerId, String param, double def) {
        String v = tuningRaw(tenant, providerId, param);
        try { return v == null || v.isBlank() ? def : Double.parseDouble(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    public int tuningInt(UUID tenant, String providerId, String param, int def) {
        String v = tuningRaw(tenant, providerId, param);
        try { return v == null || v.isBlank() ? def : Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { return def; }
    }

    private String tuningRaw(UUID tenant, String providerId, String param) {
        return text(tenant, "tuning." + providerId + "." + param);
    }

    // ── Admin API ────────────────────────────────────────────────────────────

    /** All active rows for a tenant, ordered by kind then orderIndex. */
    public List<PromptRow> listActive(UUID tenant) {
        return activeRows(tenant).values().stream()
                .sorted(Comparator.comparing(PromptRow::kind)
                        .thenComparingInt(PromptRow::orderIndex)
                        .thenComparing(PromptRow::promptKey))
                .toList();
    }

    /** All versions of one key, newest first. */
    public List<PromptRow> history(UUID tenant, String promptKey) {
        try {
            List<Map<String, Object>> raw = queryWithParams(
                "SELECT FROM " + TYPE + " WHERE tenantId = :t AND promptKey = :k ORDER BY revision DESC",
                Map.of("t", tenant.toString(), "k", promptKey));
            List<PromptRow> out = new ArrayList<>();
            for (Map<String, Object> r : raw) out.add(toRow(r));
            return out;
        } catch (Exception e) {
            log.warn("Prompt history read failed for {}/{}: {}", tenant, promptKey, e.getMessage());
            return List.of();
        }
    }

    /** Create a new active version of a key (used for both add and edit). */
    public PromptRow saveNewVersion(UUID tenant, String kind, String promptKey, String name,
                                    String content, String description, int orderIndex, String createdBy) {
        String t = tenant.toString();
        int next = maxVersion(t, promptKey) + 1;
        clearActive(t, promptKey);
        String now = Instant.now().toString();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", UUID.randomUUID().toString());
        p.put("t", t);
        p.put("kind", kind);
        p.put("k", promptKey);
        p.put("name", name == null ? "" : name);
        p.put("pt", content == null ? "" : content);
        p.put("descr", description == null ? "" : description);
        p.put("oi", orderIndex);
        p.put("v", next);
        p.put("a", true);
        p.put("ca", now);
        p.put("ua", now);
        p.put("cb", createdBy == null ? "admin" : createdBy);
        commandWithParams(INSERT_SQL, p);
        invalidate(t);
        return new PromptRow((String) p.get("id"), t, kind, promptKey, (String) p.get("name"),
                (String) p.get("pt"), (String) p.get("descr"), orderIndex, next, true, now, now,
                (String) p.get("cb"));
    }

    /** Roll back / forward to an existing version of a key. */
    public void activateVersion(UUID tenant, String promptKey, int version) {
        String t = tenant.toString();
        clearActive(t, promptKey);
        commandWithParams(
            "UPDATE " + TYPE + " SET activeFlag = true, updatedAt = :ua "
          + "WHERE tenantId = :t AND promptKey = :k AND revision = :v",
            Map.of("t", t, "k", promptKey, "v", version, "ua", Instant.now().toString()));
        invalidate(t);
    }

    /** Permanently remove all versions of a key for a tenant. */
    public void deleteKey(UUID tenant, String promptKey) {
        String t = tenant.toString();
        commandWithParams("DELETE FROM " + TYPE + " WHERE tenantId = :t AND promptKey = :k",
                Map.of("t", t, "k", promptKey));
        invalidate(t);
    }

    /** Delete a key and re-seed it from the bundled default (version 1 active). */
    public PromptRow resetToDefault(UUID tenant, String promptKey, String createdBy) {
        deleteKey(tenant, promptKey);
        PromptDef d = defaults.get(promptKey).orElse(null);
        if (d == null) return null;
        return saveNewVersion(tenant, d.kind(), d.promptKey(), d.name(),
                d.content(), d.description(), d.orderIndex(), createdBy);
    }

    /** True when the active row for a key exists in the bundled defaults. */
    public boolean isDefaultKey(String promptKey) {
        return defaults.get(promptKey).isPresent();
    }

    /**
     * Restore every bundled-default prompt (optionally limited to one kind) to
     * its default content as a new active version. Custom (non-default) keys are
     * left untouched. Returns the number of prompts restored.
     */
    public int resetAll(UUID tenant, String kindOrNull, String editor) {
        int n = 0;
        for (PromptDef d : defaults.all()) {
            if (kindOrNull != null && !kindOrNull.equals(d.kind())) continue;
            try {
                resetToDefault(tenant, d.promptKey(), editor);
                n++;
            } catch (Exception e) {
                log.warn("Bulk reset failed for {} on tenant {}: {}", d.promptKey(), tenant, e.getMessage());
            }
        }
        invalidate(tenant.toString());
        return n;
    }

    // ── Internal: active-row loading + cache + seeding ───────────────────────

    private Map<String, PromptRow> activeRows(UUID tenant) {
        if (tenant == null) return defaultRows();
        String t = tenant.toString();
        long now = System.nanoTime();
        CacheEntry e = cache.get(t);
        if (e != null && e.expiresNanos() > now) return e.activeByKey();
        Map<String, PromptRow> rows = loadActive(t);
        cache.put(t, new CacheEntry(now + CACHE_TTL_NANOS, rows));
        return rows;
    }

    private Map<String, PromptRow> loadActive(String tenantId) {
        try {
            List<Map<String, Object>> raw = queryWithParams(
                "SELECT FROM " + TYPE + " WHERE tenantId = :t AND activeFlag = true",
                Map.of("t", tenantId));
            if (raw.isEmpty() && seededTenants.add(tenantId)) {
                seedDefaults(tenantId);
                raw = queryWithParams(
                    "SELECT FROM " + TYPE + " WHERE tenantId = :t AND active = true",
                    Map.of("t", tenantId));
            }
            if (raw.isEmpty()) return defaultRows();
            Map<String, PromptRow> map = new LinkedHashMap<>();
            for (Map<String, Object> r : raw) {
                PromptRow row = toRow(r);
                if (row.promptKey() != null) map.put(row.promptKey(), row);
            }
            seedMissingKinds(tenantId, map);
            return map;
        } catch (Exception e) {
            log.warn("PromptConfig read failed for tenant {} — using bundled defaults: {}",
                    tenantId, e.getMessage());
            return defaultRows();
        }
    }

    private void seedDefaults(String tenantId) {
        String now = Instant.now().toString();
        int seeded = 0;
        for (PromptDef d : defaults.all()) {
            if (insertDefault(tenantId, d, now)) seeded++;
        }
        log.info("Seeded {} default prompts for tenant {}", seeded, tenantId);
    }

    /**
     * Backfills bundled defaults of any kind that has NO rows at all for this
     * tenant — i.e. prompt kinds added in an upgrade (email-classification,
     * email-drafting, doc-processing) for tenants seeded before the kind
     * existed. Kinds with at least one row are never touched, so individually
     * deleted prompts of an existing kind stay deleted.
     */
    private void seedMissingKinds(String tenantId, Map<String, PromptRow> active) {
        java.util.Set<String> presentKinds = new java.util.HashSet<>();
        for (PromptRow r : active.values()) presentKinds.add(r.kind());
        List<PromptDef> missing = defaults.all().stream()
                .filter(d -> !presentKinds.contains(d.kind()))
                .toList();
        if (missing.isEmpty()) return;
        String now = Instant.now().toString();
        int seeded = 0;
        for (PromptDef d : missing) {
            if (insertDefault(tenantId, d, now)) {
                active.put(d.promptKey(), new PromptRow(
                        "seeded:" + d.promptKey(), tenantId, d.kind(), d.promptKey(), d.name(),
                        d.content(), d.description(), d.orderIndex(), 1, true, now, now, "system"));
                seeded++;
            }
        }
        if (seeded > 0) {
            log.info("Backfilled {} default prompts of new kinds for tenant {}", seeded, tenantId);
        }
    }

    /** One default row as version 1, active. False on failure (e.g. a UNIQUE clash from a concurrent seed). */
    private boolean insertDefault(String tenantId, PromptDef d, String now) {
        try {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("id", UUID.randomUUID().toString());
            p.put("t", tenantId);
            p.put("kind", d.kind());
            p.put("k", d.promptKey());
            p.put("name", d.name() == null ? "" : d.name());
            p.put("pt", d.content() == null ? "" : d.content());
            p.put("descr", d.description() == null ? "" : d.description());
            p.put("oi", d.orderIndex());
            p.put("v", 1);
            p.put("a", true);
            p.put("ca", now);
            p.put("ua", now);
            p.put("cb", "system");
            commandWithParams(INSERT_SQL, p);
            return true;
        } catch (Exception ex) {
            log.debug("Seed insert skipped for {} on tenant {}: {}", d.promptKey(), tenantId, ex.getMessage());
            return false;
        }
    }

    private Map<String, PromptRow> defaultRows() {
        String now = Instant.now().toString();
        Map<String, PromptRow> map = new LinkedHashMap<>();
        for (PromptDef d : defaults.all()) {
            map.put(d.promptKey(), new PromptRow(
                    "default:" + d.promptKey(), "", d.kind(), d.promptKey(), d.name(),
                    d.content(), d.description(), d.orderIndex(), 1, true, now, now, "system"));
        }
        return map;
    }

    private List<PromptRow> defaultRowsOfKind(String kind) {
        return defaultRows().values().stream()
                .filter(r -> kind.equals(r.kind()))
                .sorted(Comparator.comparingInt(PromptRow::orderIndex).thenComparing(PromptRow::promptKey))
                .toList();
    }

    private int maxVersion(String tenantId, String promptKey) {
        try {
            List<Map<String, Object>> raw = queryWithParams(
                "SELECT revision FROM " + TYPE + " WHERE tenantId = :t AND promptKey = :k "
              + "ORDER BY revision DESC LIMIT 1",
                Map.of("t", tenantId, "k", promptKey));
            if (raw.isEmpty()) return 0;
            return asInt(raw.get(0).get("revision"), 0);
        } catch (Exception e) {
            return 0;
        }
    }

    private void clearActive(String tenantId, String promptKey) {
        commandWithParams(
            "UPDATE " + TYPE + " SET activeFlag = false WHERE tenantId = :t AND promptKey = :k AND activeFlag = true",
            Map.of("t", tenantId, "k", promptKey));
    }

    private void invalidate(String tenantId) {
        cache.remove(tenantId);
    }

    private PromptRow toRow(Map<String, Object> r) {
        return new PromptRow(
                str(r.get("id")), str(r.get("tenantId")), str(r.get("kind")), str(r.get("promptKey")),
                str(r.get("name")), str(r.get("promptText")), str(r.get("description")),
                asInt(r.get("ordinal"), 0), asInt(r.get("revision"), 1),
                Boolean.TRUE.equals(r.get("activeFlag")),
                str(r.get("createdAt")), str(r.get("updatedAt")), str(r.get("createdBy")));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static int asInt(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? def : Integer.parseInt(o.toString().trim()); }
        catch (NumberFormatException e) { return def; }
    }

    // ── HTTP plumbing (mirrors TenantProviderMetaStore) ──────────────────────

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<Map<String, Object>> queryWithParams(String sql, Map<String, Object> params) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
            Map.of("language", "sql", "command", sql, "params", params), headers());
        var res = rest.exchange(arcadeUrl + "/api/v1/query/" + DB, HttpMethod.POST, entity, Map.class);
        if (res.getBody() == null) return List.of();
        Object result = res.getBody().get("result");
        return result instanceof List ? (List<Map<String, Object>>) result : List.of();
    }

    private void command(String sql) {
        HttpEntity<Map<String, String>> entity = new HttpEntity<>(
            Map.of("language", "sql", "command", sql), headers());
        rest.exchange(arcadeUrl + "/api/v1/command/" + DB, HttpMethod.POST, entity, Map.class);
    }

    private void commandWithParams(String sql, Map<String, Object> params) {
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(
            Map.of("language", "sql", "command", sql, "params", params), headers());
        rest.exchange(arcadeUrl + "/api/v1/command/" + DB, HttpMethod.POST, entity, Map.class);
    }

    private HttpHeaders headers() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBasicAuth(arcadeUser, arcadePassword);
        return h;
    }
}
