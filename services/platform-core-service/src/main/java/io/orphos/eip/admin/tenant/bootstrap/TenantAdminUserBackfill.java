package io.orphos.eip.admin.tenant.bootstrap;

import io.orphos.eip.admin.arcade.SchemaBootstrap;
import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * On every boot, re-affirms the bootstrap-grade {@code admin/admin} user in
 * every active tenant's Keycloak realm. Self-heals tenants whose realms were
 * created by an older admin-service build that did not yet seed the default
 * admin — without that, the SPA's login link for those tenants is unreachable
 * unless an operator manually creates a user in Keycloak. It also adds any built-in
 * role missing from an older realm ({@code KeycloakProvisioningClient#ensureBuiltInRealmRoles}).
 *
 * <p>Runs AFTER {@link DemoTenantBootstrap} so the demo tenant row is in place
 * before the iteration starts. Keycloak unreachable is downgraded to a warning
 * — admin-service must still start so the demo path (which can survive without
 * Keycloak) keeps working.</p>
 */
@Component
@Order(SchemaBootstrap.ORDER_DEMO_TENANT + 50)
public class TenantAdminUserBackfill implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(TenantAdminUserBackfill.class);

    private final TenantRepository tenants;
    private final KeycloakProvisioningClient keycloak;

    public TenantAdminUserBackfill(TenantRepository tenants, KeycloakProvisioningClient keycloak) {
        this.tenants = tenants;
        this.keycloak = keycloak;
    }

    @Override
    public void run(ApplicationArguments args) {
        int healed = 0;
        for (Tenant t : tenants.listActive()) {
            String realm = t.keycloakRealm();
            if (realm == null || realm.isBlank()) continue;
            try {
                keycloak.ensureDefaultAdminUser(realm, t.displayName());
                // Roles added to the catalog after this realm was provisioned (coo, chro).
                keycloak.ensureBuiltInRealmRoles(realm);
                healed++;
            } catch (Exception e) {
                log.warn("Could not affirm default admin in realm '{}' (tenant {}): {}",
                        realm, t.id(), e.getMessage());
            }
        }
        if (healed > 0) {
            log.info("Tenant admin backfill: affirmed default admin in {} realm(s)", healed);
        }
    }
}
