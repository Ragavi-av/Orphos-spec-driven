package io.orphos.eip.mailinsight.repo;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mailinsight.arcade.MailArcadeClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant-scoped CRUD for mail connections ({@code MailMonitorConfig} — a tenant
 * may have several, e.g. one Microsoft 365 connection and one Gmail connection)
 * and the monitored mailboxes tied to them ({@code MonitoredMailbox}, via
 * {@code mailConnectionId}).
 *
 * <p>The Graph client secret itself is never stored here — only the Infisical
 * reference path ({@code clientSecretRef}). Callers resolve the actual secret
 * from Infisical at request time.</p>
 */
@Repository
public class MailboxConfigRepository {

    private final MailArcadeClient arcade;

    public MailboxConfigRepository(MailArcadeClient arcade) {
        this.arcade = arcade;
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    // ── Connections (one or more per tenant) ─────────────────────────────────

    public List<Map<String, Object>> listConfigs() {
        return arcade.query(
                "SELECT * FROM MailMonitorConfig WHERE tenantId = :tenantId ORDER BY createdAt",
                Map.of("tenantId", tenantId()));
    }

    public Map<String, Object> getConfigById(String id) {
        if (id == null || id.isBlank()) return null;
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM MailMonitorConfig WHERE tenantId = :tenantId AND id = :id LIMIT 1",
                Map.of("tenantId", tenantId(), "id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The tenant's "default" connection for flows with no connection picker
     * (self-service "monitor my own mailbox"): the first enabled connection, or
     * failing that the first connection at all, or {@code null} if none exist.
     */
    public Map<String, Object> getPrimaryConfig() {
        List<Map<String, Object>> enabled = arcade.query(
                "SELECT * FROM MailMonitorConfig WHERE tenantId = :tenantId AND enabled = true " +
                "ORDER BY createdAt LIMIT 1",
                Map.of("tenantId", tenantId()));
        if (!enabled.isEmpty()) return enabled.get(0);
        List<Map<String, Object>> any = arcade.query(
                "SELECT * FROM MailMonitorConfig WHERE tenantId = :tenantId ORDER BY createdAt LIMIT 1",
                Map.of("tenantId", tenantId()));
        return any.isEmpty() ? null : any.get(0);
    }

    /** True if another connection in this tenant already has this name (trimmed, case-insensitive). */
    public boolean nameTaken(String name, String excludeId) {
        String needle = name == null ? "" : name.trim().toLowerCase();
        for (Map<String, Object> row : listConfigs()) {
            String rowId = String.valueOf(row.get("id"));
            if (rowId.equals(excludeId)) continue;
            String rowName = String.valueOf(row.getOrDefault("name", "")).trim().toLowerCase();
            if (rowName.equals(needle)) return true;
        }
        return false;
    }

    /**
     * Create or update a mail connection. {@code id} blank → insert a new
     * connection; otherwise update the existing one (must belong to the current
     * tenant). {@code provider} ∈ {MS_GRAPH, GOOGLE, ZOHO, IMAP}. Graph fields
     * apply to MS_GRAPH; imap fields apply to the rest. {@code clientSecretRef}
     * (Graph) is preserved when null/blank on update.
     *
     * @throws IllegalStateException if {@code name} collides with another connection in this tenant
     * @return the connection id (new or existing)
     */
    public String saveConfig(String id, String name, String provider, String directoryId, String clientId,
                             String clientSecretRef, String graphBaseUrl, String imapHost, int imapPort,
                             boolean imapSsl, int defaultScanWindowDays, boolean enabled, String scheduleJson) {
        if (nameTaken(name, id)) {
            throw new IllegalStateException("A connection named \"" + name.trim() + "\" already exists.");
        }
        String tenantId = tenantId();
        String now = Instant.now().toString();
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenantId);
        p.put("name", name == null ? "" : name);
        p.put("provider", provider == null || provider.isBlank() ? "MS_GRAPH" : provider);
        p.put("directoryId", directoryId == null ? "" : directoryId);
        p.put("clientId", clientId == null ? "" : clientId);
        p.put("graphBaseUrl", graphBaseUrl == null ? "" : graphBaseUrl);
        p.put("imapHost", imapHost == null ? "" : imapHost);
        p.put("imapPort", imapPort);
        p.put("imapSsl", imapSsl);
        p.put("defaultScanWindowDays", defaultScanWindowDays);
        p.put("enabled", enabled);
        // "" = no custom schedule → the scheduler scans on every sweep (legacy).
        p.put("scheduleJson", scheduleJson == null ? "" : scheduleJson);
        p.put("updatedAt", now);

        Map<String, Object> existing = id == null || id.isBlank() ? null : getConfigById(id);
        if (existing != null) {
            p.put("id", id);
            String secretClause = "";
            if (clientSecretRef != null && !clientSecretRef.isBlank()) {
                p.put("clientSecretRef", clientSecretRef);
                secretClause = "clientSecretRef = :clientSecretRef, ";
            }
            arcade.commandNoResult(
                    "UPDATE MailMonitorConfig SET name = :name, provider = :provider, " +
                    "directoryId = :directoryId, clientId = :clientId, " + secretClause +
                    "graphBaseUrl = :graphBaseUrl, imapHost = :imapHost, imapPort = :imapPort, imapSsl = :imapSsl, " +
                    "defaultScanWindowDays = :defaultScanWindowDays, enabled = :enabled, " +
                    "scheduleJson = :scheduleJson, updatedAt = :updatedAt " +
                    "WHERE tenantId = :tenantId AND id = :id", p);
            return id;
        } else {
            String newId = "mc-" + UUID.randomUUID();
            p.put("id", newId);
            p.put("clientSecretRef", clientSecretRef == null ? "" : clientSecretRef);
            p.put("createdAt", now);
            arcade.commandNoResult(
                    "INSERT INTO MailMonitorConfig SET id = :id, tenantId = :tenantId, name = :name, " +
                    "provider = :provider, directoryId = :directoryId, clientId = :clientId, " +
                    "clientSecretRef = :clientSecretRef, graphBaseUrl = :graphBaseUrl, imapHost = :imapHost, " +
                    "imapPort = :imapPort, imapSsl = :imapSsl, defaultScanWindowDays = :defaultScanWindowDays, " +
                    "enabled = :enabled, scheduleJson = :scheduleJson, " +
                    "createdAt = :createdAt, updatedAt = :updatedAt", p);
            return newId;
        }
    }

    /**
     * Stamp the moment a scheduled (as opposed to manual) scan of this
     * connection completed — {@code ScheduleSpec.isDue} reads it back as the
     * "lastRun" side of its contract.
     */
    public void updateScheduledScanStamp(String connectionId) {
        arcade.commandNoResult(
                "UPDATE MailMonitorConfig SET lastScheduledScanAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", connectionId, "now", Instant.now().toString()));
    }

    /**
     * Delete a connection. Refuses (throws {@link IllegalStateException}) if any
     * mailbox is still assigned to it — callers must remove/reassign those first.
     */
    public void deleteConfig(String id) {
        if (!listMailboxesByConnection(id).isEmpty()) {
            throw new IllegalStateException(
                    "This connection still has monitored mailboxes assigned to it. " +
                    "Remove or reassign them before deleting the connection.");
        }
        arcade.commandNoResult(
                "DELETE FROM MailMonitorConfig WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id));
    }

    // ── Monitored mailboxes (many per tenant, each tied to one connection) ───

    public List<Map<String, Object>> listMailboxes() {
        return arcade.query(
                "SELECT * FROM MonitoredMailbox WHERE tenantId = :tenantId ORDER BY address",
                Map.of("tenantId", tenantId()));
    }

    /**
     * Distinct mailbox addresses configured for scan by a single user
     * ({@code ownerSub}), for self-service pickers. Returns empty for a blank
     * owner — never the tenant-wide ('' owner) mailboxes, so one user cannot see
     * another's configured scan addresses.
     */
    public List<String> listMailboxAddressesForOwner(String ownerSub) {
        if (ownerSub == null || ownerSub.isBlank()) return List.of();
        return arcade.query(
                "SELECT address FROM MonitoredMailbox WHERE tenantId = :tenantId AND ownerSub = :owner " +
                "ORDER BY address",
                Map.of("tenantId", tenantId(), "owner", ownerSub))
                .stream()
                .map(r -> String.valueOf(r.getOrDefault("address", "")))
                .filter(s -> !s.isBlank())
                .distinct()
                .toList();
    }

    public List<Map<String, Object>> listMailboxesByConnection(String connectionId) {
        return arcade.query(
                "SELECT * FROM MonitoredMailbox WHERE tenantId = :tenantId AND mailConnectionId = :cid " +
                "ORDER BY address",
                Map.of("tenantId", tenantId(), "cid", connectionId == null ? "" : connectionId));
    }

    public List<Map<String, Object>> listEnabledMailboxes() {
        return arcade.query(
                "SELECT * FROM MonitoredMailbox WHERE tenantId = :tenantId AND enabled = true",
                Map.of("tenantId", tenantId()));
    }

    /** Mailboxes whose mailConnectionId is blank or no longer matches any connection — need manual assignment. */
    public List<Map<String, Object>> listUnassignedMailboxes() {
        java.util.Set<String> knownIds = new java.util.HashSet<>();
        for (Map<String, Object> c : listConfigs()) knownIds.add(String.valueOf(c.get("id")));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map<String, Object> mb : listMailboxes()) {
            String cid = String.valueOf(mb.getOrDefault("mailConnectionId", ""));
            if (cid.isBlank() || !knownIds.contains(cid)) out.add(mb);
        }
        return out;
    }

    /** Reassign a mailbox to a different connection (or '' to unassign). Does not validate the target exists. */
    public void reassignMailboxConnection(String id, String mailConnectionId) {
        arcade.commandNoResult(
                "UPDATE MonitoredMailbox SET mailConnectionId = :cid WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id, "cid", mailConnectionId == null ? "" : mailConnectionId));
    }

    /**
     * Add a mailbox for a self-service flow with no connection picker — resolves
     * the tenant's {@link #getPrimaryConfig() primary connection} automatically
     * (blank if none configured yet, matching legacy behavior).
     */
    public String addMailbox(String address, String displayName, Integer scanWindowDays,
                             boolean scanMail, boolean scanCalendar, String createdBy,
                             String ownerSub, String ownerEmail) {
        Map<String, Object> primary = getPrimaryConfig();
        String connectionId = primary == null ? "" : String.valueOf(primary.get("id"));
        return addMailbox(address, displayName, scanWindowDays, scanMail, scanCalendar, createdBy,
                ownerSub, ownerEmail, connectionId);
    }

    /**
     * Add a monitored mailbox under an explicit connection. {@code ownerSub}
     * blank → tenant-wide (admin-added, visible to all). {@code ownerSub} set →
     * personal self-service mailbox, visible only to that user.
     */
    public String addMailbox(String address, String displayName, Integer scanWindowDays,
                             boolean scanMail, boolean scanCalendar, String createdBy,
                             String ownerSub, String ownerEmail, String mailConnectionId) {
        String id = "mb-" + UUID.randomUUID();
        Map<String, Object> p = new HashMap<>();
        p.put("id", id);
        p.put("tenantId", tenantId());
        p.put("mailConnectionId", mailConnectionId == null ? "" : mailConnectionId);
        p.put("address", address);
        p.put("displayName", displayName == null ? "" : displayName);
        p.put("scanWindowDays", scanWindowDays == null ? 0 : scanWindowDays); // 0 → fall back to config default
        p.put("scanMail", scanMail);
        p.put("scanCalendar", scanCalendar);
        p.put("ownerSub", ownerSub == null ? "" : ownerSub);
        p.put("ownerEmail", ownerEmail == null ? "" : ownerEmail);
        p.put("createdBy", createdBy == null ? "" : createdBy);
        p.put("createdAt", Instant.now().toString());
        arcade.commandNoResult(
                "INSERT INTO MonitoredMailbox SET id = :id, tenantId = :tenantId, " +
                "mailConnectionId = :mailConnectionId, address = :address, " +
                "displayName = :displayName, scanWindowDays = :scanWindowDays, scanMail = :scanMail, " +
                "scanCalendar = :scanCalendar, secretRef = '', ownerSub = :ownerSub, ownerEmail = :ownerEmail, " +
                "enabled = true, createdBy = :createdBy, createdAt = :createdAt", p);
        return id;
    }

    public Map<String, Object> findMailbox(String id) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM MonitoredMailbox WHERE tenantId = :tenantId AND id = :id LIMIT 1",
                Map.of("tenantId", tenantId(), "id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, Object> findByAddress(String address) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT * FROM MonitoredMailbox WHERE tenantId = :tenantId AND address = :address LIMIT 1",
                Map.of("tenantId", tenantId(), "address", address));
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Set the Infisical reference for a mailbox's IMAP app password. */
    public void setMailboxSecretRef(String id, String secretRef) {
        arcade.commandNoResult(
                "UPDATE MonitoredMailbox SET secretRef = :ref WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id, "ref", secretRef == null ? "" : secretRef));
    }

    public void deleteMailbox(String id) {
        arcade.commandNoResult(
                "DELETE FROM MonitoredMailbox WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id));
    }

    /** Mailbox-level scan opt-in/out — disabled mailboxes are skipped by every scan path. */
    public void setMailboxEnabled(String mailboxId, boolean enabled) {
        arcade.commandNoResult(
                "UPDATE MonitoredMailbox SET enabled = :enabled WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", mailboxId, "enabled", enabled));
    }

    public void updateScanStatus(String mailboxId, String status, String error) {
        arcade.commandNoResult(
                "UPDATE MonitoredMailbox SET lastScanAt = :now, lastScanStatus = :status, lastError = :error " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", mailboxId, "now", Instant.now().toString(),
                        "status", status == null ? "" : status, "error", error == null ? "" : error));
    }

    // ── Scan run audit ───────────────────────────────────────────────────────

    public String startRun(String mailboxId) {
        String id = "run-" + UUID.randomUUID();
        arcade.commandNoResult(
                "INSERT INTO MailScanRun SET id = :id, tenantId = :tenantId, mailboxId = :mailboxId, " +
                "startedAt = :now, status = 'RUNNING', itemsCreated = 0, itemsUpdated = 0, error = ''",
                Map.of("id", id, "tenantId", tenantId(), "mailboxId", mailboxId,
                        "now", Instant.now().toString()));
        return id;
    }

    public void finishRun(String runId, String status, int created, int updated, String error) {
        arcade.commandNoResult(
                "UPDATE MailScanRun SET finishedAt = :now, status = :status, itemsCreated = :created, " +
                "itemsUpdated = :updated, error = :error WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", runId, "now", Instant.now().toString(),
                        "status", status, "created", created, "updated", updated,
                        "error", error == null ? "" : error));
    }

    public List<Map<String, Object>> listRuns(int limit) {
        return arcade.query(
                "SELECT * FROM MailScanRun WHERE tenantId = :tenantId ORDER BY startedAt DESC LIMIT " + limit,
                Map.of("tenantId", tenantId()));
    }
}
