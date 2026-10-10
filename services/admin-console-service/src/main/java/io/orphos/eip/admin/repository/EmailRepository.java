package io.orphos.eip.admin.repository;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.util.Ids;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

/**
 * <p>Every method stamps {@code tenantId} on inserts and filters by it on
 * reads/updates/deletes. The tenant comes from {@link TenantContextHolder}.</p>
 */
@Repository
public class EmailRepository {

    private final ArcadeDbTemplate arcade;

    public EmailRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    // ----- EmailConfig -----
    public Optional<Map<String, Object>> findActiveConfig() {
        var rows = arcade.query(
                "SELECT FROM EmailConfig WHERE enabled = true AND tenantId = :tenantId LIMIT 1",
                Map.of("tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Map<String, Object>> findConfigByName(String name) {
        var rows = arcade.query(
                "SELECT FROM EmailConfig WHERE name = :n AND tenantId = :tenantId LIMIT 1",
                Map.of("n", name, "tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public List<Map<String, Object>> listConfigs() {
        return arcade.query(
                "SELECT FROM EmailConfig WHERE tenantId = :tenantId ORDER BY name",
                Map.of("tenantId", currentTenantId()));
    }

    public String upsertConfig(Map<String, Object> c) {
        String id = (String) c.getOrDefault("id", Ids.newId());
        c.put("id", id);
        c.put("updatedAt", Instant.now().toString());
        var existing = findConfigByName(Objects.toString(c.get("name")));
        if (existing.isEmpty()) {
            c.putIfAbsent("createdAt", Instant.now().toString());
            arcade.commandNoResult(
                    "INSERT INTO EmailConfig SET id = :id, tenantId = :tenantId, name = :name, host = :host, " +
                            "port = :port, fromAddress = :fa, fromName = :fn, username = :u, " +
                            "passwordRef = :pr, security = :sec, enabled = :en, " +
                            "createdAt = :ca, updatedAt = :ua",
                    asMap(c));
        } else {
            arcade.commandNoResult(
                    "UPDATE EmailConfig SET host = :host, port = :port, fromAddress = :fa, " +
                            "fromName = :fn, username = :u, passwordRef = :pr, security = :sec, " +
                            "enabled = :en, updatedAt = :ua WHERE name = :name AND tenantId = :tenantId",
                    asMap(c));
        }
        return id;
    }

    public void setSingleEnabled(String name) {
        // Per-tenant flip: without the tenantId predicate the first UPDATE
        // would disable every tenant's enabled config.
        String tenantId = currentTenantId();
        arcade.commandNoResult(
                "UPDATE EmailConfig SET enabled = false WHERE enabled = true AND tenantId = :tenantId",
                Map.of("tenantId", tenantId));
        arcade.commandNoResult(
                "UPDATE EmailConfig SET enabled = true WHERE name = :n AND tenantId = :tenantId",
                Map.of("n", name, "tenantId", tenantId));
    }

    // ----- EmailTemplate -----
    public List<Map<String, Object>> listTemplates() {
        return arcade.query(
                "SELECT FROM EmailTemplate WHERE tenantId = :tenantId ORDER BY code",
                Map.of("tenantId", currentTenantId()));
    }

    public Optional<Map<String, Object>> findTemplate(String code) {
        var rows = arcade.query(
                "SELECT FROM EmailTemplate WHERE code = :c AND tenantId = :tenantId LIMIT 1",
                Map.of("c", code, "tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public String upsertTemplate(Map<String, Object> t) {
        String id = (String) t.getOrDefault("id", Ids.newId());
        t.put("id", id);
        t.put("updatedAt", Instant.now().toString());
        var existing = findTemplate(Objects.toString(t.get("code")));
        if (existing.isEmpty()) {
            t.putIfAbsent("createdAt", Instant.now().toString());
            arcade.commandNoResult(
                    "INSERT INTO EmailTemplate SET id = :id, tenantId = :tenantId, code = :code, name = :name, " +
                            "subject = :sub, bodyHtml = :bh, bodyText = :bt, " +
                            "requiredVariables = :rv, createdAt = :ca, updatedAt = :ua",
                    tmplMap(t));
        } else {
            arcade.commandNoResult(
                    "UPDATE EmailTemplate SET name = :name, subject = :sub, bodyHtml = :bh, " +
                            "bodyText = :bt, requiredVariables = :rv, updatedAt = :ua " +
                            "WHERE code = :code AND tenantId = :tenantId",
                    tmplMap(t));
        }
        return id;
    }

    public void deleteTemplate(String code) {
        arcade.commandNoResult(
                "DELETE FROM EmailTemplate WHERE code = :c AND tenantId = :tenantId",
                Map.of("c", code, "tenantId", currentTenantId()));
    }

    // ----- EmailEvent -----
    public void recordEvent(String templateCode, String toAddress, String status, String reason) {
        arcade.commandNoResult(
                "INSERT INTO EmailEvent SET id = :id, tenantId = :tenantId, " +
                        "templateCode = :tc, toHash = :th, " +
                        "status = :s, reason = :r, createdAt = :now",
                Map.of("id", Ids.newId(),
                        "tenantId", currentTenantId(),
                        "tc", templateCode == null ? "" : templateCode,
                        // Hash the recipient address - we keep delivery analytics
                        // without storing recipient emails in plaintext.
                        "th", toAddress == null ? "" : Integer.toHexString(toAddress.hashCode()),
                        "s", status,
                        "r", reason == null ? "" : reason,
                        "now", Instant.now().toString()));
    }

    public List<Map<String, Object>> eventsSince(Instant since) {
        return arcade.query(
                "SELECT FROM EmailEvent WHERE createdAt >= :s AND tenantId = :tenantId " +
                        "ORDER BY createdAt DESC LIMIT 5000",
                Map.of("s", since.toString(), "tenantId", currentTenantId()));
    }

    // ----- NotificationPref -----
    public Optional<Map<String, Object>> findPrefs(String userKey) {
        var rows = arcade.query(
                "SELECT FROM NotificationPref WHERE userKey = :u AND tenantId = :tenantId LIMIT 1",
                Map.of("u", userKey, "tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void upsertPrefs(String userKey, boolean systemAlerts, boolean briefingDigest,
                            boolean securityAlerts, boolean marketingUpdates) {
        var existing = findPrefs(userKey);
        String tenantId = currentTenantId();
        if (existing.isEmpty()) {
            arcade.commandNoResult(
                    "INSERT INTO NotificationPref SET id = :id, tenantId = :tenantId, userKey = :u, " +
                            "systemAlerts = :sa, briefingDigest = :bd, securityAlerts = :sec, " +
                            "marketingUpdates = :mk, createdAt = :now, updatedAt = :now",
                    Map.of("id", Ids.newId(), "tenantId", tenantId, "u", userKey,
                            "sa", systemAlerts, "bd", briefingDigest,
                            "sec", securityAlerts, "mk", marketingUpdates,
                            "now", Instant.now().toString()));
        } else {
            arcade.commandNoResult(
                    "UPDATE NotificationPref SET systemAlerts = :sa, briefingDigest = :bd, " +
                            "securityAlerts = :sec, marketingUpdates = :mk, updatedAt = :now " +
                            "WHERE userKey = :u AND tenantId = :tenantId",
                    Map.of("u", userKey, "tenantId", tenantId,
                            "sa", systemAlerts, "bd", briefingDigest,
                            "sec", securityAlerts, "mk", marketingUpdates,
                            "now", Instant.now().toString()));
        }
    }

    // ----- Backup -----
    public String storeBackup(String label, Map<String, Object> snapshot) {
        String id = Ids.newId();
        arcade.commandNoResult(
                "INSERT INTO EmailConfigBackup SET id = :id, tenantId = :tenantId, " +
                        "label = :l, snapshot = :s, createdAt = :now",
                Map.of("id", id, "tenantId", currentTenantId(),
                        "l", label, "s", snapshot, "now", Instant.now().toString()));
        return id;
    }

    public List<Map<String, Object>> listBackups() {
        return arcade.query(
                "SELECT id, label, createdAt FROM EmailConfigBackup WHERE tenantId = :tenantId " +
                        "ORDER BY createdAt DESC LIMIT 100",
                Map.of("tenantId", currentTenantId()));
    }

    public Optional<Map<String, Object>> findBackup(String id) {
        var rows = arcade.query(
                "SELECT FROM EmailConfigBackup WHERE id = :i AND tenantId = :tenantId LIMIT 1",
                Map.of("i", id, "tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    // ----- Helpers -----
    private static String currentTenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    private static Map<String, Object> asMap(Map<String, Object> c) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", c.get("id"));
        m.put("tenantId", currentTenantId());
        m.put("name", c.get("name"));
        m.put("host", c.get("host"));
        m.put("port", c.getOrDefault("port", 587));
        m.put("fa", c.get("fromAddress"));
        m.put("fn", c.get("fromName"));
        m.put("u", c.get("username"));
        // We store a reference (passwordRef), never the raw secret.
        m.put("pr", c.getOrDefault("passwordRef", ""));
        m.put("sec", c.getOrDefault("security", "STARTTLS"));
        m.put("en", c.getOrDefault("enabled", false));
        m.put("ca", c.getOrDefault("createdAt", Instant.now().toString()));
        m.put("ua", c.getOrDefault("updatedAt", Instant.now().toString()));
        return m;
    }

    private static Map<String, Object> tmplMap(Map<String, Object> t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.get("id"));
        m.put("tenantId", currentTenantId());
        m.put("code", t.get("code"));
        m.put("name", t.get("name"));
        m.put("sub", t.get("subject"));
        m.put("bh", t.getOrDefault("bodyHtml", ""));
        m.put("bt", t.getOrDefault("bodyText", ""));
        m.put("rv", t.getOrDefault("requiredVariables", List.of()));
        m.put("ca", t.getOrDefault("createdAt", Instant.now().toString()));
        m.put("ua", t.getOrDefault("updatedAt", Instant.now().toString()));
        return m;
    }
}
