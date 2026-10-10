package io.orphos.eip.mailinsight.repo;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mailinsight.arcade.MailArcadeClient;
import io.orphos.eip.mailinsight.security.CurrentUser;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Per-user action overlay for live data-source items (incidents, alerts, SLA
 * breaches, etc.). These items aren't MailInsight rows — they belong to their
 * source systems — so a user's "done / ignore / deprioritize" is recorded here
 * and applied when the data-source items are merged into the read views.
 *
 * Keyed by (tenantId, ownerSub, itemRef) where itemRef is the synthetic id
 * such as "ds-alert-123".
 */
@Repository
public class OverrideRepository {

    // Only these columns may be written (column name is interpolated into SQL).
    private static final Set<String> ALLOWED =
            Set.of("status", "priorityOverride", "criticalityOverride", "reminderAt");

    private final MailArcadeClient arcade;

    public OverrideRepository(MailArcadeClient arcade) {
        this.arcade = arcade;
    }

    private static String tenantId() {
        return TenantContextHolder.requireTenantId().toString();
    }

    private static String ownerSub() {
        return CurrentUser.sub().orElse("");
    }

    /** Set one override field for a data-source item, creating the row if needed. */
    public void setField(String itemRef, String column, String value) {
        if (!ALLOWED.contains(column)) {
            throw new IllegalArgumentException("Unsupported override field: " + column);
        }
        ensureRow(itemRef);
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenantId());
        p.put("ownerSub", ownerSub());
        p.put("itemRef", itemRef);
        p.put("v", value == null ? "" : value);
        p.put("now", Instant.now().toString());
        arcade.commandNoResult(
                "UPDATE MailItemOverride SET " + column + " = :v, updatedAt = :now " +
                "WHERE tenantId = :tenantId AND ownerSub = :ownerSub AND itemRef = :itemRef", p);
    }

    /**
     * Set the status override, stamping {@code completedAt} when it becomes DONE and
     * clearing it otherwise — so "completed today" survives later edits to other fields.
     */
    public void setStatus(String itemRef, String status) {
        ensureRow(itemRef);
        String now = Instant.now().toString();
        Map<String, Object> p = new HashMap<>();
        p.put("tenantId", tenantId());
        p.put("ownerSub", ownerSub());
        p.put("itemRef", itemRef);
        p.put("v", status == null ? "" : status);
        p.put("completedAt", "DONE".equals(status) ? now : "");
        p.put("now", now);
        arcade.commandNoResult(
                "UPDATE MailItemOverride SET status = :v, completedAt = :completedAt, updatedAt = :now " +
                "WHERE tenantId = :tenantId AND ownerSub = :ownerSub AND itemRef = :itemRef", p);
    }

    private void ensureRow(String itemRef) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT id FROM MailItemOverride WHERE tenantId = :tenantId AND ownerSub = :ownerSub " +
                "AND itemRef = :itemRef LIMIT 1",
                Map.of("tenantId", tenantId(), "ownerSub", ownerSub(), "itemRef", itemRef));
        if (!rows.isEmpty()) return;
        arcade.commandNoResult(
                "INSERT INTO MailItemOverride SET id = :id, tenantId = :tenantId, ownerSub = :ownerSub, " +
                "itemRef = :itemRef, status = '', priorityOverride = '', criticalityOverride = '', " +
                "reminderAt = '', updatedAt = :now",
                Map.of("id", "ov-" + UUID.randomUUID(), "tenantId", tenantId(), "ownerSub", ownerSub(),
                        "itemRef", itemRef, "now", Instant.now().toString()));
    }

    // ── Scheduler-facing (tenant-scoped, all users — no current-user context) ──

    /** Overrides for the tenant whose reminder is due (used by the scheduler). */
    public List<Map<String, Object>> findDueRemindersForTenant(String nowIso) {
        return arcade.query(
                "SELECT * FROM MailItemOverride WHERE tenantId = :tenantId " +
                "AND reminderAt IS NOT NULL AND reminderAt <> '' AND reminderAt <= :now",
                Map.of("tenantId", tenantId(), "now", nowIso));
    }

    public void clearReminderById(String id) {
        arcade.commandNoResult(
                "UPDATE MailItemOverride SET reminderAt = '', updatedAt = :now WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id, "now", Instant.now().toString()));
    }

    public void deleteById(String id) {
        arcade.commandNoResult(
                "DELETE FROM MailItemOverride WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenantId(), "id", id));
    }

    /** All overrides for the current user, keyed by itemRef. */
    public Map<String, Map<String, Object>> findForUser() {
        Map<String, Map<String, Object>> out = new HashMap<>();
        try {
            List<Map<String, Object>> rows = arcade.query(
                    "SELECT * FROM MailItemOverride WHERE tenantId = :tenantId AND ownerSub = :ownerSub",
                    Map.of("tenantId", tenantId(), "ownerSub", ownerSub()));
            for (Map<String, Object> r : rows) {
                Object ref = r.get("itemRef");
                if (ref != null) out.put(ref.toString(), r);
            }
        } catch (Exception ignored) {
            // Type may not exist yet on a cold DB — treat as no overrides.
        }
        return out;
    }
}
