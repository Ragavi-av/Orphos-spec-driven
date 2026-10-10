package io.orphos.eip.admin.tenant.service;

import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.model.TenantStatus;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import io.orphos.eip.common.tenant.ActiveTenantsProvider;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * admin-service override of {@link ActiveTenantsProvider}. Reads live from the
 * {@code Tenant} document type — newly provisioned tenants are visible to any
 * background job inside admin-service on the next scheduled tick.
 *
 * <p>Marked {@code @Primary} so it wins over the polling default registered
 * by {@code TenantAutoConfiguration} (which would try to call admin-service
 * from inside admin-service — wasteful even if it worked).</p>
 */
@Service
@Primary
public class DbBackedActiveTenantsProvider implements ActiveTenantsProvider {

    private final TenantRepository tenants;

    public DbBackedActiveTenantsProvider(TenantRepository tenants) {
        this.tenants = tenants;
    }

    @Override
    public Set<UUID> activeTenantIds() {
        Set<UUID> out = new LinkedHashSet<>();
        for (Tenant t : tenants.listActive()) {
            if (t.status() == TenantStatus.ACTIVE) out.add(t.id());
        }
        return out;
    }
}
