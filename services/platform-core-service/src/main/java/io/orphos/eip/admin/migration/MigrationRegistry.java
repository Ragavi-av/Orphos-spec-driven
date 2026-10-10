package io.orphos.eip.admin.migration;

import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Spring-collected, version-ordered list of every {@link TenantMigration} bean
 * on the classpath. Defining a new migration is therefore a single-file change:
 * implement {@code TenantMigration} and annotate {@code @Component}; the
 * registry will pick it up automatically.
 */
@Component
public class MigrationRegistry {

    private final List<TenantMigration> ordered;

    public MigrationRegistry(List<TenantMigration> migrations) {
        // Defensive copy + sort so callers can't reorder by mutating the bean
        // list (and so an out-of-order @Order on a migration bean doesn't change
        // application semantics — version() is authoritative).
        this.ordered = migrations.stream()
                .sorted(Comparator.comparingInt(TenantMigration::version))
                .toList();
        validateUniqueVersions();
    }

    public List<TenantMigration> all() {
        return ordered;
    }

    public int latestVersion() {
        return ordered.isEmpty() ? 0 : ordered.get(ordered.size() - 1).version();
    }

    private void validateUniqueVersions() {
        for (int i = 1; i < ordered.size(); i++) {
            if (ordered.get(i).version() == ordered.get(i - 1).version()) {
                throw new IllegalStateException(
                        "Duplicate TenantMigration version " + ordered.get(i).version()
                                + " — " + ordered.get(i - 1).getClass().getSimpleName()
                                + " and " + ordered.get(i).getClass().getSimpleName());
            }
        }
    }
}
