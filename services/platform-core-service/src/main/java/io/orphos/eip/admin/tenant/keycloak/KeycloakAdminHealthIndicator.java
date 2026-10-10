package io.orphos.eip.admin.tenant.keycloak;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces whether {@code platform-core} can authenticate to Keycloak as the
 * master admin — the credential that per-tenant realm provisioning depends on.
 *
 * <p>Contributes to {@code /actuator/health} with three deliberately-chosen
 * outcomes so the container healthcheck reflects a real misconfiguration without
 * flapping during the normal boot-order race:</p>
 * <ul>
 *   <li><b>UP</b> — a master admin token was obtained (provisioning will work).</li>
 *   <li><b>UP</b> (status detail {@code pending}) — the admin password is not
 *       seeded yet, or Keycloak is not reachable yet. This is the transient
 *       window while {@code infisical-seed}/Keycloak finish coming up; reporting
 *       UP here keeps platform-core healthy so the app layer isn't wedged, and
 *       {@link KeycloakProvisioningClient}'s re-auth path recovers provisioning
 *       once the secret lands.</li>
 *   <li><b>UP</b> (status detail {@code auth-failed}) — the password IS present but
 *       Keycloak rejected it (401). This is surfaced as a health <em>detail</em>,
 *       NOT a DOWN: tenant provisioning will fail until the credentials match, but
 *       a Keycloak-admin problem must not take down the rest of the app layer
 *       (every service depends on {@code platform-core: service_healthy}). The
 *       tenant-creation 401 surfaces to the operator in the Create-tenant dialog,
 *       which is the right place to see and fix it.</li>
 * </ul>
 *
 * <p>By design this indicator NEVER reports DOWN — it is diagnostic only, so it
 * cannot wedge the whole platform on a credential mismatch.</p>
 */
@Component
public class KeycloakAdminHealthIndicator implements HealthIndicator {

    private final KeycloakProvisioningClient keycloak;

    public KeycloakAdminHealthIndicator(KeycloakProvisioningClient keycloak) {
        this.keycloak = keycloak;
    }

    @Override
    public Health health() {
        KeycloakProvisioningClient.AdminAuthProbe probe = keycloak.probeAdminAuth();
        return switch (probe.state()) {
            case READY -> Health.up()
                    .withDetail("keycloakAdmin", "ready")
                    .withDetail("detail", probe.detail())
                    .build();
            case PENDING -> Health.up()
                    .withDetail("keycloakAdmin", "pending")
                    .withDetail("detail", probe.detail())
                    .build();
            // NOT down: a wrong admin password must not block the app layer.
            // Surfaced as a detail; tenant provisioning still errors clearly at
            // create time until KEYCLOAK_ADMIN_PASSWORD matches Keycloak.
            case MISCONFIGURED -> Health.up()
                    .withDetail("keycloakAdmin", "auth-failed")
                    .withDetail("detail", probe.detail())
                    .withDetail("action", "reconcile KEYCLOAK_ADMIN_PASSWORD with Keycloak (reset volumes or restore the initialized value)")
                    .build();
        };
    }
}
