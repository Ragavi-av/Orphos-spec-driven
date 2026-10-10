package io.orphos.eip.mailinsight.channels;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mailinsight.arcade.MailArcadeClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped CRUD for communication-channel connections
 * ({@code CommChannelConnection} — a tenant may have several, e.g. one Slack
 * workspace and one Teams tenant) and the monitored accounts tied to them
 * ({@code MonitoredChannelAccount} — a channel/conversation, via
 * {@code channelConnectionId}). Mirrors {@code MailboxConfigRepository}.
 *
 * <p>Bot tokens / client secrets are never stored here — only the Infisical
 * reference path ({@code credentialRef}). Scan-run audit rows reuse the shared
 * {@code MailScanRun} type (with the account id in the {@code mailboxId} slot),
 * so channel scans show up in the same run history as mailbox scans.</p>
 */
@Repository
public class ChannelConfigRepository {

    private final MailArcadeClient arcade;

    public ChannelConfigRepository(MailArcadeClient arcade) {
        this.arcade = arcade;
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    // ── Connections ─────────────────────────────────────────────────────────

    public List<Map<String, Object>> listConnections() {
        return arcade.query(
                "SELECT * FROM CommChannelConnection WHERE tenantId = :tenantId ORDER BY createdAt",
                Map.of("tenantId", tenantId()));
    }

    public Map<String, Object> getConnectionById(String id) {
        if (id == null || id.isBlank()) return null;
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM CommChannelConnection WHERE tenantId = :tenantId AND id = :id LIMIT 1",
                Map.of("tenantId", tenantId(), "id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean nameTaken(String name, String excludeId) {
        String needle = name == null ? "" : name.trim().toLowerCase();
        for (Map<String, Object> row : listConnections()) {
            String rowId = String.valueOf(row.get("id"));
            if (rowId.equals(excludeId)) continue;
            String rowName = String.valueOf(row.getOrDefault("name", "")).trim().toLowerCase();
            if (rowName.equals(needle)) return true;
        }
        return false;
    }

    /**
     * Create or update a channel connection. {@code id} blank → insert.
     * {@code credentialRef} (an Infisical "path#field") is preserved when
     * null/blank on update.
     *
     * @throws IllegalStateException if {@code name} collides with another connection in this tenant
     */
    public String saveConnection(String id, String name, String kind, String configJson,
                                 String credentialRef, int defaultScanWindowDays, boolean enabled,
                                 String scheduleJson) {
        if (nameTaken(name, id)) {
            throw new IllegalStateException("A channel connection named \"" + name.trim() + "\" already exists.");
        }
        String tenantId = tenantId();
        String now = Instant.now().toString();
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenantId);
        p.put("name", name == null ? "" : name);
        p.put("kind", kind == null ? "" : kind);
        p.put("configJson", configJson == null ? "{}" : configJson);
        p.put("defaultScanWindowDays", defaultScanWindowDays);
        p.put("enabled", enabled);
        p.put("scheduleJson", scheduleJson == null ? "" : scheduleJson);
        p.put("updatedAt", now);

        Map<String, Object> existing = id == null || id.isBlank() ? null : getConnectionById(id);
        if (existing != null) {
            p.put("id", id);
            String credClause = "";
            if (credentialRef != null && !credentialRef.isBlank()) {
                p.put("credentialRef", credentialRef);
                credClause = "credentialRef = :credentialRef, ";
            }
            arcade.commandNoResult(
                    "UPDATE CommChannelConnection SET name = :name, kind = :kind, configJson = :configJson, "
                    + credClause
                    + "defaultScanWindowDays = :defaultScanWindowDays, enabled = :enabled, "
                    + "scheduleJson = :scheduleJson, updatedAt = :updatedAt "
                    + "WHERE tenantId = :tenantId AND id = :id", p);
            return id;
        }
        String newId = "cc-" + UUID.randomUUID();
        p.put("id", newId);
        p.put("credentialRef", credentialRef == null ? "" : credentialRef);
        p.put("createdAt", now);
        arcade.commandNoResult(
                "INSERT INTO CommChannelConnection SET id = :id, tenantId = :tenantId, name = :name, "
                + "kind = :kind, configJson = :configJson, credentialRef = :credentialRef, "
                + "defaultScanWindowDays = :defaultScanWindowDays, enabled = :enabled, "
                + "scheduleJson = :scheduleJson, createdAt = :createdAt, updatedAt = :updatedAt", p);
        return newId;
    }

    public void updateScheduledScanStamp(String connectionId) {
        arcade.commandNoResult(
                "UPDATE CommChannelConnection SET lastScheduledScanAt = :now "
                + "WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", connectionId, "now", Instant.now().toString()));
    }

