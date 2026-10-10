package io.orphos.eip.admin.migration;

import io.orphos.eip.admin.arcade.SchemaBootstrap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Applies pending {@link TenantMigration}s against the default database on
 * service startup. Runs AFTER {@link SchemaBootstrap} (so the
 * {@code SchemaMigration} doc type is in place) and BEFORE
 * {@code DemoTenantBootstrap} (so the demo backfill sees the up-to-date
 * schema).
 *
 * <p>Per-tenant DEDICATED databases get their migrations run via
 * {@code TenantService.create}; this runner only covers the SHARED-mode default
 * DB on startup.</p>
 */
@Component
@Order(SchemaBootstrap.ORDER_SCHEMA + 50)
public class MigrationStartupRunner implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(MigrationStartupRunner.class);

    private final MigrationRunner runner;

    @Value("${arcadedb.database}")
    private String defaultDatabase;

    @Value("${eip.tenant.migration.run-on-startup:true}")
    private boolean enabled;

    public MigrationStartupRunner(MigrationRunner runner) {
        this.runner = runner;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            log.info("Tenant migrations disabled at startup (eip.tenant.migration.run-on-startup=false)");
            return;
        }
        MigrationRunner.MigrationRunResult result = runner.runPending(defaultDatabase);
        if (result.attempted() == 0) {
            log.info("Tenant migrations: no pending on '{}'", defaultDatabase);
        } else {
            log.info("Tenant migrations applied on '{}': {} attempted, {} succeeded, {} failed",
                    defaultDatabase, result.attempted(), result.succeeded(), result.failed());
        }
    }
}
