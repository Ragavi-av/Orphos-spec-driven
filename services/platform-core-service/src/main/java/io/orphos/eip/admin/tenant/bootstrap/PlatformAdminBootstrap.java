package io.orphos.eip.admin.tenant.bootstrap;

import io.orphos.eip.admin.arcade.SchemaBootstrap;
import io.orphos.eip.admin.tenant.keycloak.KeycloakProvisioningClient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Promotes the setup wizard's first-tenant administrator to a cross-tenant
 * platform admin.
 *
 * <p>{@code platform_admin} is issued only by the separate {@code platform}
 * Keycloak realm (see {@code config/keycloak/platform-realm.json}); a user must
 * hold it <em>in that realm</em> to reach the cross-tenant experience the SPA
 * exposes at {@code /platform/}. So when the operator supplied admin credentials
 * in the wizard, this runner seeds the SAME email + password into the platform
 * realm with the {@code platform_admin} role — giving that one identity both
 * tenant-admin rights (via {@link DemoTenantBootstrap}, in the tenant realm) and
 * platform-admin rights here.</p>
 *
 * <p>No-op when the wizard credentials are blank: the platform realm keeps its
 * bootstrap-grade {@code pa}/{@code pa} user imported from the realm JSON.
 * Keycloak (or the platform realm) being unreachable is downgraded to a warning
 * so it never wedges startup — the tenant path does not depend on it.</p>
 *
 * <p>Runs AFTER {@link TenantAdminUserBackfill} so all tenant-realm work has
 * completed first.</p>
 */
@Component
@Order(SchemaBootstrap.ORDER_DEMO_TENANT + 60)
public class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LogManager.getLogger(PlatformAdminBootstrap.class);

    private final KeycloakProvisioningClient keycloak;

    @Value("${eip.platform.realm:platform}")
    private String platformRealm;

    @Value("${eip.tenant.demo.admin-email:}")
    private String adminEmail;

    @Value("${eip.tenant.demo.admin-password:}")
    private String adminPassword;

    public PlatformAdminBootstrap(KeycloakProvisioningClient keycloak) {
        this.keycloak = keycloak;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (adminEmail == null || adminEmail.isBlank()
                || adminPassword == null || adminPassword.isBlank()) {
            log.info("No first-tenant admin credentials configured — leaving the platform realm's "
                    + "bootstrap platform admin (pa/pa) in place.");
            return;
        }
        try {
            keycloak.ensureRealmAdminUser(platformRealm, "Platform Admin",
                    adminEmail, adminPassword, "platform_admin");
            log.info("Ensured platform admin '{}' with platform_admin role in realm '{}'",
                    adminEmail, platformRealm);
        } catch (Exception e) {
            log.warn("Could not seed platform admin in realm '{}': {} — the cross-tenant "
                    + "platform login falls back to the bootstrap pa/pa user.",
                    platformRealm, e.getMessage());
        }
    }
}
