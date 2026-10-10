package io.orphos.eip.admin.access;

import io.orphos.eip.admin.access.repo.AccessControlRepository;
import io.orphos.eip.common.access.AccessEnforcementFilter;
import io.orphos.eip.common.access.AccessGrantSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * {@link AccessGrantSource} for platform-core, which OWNS the RBAC store: reads
 * the current tenant's grants straight from ArcadeDB (no HTTP round-trip).
 */
@Component
public class PlatformCoreAccessGrantSource implements AccessGrantSource {

    private static final Logger log = LogManager.getLogger(PlatformCoreAccessGrantSource.class);

    private final AccessControlRepository repo;

    public PlatformCoreAccessGrantSource(AccessControlRepository repo) {
        this.repo = repo;
    }

    @Override
    public Map<String, Map<String, String>> grantsForCurrentTenant() {
        try {
            return AccessEnforcementFilter.toGrantMap(repo.listGrants());
        } catch (Exception e) {
            // Store unreachable → signal "unavailable" (null) so the filter applies
            // its configured fail mode rather than treating it as "no grants".
            log.warn("Could not read access grants from ArcadeDB: {}", e.getMessage());
            return null;
        }
    }
}
