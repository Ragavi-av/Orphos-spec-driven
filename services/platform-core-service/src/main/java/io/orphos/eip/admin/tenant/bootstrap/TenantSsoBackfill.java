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
 * On every boot, seeds the platform's default SSO identity provider into every
 * active tenant's Keycloak realm — retrofitting realms created by an older
 * admin-service build that provisioned local login only. Without this, an
 * existing tenant would never get a "Sign in with Microsoft" button even after
 * the platform SSO credentials are configured.
 *
 * <p>{@link KeycloakProvisioningClient#seedDefaultSso(String)} is idempotent and
 * non-fatal: it no-ops when SSO is unconfigured or the realm already carries the
 * IdP (e.g. the demo {@code eip} realm), and swallows errors so a discovery
 * hiccup or unreachable Keycloak never blocks startup.</p>
 *
 * <p>Runs AFTER {@link TenantAdminUserBackfill} so the admin user is affirmed
 * before SSO is layered on.</p>
 */
@Component
@Order(SchemaBootstrap.ORDER_DEMO_TENANT + 60)
public class TenantSsoBackfill implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(TenantSsoBackfill.class);

    private final TenantRepository tenants;
    private final KeycloakProvisioningClient keycloak;

    public TenantSsoBackfill(TenantRepository tenants, KeycloakProvisioningClient keycloak) {
        this.tenants = tenants;
        this.keycloak = keycloak;
    }

    @Override
    public void run(ApplicationArguments args) {
        int seeded = 0;
        for (Tenant t : tenants.listActive()) {
            String realm = t.keycloakRealm();
            if (realm == null || realm.isBlank()) continue;
            try {
                // Backfill standard profile/email scopes (older realms lacked them,
                // so tokens omitted name/email — SPA showed a "User" placeholder).
                keycloak.ensureStandardClientScopes(realm);
                keycloak.seedDefaultSso(realm);
                seeded++;
            } catch (Exception e) {
                log.warn("Could not backfill auth scopes/SSO in realm '{}' (tenant {}): {}",
                        realm, t.id(), e.getMessage());
            }
        }
        if (seeded > 0) {
            log.info("Tenant SSO backfill: processed {} realm(s)", seeded);
        }
    }
}
