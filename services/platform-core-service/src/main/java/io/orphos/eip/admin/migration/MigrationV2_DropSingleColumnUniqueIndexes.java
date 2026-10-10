package io.orphos.eip.admin.migration;

import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Drops the legacy single-column UNIQUE indexes that pre-date multi-tenancy.
 *
 * <p>Before multi-tenancy, indexes like {@code BrandingProfile (name) UNIQUE}
 * were correct because there was one tenant. With realm-per-tenant + row-level
 * tenantId stamping, that index actively prevents tenant B from saving a
 * profile named {@code "default"} if tenant A already has one — manifesting
 * as a confusing "duplicate key" error rather than as expected isolation.</p>
 *
 * <p>The compound replacements ({@code (tenantId, name) UNIQUE} and friends)
 * are created by the updated {@link io.orphos.eip.admin.arcade.SchemaBootstrap},
 * which {@link BaselineMigrationV1} delegates to — so this migration's
 * only job is to remove the legacy index. Idempotent: each DROP is wrapped
 * in a try/catch so reruns no-op if the legacy index is already gone.</p>
 */
@Component
public class MigrationV2_DropSingleColumnUniqueIndexes implements TenantMigration {

    private static final Logger log = LogManager.getLogger(MigrationV2_DropSingleColumnUniqueIndexes.class);

    /** Class + property pairs whose legacy single-column UNIQUE index must go. */
    private static final List<String[]> LEGACY_INDEXES = List.of(
            new String[]{"BrandingProfile",  "name"},
            new String[]{"Theme",            "name"},
            new String[]{"EmailTemplate",    "code"},
            new String[]{"EmailConfig",      "name"},
            new String[]{"LocalUser",        "email"},
            new String[]{"IdentityProvider", "name"}
    );

    @Override
    public int version() {
        return 2;
    }

    @Override
    public String description() {
        return "Drop legacy single-column UNIQUE indexes (replaced by compound (tenantId, *) UNIQUE)";
    }

    @Override
    public void up(ArcadeDbTemplate arcade, String databaseName) {
        for (String[] pair : LEGACY_INDEXES) {
            String clazz = pair[0];
            String prop  = pair[1];
            // ArcadeDB does not support DROP INDEX IF EXISTS uniformly across
            // versions; wrap in try/catch and accept that a missing index is a
            // benign no-op. The legacy index name follows the standard
            // {Class}[{property}] convention ArcadeDB generates for
            // single-property indexes created without an explicit name.
            String dropSql = "DROP INDEX `" + clazz + "[" + prop + "]`";
            try {
                arcade.commandNoResultOn(databaseName, dropSql, Map.of());
                log.info("Dropped legacy index {}[{}] on '{}'", clazz, prop, databaseName);
            } catch (Exception e) {
                log.debug("Legacy index {}[{}] not present on '{}' — skipping ({})",
                        clazz, prop, databaseName, e.getMessage());
            }
        }
    }
}
