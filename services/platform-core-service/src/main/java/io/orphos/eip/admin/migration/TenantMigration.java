package io.orphos.eip.admin.migration;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;

/**
 * A single, idempotent schema/data migration applied to a tenant's database.
 *
 * <p>Each migration is a Spring bean — {@link MigrationRegistry} collects all of
 * them, orders by {@link #version()}, and {@link MigrationRunner} applies the
 * subset that has not yet been recorded in the target DB's
 * {@code SchemaMigration} table.</p>
 *
 * <p>Implementations MUST be idempotent: a migration may be re-run if a
 * previous attempt crashed before the {@code SchemaMigration} row was written.
 * Use {@code CREATE … IF NOT EXISTS}, guard data updates with WHERE clauses,
 * and avoid destructive operations. Down-migrations are explicitly out of
 * scope for slice 3.1.</p>
 */
public interface TenantMigration {

    /**
     * Monotonically increasing integer version. Migrations are applied in
     * ascending order. Version {@code 1} is reserved for the baseline schema;
     * future schema evolutions use {@code 2}, {@code 3}, …
     */
    int version();

    /** Human-readable description shown in the admin UI. */
    String description();

    /**
     * Apply the migration to {@code databaseName}. Must be idempotent.
     * Implementations should use {@link ArcadeDbTemplate#commandNoResultOn} and
     * {@link ArcadeDbTemplate#queryOn} so they target the correct database
     * regardless of the calling service's default-DB binding.
     */
    void up(ArcadeDbTemplate arcade, String databaseName);
}
