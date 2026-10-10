package io.orphos.eip.admin.tenant.repo;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.tenant.model.IsolationMode;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.model.TenantStatus;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ArcadeDB-backed CRUD for the {@code Tenant} document type. Mirrors the
 * hand-rolled SQL pattern used by {@link io.orphos.eip.admin.auth.repo.AuthRepository}
 * — no JPA, parameter binding via {@code :name}, never string concatenation
 * of user input.
 *
 * <p>DELETED rows are kept (soft-delete) but filtered out of {@link #listActive()}.
 * Use {@link #findByIdIncludingDeleted(UUID)} for audit reads.</p>
 */
@Repository
public class TenantRepository {

    private final ArcadeDbTemplate arcade;

    public TenantRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    public Tenant insert(Tenant t) {
        arcade.commandNoResult(
                "INSERT INTO Tenant SET id = :id, domain = :domain, displayName = :name, " +
                        "status = :status, isolationMode = :iso, arcadeDatabase = :db, " +
                        "arcadeConnectionId = :connId, " +
                        "keycloakRealm = :realm, createdAt = :ca, updatedAt = :ua",
                toParams(t));
        return t;
    }

    public Optional<Tenant> findById(UUID id) {
        var rows = arcade.query(
                "SELECT FROM Tenant WHERE id = :id AND status <> 'DELETED' LIMIT 1",
                Map.of("id", id.toString()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromMap(rows.get(0)));
    }

    public Optional<Tenant> findByIdIncludingDeleted(UUID id) {
        var rows = arcade.query(
                "SELECT FROM Tenant WHERE id = :id LIMIT 1",
                Map.of("id", id.toString()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromMap(rows.get(0)));
    }

    public Optional<Tenant> findByDomain(String domain) {
        var rows = arcade.query(
                "SELECT FROM Tenant WHERE domain = :d AND status <> 'DELETED' LIMIT 1",
                Map.of("d", domain));
        return rows.isEmpty() ? Optional.empty() : Optional.of(fromMap(rows.get(0)));
    }

    /**
     * Case-insensitive match on {@code displayName}. Drives the SPA's
     * path-based tenant routing ({@code http://host:port/<displayName>/}) so
     * a friendly URL like {@code /Acme%20Corporation/} resolves to the right
     * tenant regardless of how the operator capitalised the name in the DB.
     *
     * <p>Filters in Java rather than via SQL string functions — ArcadeDB's
     * dialect varies on string-method support and the active tenant set is
     * small (typically tens, never thousands), so a list-and-scan is cheaper
     * than maintaining a stored-lowercase index.</p>
     */
    public Optional<Tenant> findByDisplayNameIgnoreCase(String displayName) {
        if (displayName == null) return Optional.empty();
        String target = displayName.trim().toLowerCase();
        return listActive().stream()
                .filter(t -> t.displayName() != null
                        && t.displayName().trim().toLowerCase().equals(target))
                .findFirst();
    }

    public List<Tenant> listActive() {
        return arcade.query(
                "SELECT FROM Tenant WHERE status <> 'DELETED' ORDER BY createdAt DESC LIMIT 1000",
                Map.of())
                .stream().map(TenantRepository::fromMap).collect(Collectors.toList());
    }

    public void updateDisplayName(UUID id, String displayName) {
        arcade.commandNoResult(
                "UPDATE Tenant SET displayName = :name, updatedAt = :ua WHERE id = :id",
                Map.of("name", displayName, "ua", Instant.now().toString(), "id", id.toString()));
    }

    public void updateDomain(UUID id, String newDomain) {
        arcade.commandNoResult(
                "UPDATE Tenant SET domain = :d, updatedAt = :ua WHERE id = :id",
                Map.of("d", newDomain, "ua", Instant.now().toString(), "id", id.toString()));
    }

    public void updateStatus(UUID id, TenantStatus status) {
        arcade.commandNoResult(
                "UPDATE Tenant SET status = :s, updatedAt = :ua WHERE id = :id",
                Map.of("s", status.name(), "ua", Instant.now().toString(), "id", id.toString()));
    }

    /**
     * Hard-delete the Tenant row. Used by the destroy flow only — soft-delete
     * (status=DELETED) is the normal path. This is irreversible.
     */
    public void hardDelete(UUID id) {
        arcade.commandNoResult(
                "DELETE FROM Tenant WHERE id = :id",
                Map.of("id", id.toString()));
    }

    private static Map<String, Object> toParams(Tenant t) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", t.id().toString());
        m.put("domain", t.domain());
        m.put("name", t.displayName());
        m.put("status", t.status().name());
        m.put("iso", t.isolationMode().name());
        m.put("db", t.arcadeDatabase());
        // Empty string for SHARED / bundled default — avoids binding a null SQL
        // parameter; fromMap maps "" back to null.
        m.put("connId", t.arcadeConnectionId() == null ? "" : t.arcadeConnectionId());
        m.put("realm", t.keycloakRealm());
        m.put("ca", t.createdAt().toString());
        m.put("ua", t.updatedAt().toString());
        return m;
    }

    private static Tenant fromMap(Map<String, Object> row) {
        return new Tenant(
                UUID.fromString(Objects.toString(row.get("id"))),
                Objects.toString(row.get("domain"), null),
                Objects.toString(row.get("displayName"), null),
                TenantStatus.valueOf(Objects.toString(row.get("status"))),
                IsolationMode.valueOf(Objects.toString(row.get("isolationMode"))),
                Objects.toString(row.get("arcadeDatabase"), null),
                blankToNull(Objects.toString(row.get("arcadeConnectionId"), null)),
                Objects.toString(row.get("keycloakRealm"), null),
                Instant.parse(Objects.toString(row.get("createdAt"))),
                Instant.parse(Objects.toString(row.get("updatedAt"))));
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
