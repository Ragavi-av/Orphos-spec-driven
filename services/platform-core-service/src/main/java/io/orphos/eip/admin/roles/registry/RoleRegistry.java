package io.orphos.eip.admin.roles.registry;

import io.orphos.eip.admin.common.Ids;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Per-tenant role metadata (alias / colour / custom-flag) layered on top of the
 * tenant's Keycloak realm roles. Built-in roles need no row here — the service
 * applies the application defaults when a row is absent; a row exists only when
 * an admin has customised the role (an alias/colour) or created a custom role.
 *
 * <p>One {@code TenantRoleMeta} document per {@code (tenantId, roleKey)}. Follows
 * the self-bootstrapping schema pattern of {@code TenantDataRegistry}; every
 * read/write is scoped to the tenant from {@link TenantContextHolder}.</p>
 */
@Component
public class RoleRegistry {

    private static final Logger log = LogManager.getLogger(RoleRegistry.class);
    private static final String TYPE = "TenantRoleMeta";

    private final ArcadeDbTemplate arcade;

    public RoleRegistry(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    @PostConstruct
    public void ensureSchema() {
        try {
            arcade.commandNoResult("CREATE DOCUMENT TYPE " + TYPE + " IF NOT EXISTS", Map.of());
            arcade.commandNoResult("CREATE PROPERTY " + TYPE + ".tenantId IF NOT EXISTS STRING", Map.of());
            arcade.commandNoResult("CREATE PROPERTY " + TYPE + ".roleKey IF NOT EXISTS STRING", Map.of());
            arcade.commandNoResult("CREATE PROPERTY " + TYPE + ".alias IF NOT EXISTS STRING", Map.of());
            arcade.commandNoResult("CREATE PROPERTY " + TYPE + ".color IF NOT EXISTS STRING", Map.of());
            arcade.commandNoResult("CREATE PROPERTY " + TYPE + ".custom IF NOT EXISTS BOOLEAN", Map.of());
            arcade.commandNoResult("CREATE PROPERTY " + TYPE + ".basedOn IF NOT EXISTS STRING", Map.of());
            arcade.commandNoResult("CREATE PROPERTY " + TYPE + ".updatedAt IF NOT EXISTS STRING", Map.of());
            arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON " + TYPE + " (tenantId, roleKey) UNIQUE", Map.of());
            log.info("TenantRoleMeta schema ready on ArcadeDB");
        } catch (Exception e) {
            log.warn("Could not initialise TenantRoleMeta schema (will retry per-call): {}", e.getMessage());
        }
    }

    /** All role-metadata rows for the current tenant. */
    public List<Map<String, Object>> list() {
        return arcade.query(
                "SELECT roleKey, alias, color, custom, basedOn FROM " + TYPE + " WHERE tenantId = :t",
                Map.of("t", tenantId()));
    }

    public Optional<Map<String, Object>> get(String roleKey) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT roleKey, alias, color, custom, basedOn FROM " + TYPE
                        + " WHERE tenantId = :t AND roleKey = :r LIMIT 1",
                Map.of("t", tenantId(), "r", roleKey));
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Insert or update the metadata for a role. Nulls for {@code alias}/{@code color}
     * are stored as blanks; {@code custom}/{@code basedOn} identify admin-created roles.
     */
    public void upsert(String roleKey, String alias, String color, boolean custom, String basedOn) {
        String t = tenantId();
        String now = Instant.now().toString();
        boolean exists = !arcade.query(
                "SELECT FROM " + TYPE + " WHERE tenantId = :t AND roleKey = :r LIMIT 1",
                Map.of("t", t, "r", roleKey)).isEmpty();
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("t", t);
        params.put("r", roleKey);
        params.put("alias", alias == null ? "" : alias);
        params.put("color", color == null ? "" : color);
        params.put("custom", custom);
        params.put("basedOn", basedOn == null ? "" : basedOn);
        params.put("now", now);
        if (exists) {
            arcade.commandNoResult(
                    "UPDATE " + TYPE + " SET alias = :alias, color = :color, custom = :custom, "
                            + "basedOn = :basedOn, updatedAt = :now WHERE tenantId = :t AND roleKey = :r",
                    params);
        } else {
            params.put("id", Ids.newId());
            arcade.commandNoResult(
                    "INSERT INTO " + TYPE + " SET id = :id, tenantId = :t, roleKey = :r, alias = :alias, "
                            + "color = :color, custom = :custom, basedOn = :basedOn, updatedAt = :now",
                    params);
        }
    }

    public void delete(String roleKey) {
        arcade.commandNoResult(
                "DELETE FROM " + TYPE + " WHERE tenantId = :t AND roleKey = :r",
                Map.of("t", tenantId(), "r", roleKey));
    }

    /** Removes ALL role metadata for the tenant — clears every alias and custom marker. */
    public void deleteAll() {
        arcade.commandNoResult("DELETE FROM " + TYPE + " WHERE tenantId = :t", Map.of("t", tenantId()));
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }
}
