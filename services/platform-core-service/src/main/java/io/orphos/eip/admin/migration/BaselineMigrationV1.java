package io.orphos.eip.admin.migration;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.arcade.SchemaBootstrap;
import org.springframework.stereotype.Component;

/**
 * Baseline migration — version 1. Reproduces the schema applied by
 * {@link SchemaBootstrap}. Because both this migration and SchemaBootstrap use
 * {@code CREATE … IF NOT EXISTS}, running both against the same DB is safe;
 * the migration's role is to mark "the baseline is present" in the
 * SchemaMigration table so future evolutions know where they start from.
 *
 * <p>Future schemas should NOT modify this migration. Add a new
 * {@code MigrationVxxx_…} class with a higher version number; the registry
 * picks it up automatically and {@link MigrationRunner} will apply it to each
 * tenant DB on demand.</p>
 */
@Component
public class BaselineMigrationV1 implements TenantMigration {

    private final SchemaBootstrap schemaBootstrap;

    public BaselineMigrationV1(SchemaBootstrap schemaBootstrap) {
        this.schemaBootstrap = schemaBootstrap;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public String description() {
        return "Baseline schema (admin doctypes + tenantId indexes)";
    }

    @Override
    public void up(ArcadeDbTemplate arcade, String databaseName) {
        // Delegating to SchemaBootstrap keeps the baseline DDL in one place so
        // SchemaBootstrap (called on the default DB at startup) and this
        // migration (called per-tenant-DB) never drift. Use the template the
        // runner hands us so the DDL lands on the tenant's chosen ArcadeDB
        // server (not the platform default).
        schemaBootstrap.applyTo(arcade, databaseName);
    }
}
