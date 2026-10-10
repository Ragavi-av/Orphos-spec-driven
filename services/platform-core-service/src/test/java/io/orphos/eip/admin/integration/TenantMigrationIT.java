package io.orphos.eip.admin.integration;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.migration.BaselineMigrationV1;
import io.orphos.eip.admin.migration.MigrationRegistry;
import io.orphos.eip.admin.migration.MigrationRunner;
import io.orphos.eip.admin.migration.MigrationRunner.MigrationRunResult;
import io.orphos.eip.admin.migration.MigrationRunner.MigrationStatus;
import io.orphos.eip.admin.migration.TenantMigration;
import io.orphos.eip.admin.testsupport.BaseIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the {@link MigrationRunner} against a fresh tenant database:
 * baseline application, idempotent re-runs, and failure handling.
 *
 * <p>Constructs the runner with a hand-built {@link MigrationRegistry} so the
 * test can inject a deliberately-failing migration without affecting the rest
 * of the suite. The registry's constructor sorts migrations and rejects
 * duplicate versions, so test-only migrations use non-baseline versions.</p>
 */
class TenantMigrationIT extends BaseIntegrationTest {

    private String tenantDb;

    @BeforeEach
    void provisionFreshDb() {
        tenantDb = newDedicatedDbName();
        createDatabase(tenantDb);
        // Bootstrap baseline schema so the SchemaMigration doc type exists; the
        // baseline migration's up() is still idempotent here.
        SCHEMA_BOOTSTRAP.applyTo(tenantDb);
    }

    @AfterEach
    void cleanup() {
        dropDatabase(tenantDb);
    }

    @Test
    void baselineMigrationAppliesAndRecordsRow() {
        MigrationRunner runner = runnerWith(new BaselineMigrationV1(SCHEMA_BOOTSTRAP));

        MigrationRunResult result = runner.runPending(tenantDb);

        assertThat(result.attempted()).isEqualTo(1);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        List<Map<String, Object>> rows = ARCADE_CLIENT.queryOn(tenantDb,
                "SELECT FROM SchemaMigration WHERE version = 1", Map.of());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo(MigrationRunner.STATUS_APPLIED);
    }

    @Test
    void runPendingIsIdempotent() {
        MigrationRunner runner = runnerWith(new BaselineMigrationV1(SCHEMA_BOOTSTRAP));

        runner.runPending(tenantDb);
        MigrationRunResult second = runner.runPending(tenantDb);

        assertThat(second.attempted()).isZero();
        assertThat(second.succeeded()).isZero();
        assertThat(second.failed()).isZero();

        // SchemaMigration table still has exactly one row, not two.
        long applied = countAppliedRows();
        assertThat(applied).isEqualTo(1L);
    }

    @Test
    void statusOfReturnsAppliedAndPending() {
        MigrationRunner runner = runnerWith(
                new BaselineMigrationV1(SCHEMA_BOOTSTRAP),
                new InertMigration(2, "Future migration"));

        // Apply only the baseline by running once before adding the v2 migration to history.
        runnerWith(new BaselineMigrationV1(SCHEMA_BOOTSTRAP)).runPending(tenantDb);

        List<MigrationStatus> statuses = runner.statusOf(tenantDb);

        assertThat(statuses).hasSize(2);
        assertThat(statuses.get(0).version()).isEqualTo(1);
        assertThat(statuses.get(0).status()).isEqualTo(MigrationRunner.STATUS_APPLIED);
        assertThat(statuses.get(1).version()).isEqualTo(2);
        assertThat(statuses.get(1).status()).isEqualTo("PENDING");
    }

    @Test
    void failedMigrationIsRecordedAndHaltsLaterOnes() {
        MigrationRunner runner = runnerWith(
                new BaselineMigrationV1(SCHEMA_BOOTSTRAP),
                new FailingMigration(2),
                new InertMigration(3, "Should not run"));

        MigrationRunResult result = runner.runPending(tenantDb);

        assertThat(result.attempted()).isEqualTo(2);
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);

        List<Map<String, Object>> v2Rows = ARCADE_CLIENT.queryOn(tenantDb,
                "SELECT FROM SchemaMigration WHERE version = 2", Map.of());
        assertThat(v2Rows).hasSize(1);
        assertThat(v2Rows.get(0).get("status")).isEqualTo(MigrationRunner.STATUS_FAILED);

        List<Map<String, Object>> v3Rows = ARCADE_CLIENT.queryOn(tenantDb,
                "SELECT FROM SchemaMigration WHERE version = 3", Map.of());
        assertThat(v3Rows).isEmpty();
    }

    @Test
    void retryAfterFailureReplacesPriorFailedRow() {
        MigrationRunner failingFirst = runnerWith(
                new BaselineMigrationV1(SCHEMA_BOOTSTRAP),
                new FailingMigration(2));
        failingFirst.runPending(tenantDb);

        // Swap the failing migration for a working one and retry. The runner
        // must delete the previous FAILED row and replace it with APPLIED, not
        // accumulate parallel history rows.
        MigrationRunner fixed = runnerWith(
                new BaselineMigrationV1(SCHEMA_BOOTSTRAP),
                new InertMigration(2, "Now working"));
        MigrationRunResult result = fixed.runPending(tenantDb);

        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        List<Map<String, Object>> v2Rows = ARCADE_CLIENT.queryOn(tenantDb,
                "SELECT FROM SchemaMigration WHERE version = 2", Map.of());
        assertThat(v2Rows).hasSize(1);
        assertThat(v2Rows.get(0).get("status")).isEqualTo(MigrationRunner.STATUS_APPLIED);
    }

    private long countAppliedRows() {
        List<Map<String, Object>> rows = ARCADE_CLIENT.queryOn(tenantDb,
                "SELECT count(*) AS c FROM SchemaMigration WHERE status = '"
                        + MigrationRunner.STATUS_APPLIED + "'",
                Map.of());
        Object c = rows.get(0).get("c");
        return c instanceof Number n ? n.longValue() : Long.parseLong(c.toString());
    }

    private static MigrationRunner runnerWith(TenantMigration... migrations) {
        return new MigrationRunner(ARCADE_CLIENT, new MigrationRegistry(List.of(migrations)), null);
    }

    // ── Test-only migrations ─────────────────────────────────────────────

    /** A migration whose {@code up} is a no-op — used to fill out the registry. */
    private record InertMigration(int v, String desc) implements TenantMigration {
        @Override public int version() { return v; }
        @Override public String description() { return desc; }
        @Override public void up(ArcadeDbTemplate arcade, String databaseName) { /* no-op */ }
    }

    /** Always throws when {@code up} runs — used to exercise the failure path. */
    private record FailingMigration(int v) implements TenantMigration {
        @Override public int version() { return v; }
        @Override public String description() { return "Intentionally fails (v" + v + ")"; }
        @Override public void up(ArcadeDbTemplate arcade, String databaseName) {
            throw new RuntimeException("simulated migration failure");
        }
    }
}
