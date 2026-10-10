package io.orphos.eip.admin.repository;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.util.Ids;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.*;

/**
 * <p>Every method stamps {@code tenantId} on inserts and filters by it on
 * reads/updates/deletes. The tenant is pulled from {@link TenantContextHolder}
 * (populated upstream by the {@code TenantResolutionFilter}), so callers do
 * not pass it explicitly — that keeps the contract symmetric across HTTP and
 * background paths (the latter wrap their work in
 * {@code TenantContextHolder.runWith}).</p>
 */
@Repository
public class BrandingRepository {

    private final ArcadeDbTemplate arcade;

    public BrandingRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    // ----- Profile -----
    public List<Map<String, Object>> listProfiles() {
        return arcade.query(
                "SELECT FROM BrandingProfile WHERE tenantId = :tenantId ORDER BY name",
                Map.of("tenantId", currentTenantId()));
    }

    public Optional<Map<String, Object>> findProfile(String id) {
        var rows = arcade.query(
                "SELECT FROM BrandingProfile WHERE id = :i AND tenantId = :tenantId LIMIT 1",
                Map.of("i", id, "tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Map<String, Object>> findActiveProfile() {
        var rows = arcade.query(
                "SELECT FROM BrandingProfile WHERE active = true AND tenantId = :tenantId LIMIT 1",
                Map.of("tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public Optional<Map<String, Object>> findProfileByName(String name) {
        var rows = arcade.query(
                "SELECT FROM BrandingProfile WHERE name = :n AND tenantId = :tenantId LIMIT 1",
                Map.of("n", name == null ? "" : name, "tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public String saveProfile(Map<String, Object> p) {
        String id = (String) p.getOrDefault("id", Ids.newId());
        p.put("id", id);
        p.put("updatedAt", Instant.now().toString());
        // Use UPSERT semantics: if exists update, else insert
        var existing = findProfile(id);
        if (existing.isEmpty()) {
            p.putIfAbsent("createdAt", Instant.now().toString());
            arcade.commandNoResult(
                    "INSERT INTO BrandingProfile SET id = :id, tenantId = :tenantId, " +
                            "name = :name, productName = :pn, " +
                            "tagline = :tg, defaultLanguage = :dl, " +
                            "activeThemeId = :th, logoAssetId = :lg, " +
                            "backgroundAssetId = :bg, faviconAssetId = :fv, " +
                            "fontFamily = :ff, fontSize = :fs, timezone = :tz, " +
                            "currency = :cur, country = :ctry, numberLocale = :nloc, " +
                            "statusGreen = :sg, statusBlue = :sb, statusAmber = :sa, " +
                            "active = :a, createdAt = :ca, updatedAt = :ua",
                    asMap(p));
        } else {
            arcade.commandNoResult(
                    "UPDATE BrandingProfile SET name = :name, productName = :pn, tagline = :tg, " +
                            "defaultLanguage = :dl, " +
                            "activeThemeId = :th, logoAssetId = :lg, backgroundAssetId = :bg, " +
                            "faviconAssetId = :fv, fontFamily = :ff, fontSize = :fs, " +
                            "timezone = :tz, currency = :cur, country = :ctry, " +
                            "numberLocale = :nloc, statusGreen = :sg, statusBlue = :sb, " +
                            "statusAmber = :sa, active = :a, updatedAt = :ua " +
                            "WHERE id = :id AND tenantId = :tenantId",
                    asMap(p));
        }
        return id;
    }

    public void setActive(String id) {
        // Only one active profile per tenant — scoping both UPDATEs by
        // tenantId is critical: without it, activating tenant A's profile
        // would deactivate every tenant's currently-active profile.
        String tenantId = currentTenantId();
        arcade.commandNoResult(
                "UPDATE BrandingProfile SET active = false WHERE active = true AND tenantId = :tenantId",
                Map.of("tenantId", tenantId));
        arcade.commandNoResult(
                "UPDATE BrandingProfile SET active = true WHERE id = :id AND tenantId = :tenantId",
                Map.of("id", id, "tenantId", tenantId));
    }

    // ----- Theme -----
    public List<Map<String, Object>> listThemes() {
        return arcade.query(
                "SELECT FROM Theme WHERE tenantId = :tenantId ORDER BY name",
                Map.of("tenantId", currentTenantId()));
    }

    public Optional<Map<String, Object>> findTheme(String id) {
        var rows = arcade.query(
                "SELECT FROM Theme WHERE id = :i AND tenantId = :tenantId LIMIT 1",
                Map.of("i", id, "tenantId", currentTenantId()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public String saveTheme(Map<String, Object> t) {
        String id = (String) t.getOrDefault("id", Ids.newId());
        t.put("id", id);
        t.put("updatedAt", Instant.now().toString());
        var existing = findTheme(id);
        if (existing.isEmpty()) {
            t.putIfAbsent("createdAt", Instant.now().toString());
            arcade.commandNoResult(
                    "INSERT INTO Theme SET id = :id, tenantId = :tenantId, name = :name, mode = :mode, " +
                            "colors = :colors, sanitizedCss = :css, " +
                            "createdAt = :ca, updatedAt = :ua",
                    asMap(t));
        } else {
            arcade.commandNoResult(
                    "UPDATE Theme SET name = :name, mode = :mode, colors = :colors, " +
                            "sanitizedCss = :css, updatedAt = :ua " +
                            "WHERE id = :id AND tenantId = :tenantId",
                    asMap(t));
        }
        return id;
    }

    public void deleteTheme(String id) {
        arcade.commandNoResult(
                "DELETE FROM Theme WHERE id = :id AND tenantId = :tenantId",
                Map.of("id", id, "tenantId", currentTenantId()));
    }

    // ----- Asset (binary) -----
    public String insertAsset(String profileId, String kind, String contentType,
                              long sizeBytes, byte[] data) {
        String id = Ids.newId();
        // ArcadeDB accepts base64-encoded binary via the SQL driver; we store
        // the encoded form and decode on read.
        String b64 = Base64.getEncoder().encodeToString(data);
        arcade.commandNoResult(
                "INSERT INTO BrandingAsset SET id = :id, tenantId = :tenantId, " +
                        "profileId = :p, kind = :k, " +
                        "contentType = :ct, sizeBytes = :sz, data = :d, createdAt = :now",
                Map.of("id", id, "tenantId", currentTenantId(),
                        "p", profileId == null ? "" : profileId,
                        "k", kind, "ct", contentType, "sz", sizeBytes,
                        "d", b64, "now", Instant.now().toString()));
        return id;
    }

    /**
     * Look up an asset by its unique id ONLY — deliberately not tenant-scoped.
     * This backs the public, unauthenticated {@code /api/branding/public/asset/{id}}
     * endpoint, which is hit as a raw &lt;img&gt; request (no JWT, no X-Tenant-Id
     * header) by both the login page and the admin preview. Scoping by tenant
     * here broke cross-host access: the request's tenant resolves from the host,
     * so a tenant whose asset id didn't match the host-default got a 404 / broken
     * image. Asset ids are unguessable UUIDs and logos/backgrounds are public
     * branding, so serving by id alone is safe.
     */
    public Optional<Map<String, Object>> findAsset(String id) {
        var rows = arcade.query(
                "SELECT FROM BrandingAsset WHERE id = :i LIMIT 1",
                Map.of("i", id));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Tenant-scoped counterpart of {@link #findAsset}: present only when the current tenant owns it. */
    public boolean ownsAsset(String id) {
        return !arcade.query(
                "SELECT id FROM BrandingAsset WHERE id = :i AND tenantId = :tenantId LIMIT 1",
                Map.of("i", id, "tenantId", currentTenantId())).isEmpty();
    }

    public void deleteAsset(String id) {
        arcade.commandNoResult(
                "DELETE FROM BrandingAsset WHERE id = :id AND tenantId = :tenantId",
                Map.of("id", id, "tenantId", currentTenantId()));
    }

    /**
     * Remove every appearance override the current tenant owns (profiles,
     * themes, asset binaries) so it falls back to the platform default. UI
     * label overrides are deliberately untouched — they have their own restore.
     */
    public void deleteAllAppearance() {
        Map<String, Object> params = Map.of("tenantId", currentTenantId());
        arcade.commandNoResult("DELETE FROM BrandingProfile WHERE tenantId = :tenantId", params);
        arcade.commandNoResult("DELETE FROM Theme WHERE tenantId = :tenantId", params);
        arcade.commandNoResult("DELETE FROM BrandingAsset WHERE tenantId = :tenantId", params);
    }

    // ----- UI label overrides -----
    public List<Map<String, Object>> listLabels() {
        return arcade.query(
                "SELECT FROM UiLabel WHERE tenantId = :tenantId ORDER BY lang, labelKey",
                Map.of("tenantId", currentTenantId()));
    }

    public void upsertLabel(String lang, String labelKey, String value) {
        String tenantId = currentTenantId();
        var existing = arcade.query(
                "SELECT FROM UiLabel WHERE tenantId = :tenantId AND lang = :l AND labelKey = :k LIMIT 1",
                Map.of("tenantId", tenantId, "l", lang, "k", labelKey));
        String now = Instant.now().toString();
        if (existing.isEmpty()) {
            arcade.commandNoResult(
                    "INSERT INTO UiLabel SET id = :id, tenantId = :tenantId, lang = :l, " +
                            "labelKey = :k, value = :v, createdAt = :now, updatedAt = :now",
                    Map.of("id", Ids.newId(), "tenantId", tenantId, "l", lang,
                            "k", labelKey, "v", value, "now", now));
        } else {
            arcade.commandNoResult(
                    "UPDATE UiLabel SET value = :v, updatedAt = :now " +
                            "WHERE tenantId = :tenantId AND lang = :l AND labelKey = :k",
                    Map.of("tenantId", tenantId, "l", lang, "k", labelKey, "v", value, "now", now));
        }
    }

    /** Current override value for (lang, labelKey), or null when none exists. */
    public String findLabelValue(String lang, String labelKey) {
        var rows = arcade.query(
                "SELECT value FROM UiLabel WHERE tenantId = :tenantId AND lang = :l AND labelKey = :k LIMIT 1",
                Map.of("tenantId", currentTenantId(), "l", lang, "k", labelKey));
        return rows.isEmpty() ? null : Objects.toString(rows.get(0).get("value"), null);
    }

    /** Append one audit row for a label change (old/new value, actor, source). */
    public void insertLabelHistory(String lang, String labelKey, String oldValue, String newValue,
                                   String changedBy, String source) {
        Map<String, Object> p = new HashMap<>();
        p.put("id", Ids.newId());
        p.put("tenantId", currentTenantId());
        p.put("lang", lang);
        p.put("labelKey", labelKey);
        p.put("oldValue", oldValue == null ? "" : oldValue);
        p.put("newValue", newValue == null ? "" : newValue);
        p.put("changedBy", changedBy == null ? "" : changedBy);
        p.put("source", source == null ? "" : source);
        p.put("changedAt", Instant.now().toString());
        arcade.commandNoResult(
                "INSERT INTO UiLabelHistory SET id = :id, tenantId = :tenantId, lang = :lang, " +
                        "labelKey = :labelKey, oldValue = :oldValue, newValue = :newValue, " +
                        "changedBy = :changedBy, source = :source, changedAt = :changedAt", p);
    }

    public List<Map<String, Object>> listLabelHistory(int limit) {
        return arcade.query(
                "SELECT FROM UiLabelHistory WHERE tenantId = :tenantId ORDER BY changedAt DESC LIMIT " + limit,
                Map.of("tenantId", currentTenantId()));
    }

    /** @return true when an override row existed and was removed. */
    public boolean deleteLabel(String lang, String labelKey) {
        String tenantId = currentTenantId();
        var existing = arcade.query(
                "SELECT FROM UiLabel WHERE tenantId = :tenantId AND lang = :l AND labelKey = :k LIMIT 1",
                Map.of("tenantId", tenantId, "l", lang, "k", labelKey));
        if (existing.isEmpty()) return false;
        arcade.commandNoResult(
                "DELETE FROM UiLabel WHERE tenantId = :tenantId AND lang = :l AND labelKey = :k",
                Map.of("tenantId", tenantId, "l", lang, "k", labelKey));
        return true;
    }

    // ----- Helpers -----
    private static String currentTenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    private static Map<String, Object> asMap(Map<String, Object> p) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", p.get("id"));
        m.put("tenantId", currentTenantId());
        m.put("name", p.getOrDefault("name", ""));
        m.put("pn", p.getOrDefault("productName", ""));
        m.put("tg", p.getOrDefault("tagline", ""));
        m.put("dl", p.getOrDefault("defaultLanguage", ""));
        m.put("th", p.getOrDefault("activeThemeId", ""));
        m.put("lg", p.getOrDefault("logoAssetId", ""));
        m.put("bg", p.getOrDefault("backgroundAssetId", ""));
        m.put("fv", p.getOrDefault("faviconAssetId", ""));
        m.put("a", p.getOrDefault("active", false));
        m.put("mode", p.getOrDefault("mode", "light"));
        m.put("colors", p.getOrDefault("colors", Map.of()));
        m.put("ff", p.getOrDefault("fontFamily", ""));
        m.put("fs", p.getOrDefault("fontSize", 0));
        m.put("tz", p.getOrDefault("timezone", ""));
        m.put("cur", p.getOrDefault("currency", ""));
        // The tenant's primary country, and the notation it resolves to. The
        // service derives tz/cur/defaultLanguage from the same country before
        // this map is built, so the row is internally consistent by the time it
        // is written.
        m.put("ctry", p.getOrDefault("country", ""));
        m.put("nloc", p.getOrDefault("numberLocale", ""));
        m.put("sg", p.getOrDefault("statusGreen", 15));
        m.put("sb", p.getOrDefault("statusBlue", 30));
        m.put("sa", p.getOrDefault("statusAmber", 50));
        m.put("css", p.getOrDefault("sanitizedCss", ""));
        m.put("ca", p.getOrDefault("createdAt", Instant.now().toString()));
        m.put("ua", p.getOrDefault("updatedAt", Instant.now().toString()));
        return m;
    }
}
