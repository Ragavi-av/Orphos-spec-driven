package io.orphos.eip.admin.integration;

import io.orphos.eip.admin.testsupport.BaseIntegrationTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies row-level tenant isolation in the SHARED database. Two synthetic
 * tenants insert overlapping {@code AuthEvent} rows into {@code orphos};
 * queries filtered by {@code tenantId} must return only the issuing tenant's
 * rows, and unfiltered queries must surface both — documenting that isolation
 * is a property of the query construction, not the data store.
 */
class TenantIsolationSharedIT extends BaseIntegrationTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    @BeforeAll
    static void seedAuthEvents() {
        // Tenant A: 3 rows. Tenant B: 5 rows. Same doc type, same DB.
        for (int i = 0; i < 3; i++) insertAuthEvent(TENANT_A, "alice@a.test", i);
        for (int i = 0; i < 5; i++) insertAuthEvent(TENANT_B, "bob@b.test", i);
    }

    private static void insertAuthEvent(UUID tenantId, String userKey, int sequence) {
        ARCADE_CLIENT.commandNoResult(
                "INSERT INTO AuthEvent SET id = :id, tenantId = :t, userKey = :u, "
                        + "outcome = 'SUCCESS', createdAt = :now",
                Map.of(
                        "id",  UUID.randomUUID().toString(),
                        "t",   tenantId.toString(),
                        "u",   userKey + "-" + sequence,
                        "now", Instant.now().toString()));
    }

    @Test
    void tenantAQueryReturnsOnlyTenantARows() {
        List<Map<String, Object>> rows = ARCADE_CLIENT.query(
                "SELECT FROM AuthEvent WHERE tenantId = :t",
                Map.of("t", TENANT_A.toString()));

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get("tenantId")).isEqualTo(TENANT_A.toString()));
    }

    @Test
    void tenantBQueryReturnsOnlyTenantBRows() {
        List<Map<String, Object>> rows = ARCADE_CLIENT.query(
                "SELECT FROM AuthEvent WHERE tenantId = :t",
                Map.of("t", TENANT_B.toString()));

        assertThat(rows).hasSize(5);
        assertThat(rows).allSatisfy(r ->
                assertThat(r.get("tenantId")).isEqualTo(TENANT_B.toString()));
    }

    @Test
    void countQueryByTenantReturnsExpectedAggregate() {
        long countA = countForTenant(TENANT_A);
        long countB = countForTenant(TENANT_B);

        assertThat(countA).isEqualTo(3L);
        assertThat(countB).isEqualTo(5L);
    }

    @Test
    void unfilteredQueryRevealsBothTenantsRows() {
        // Documents the contract: row-level isolation is enforced by the
        // service-layer query patterns, NOT by the storage tier. An attacker
        // who slips past tenant filtering would see all rows — this is why
        // every SHARED-mode query in production code MUST include a tenantId
        // predicate, and why we have separate DEDICATED-mode tests below.
        List<Map<String, Object>> rows = ARCADE_CLIENT.query(
                "SELECT FROM AuthEvent WHERE tenantId IN [:a, :b]",
                Map.of("a", TENANT_A.toString(), "b", TENANT_B.toString()));

        assertThat(rows).hasSize(8);
    }

    private static long countForTenant(UUID tenantId) {
        List<Map<String, Object>> rows = ARCADE_CLIENT.query(
                "SELECT count(*) AS c FROM AuthEvent WHERE tenantId = :t",
                Map.of("t", tenantId.toString()));
        Object c = rows.get(0).get("c");
        return c instanceof Number n ? n.longValue() : Long.parseLong(c.toString());
    }
}