    /** Delete a connection and every account under it (channels are meaningless without their workspace). */
    public void deleteConnection(String id) {
        arcade.commandNoResult(
                "DELETE FROM MonitoredChannelAccount WHERE tenantId = :tenantId AND channelConnectionId = :cid",
                Map.of("tenantId", tenantId(), "cid", id));
        arcade.commandNoResult(
                "DELETE FROM CommChannelConnection WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id));
    }

    // ── Accounts (channels/conversations within a connection) ─────────────────

    public List<Map<String, Object>> listAccounts() {
        return arcade.query(
                "SELECT * FROM MonitoredChannelAccount WHERE tenantId = :tenantId ORDER BY displayName",
                Map.of("tenantId", tenantId()));
    }

    public List<Map<String, Object>> listAccountsByConnection(String connectionId) {
        return arcade.query(
                "SELECT * FROM MonitoredChannelAccount WHERE tenantId = :tenantId AND channelConnectionId = :cid "
                + "ORDER BY displayName",
                Map.of("tenantId", tenantId(), "cid", connectionId == null ? "" : connectionId));
    }

    public Map<String, Object> findAccount(String id) {
        if (id == null || id.isBlank()) return null;
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM MonitoredChannelAccount WHERE tenantId = :tenantId AND id = :id LIMIT 1",
                Map.of("tenantId", tenantId(), "id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public String addAccount(String channelConnectionId, String externalId, String displayName,
                             Integer scanWindowDays, String ownerSub, String ownerEmail, String createdBy) {
        String id = "ca-" + UUID.randomUUID();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("tenantId", tenantId());
        p.put("channelConnectionId", channelConnectionId == null ? "" : channelConnectionId);
        p.put("externalId", externalId == null ? "" : externalId);
        p.put("displayName", displayName == null ? "" : displayName);
        p.put("scanWindowDays", scanWindowDays == null ? 0 : scanWindowDays);
        p.put("ownerSub", ownerSub == null ? "" : ownerSub);
        p.put("ownerEmail", ownerEmail == null ? "" : ownerEmail);
        p.put("createdBy", createdBy == null ? "" : createdBy);
        p.put("createdAt", Instant.now().toString());
        arcade.commandNoResult(
                "INSERT INTO MonitoredChannelAccount SET id = :id, tenantId = :tenantId, "
                + "channelConnectionId = :channelConnectionId, externalId = :externalId, "
                + "displayName = :displayName, scanWindowDays = :scanWindowDays, "
                + "ownerSub = :ownerSub, ownerEmail = :ownerEmail, enabled = true, "
                + "createdBy = :createdBy, createdAt = :createdAt", p);
        return id;
    }

    public void setAccountEnabled(String accountId, boolean enabled) {
        arcade.commandNoResult(
                "UPDATE MonitoredChannelAccount SET enabled = :enabled WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", accountId, "enabled", enabled));
    }

    public void deleteAccount(String id) {
        arcade.commandNoResult(
                "DELETE FROM MonitoredChannelAccount WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id));
    }

    public void updateScanStatus(String accountId, String status, String error) {
        arcade.commandNoResult(
                "UPDATE MonitoredChannelAccount SET lastScanAt = :now, lastScanStatus = :status, lastError = :error "
                + "WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", accountId, "now", Instant.now().toString(),
                        "status", status == null ? "" : status, "error", error == null ? "" : error));
    }

    // ── Scan-run audit (shared MailScanRun type) ──────────────────────────────

    public String startRun(String accountId) {
        String id = "run-" + UUID.randomUUID();
        arcade.commandNoResult(
                "INSERT INTO MailScanRun SET id = :id, tenantId = :tenantId, mailboxId = :mailboxId, "
                + "startedAt = :now, status = 'RUNNING', itemsCreated = 0, itemsUpdated = 0, error = ''",
                Map.of("id", id, "tenantId", tenantId(), "mailboxId", accountId, "now", Instant.now().toString()));
        return id;
    }

    public void finishRun(String runId, String status, int created, int updated, String error) {
        arcade.commandNoResult(
                "UPDATE MailScanRun SET finishedAt = :now, status = :status, itemsCreated = :created, "
                + "itemsUpdated = :updated, error = :error WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", runId, "now", Instant.now().toString(),
                        "status", status, "created", created, "updated", updated,
                        "error", error == null ? "" : error));
    }
}
