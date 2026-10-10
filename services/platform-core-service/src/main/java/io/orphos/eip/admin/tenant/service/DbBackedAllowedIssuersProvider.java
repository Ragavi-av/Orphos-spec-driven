package io.orphos.eip.admin.tenant.service;

import io.orphos.eip.admin.tenant.model.Tenant;
import io.orphos.eip.admin.tenant.repo.TenantRepository;
import io.orphos.eip.common.security.AllowedIssuersProvider;
import io.orphos.eip.common.security.SecurityProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * admin-service override of {@link AllowedIssuersProvider}. Computes the
 * accepted-issuer set live from the {@code Tenant} doc type so newly
 * provisioned tenants are trusted without restarting admin-service.
 *
 * <p>Each active tenant contributes {@code keycloakIssuerBase/realms/{realm}}.
 * The platform realm (always trusted, even when no tenants exist) is also
 * included so platform-superadmins can sign in to bootstrap the first tenant.</p>
 *
 * <p>Marked {@code @Primary} so it wins over the default
 * {@link io.orphos.eip.common.security.ConfigBackedAllowedIssuersProvider}
 * registered by {@link io.orphos.eip.common.security.MultiIssuerJwtAutoConfiguration}.</p>
 */
@Service
@Primary
public class DbBackedAllowedIssuersProvider implements AllowedIssuersProvider {

    private final TenantRepository tenants;
    private final SecurityProperties securityProps;

    /**
     * Externally-resolvable base URL for issued JWTs. JWTs carry this as
     * {@code iss}, not the internal Docker hostname. Defaults to the demo
     * setup ({@code http://localhost:8443}).
     */
    @Value("${eip.security.keycloak-issuer-base:${KEYCLOAK_ISSUER_BASE:http://localhost:8443}}")
    private String issuerBase;

    public DbBackedAllowedIssuersProvider(TenantRepository tenants, SecurityProperties securityProps) {
        this.tenants = tenants;
        this.securityProps = securityProps;
    }

    @Override
    public Set<String> allowedIssuers() {
        Set<String> set = new LinkedHashSet<>();

        for (Tenant t : tenants.listActive()) {
            if (t.keycloakRealm() != null && !t.keycloakRealm().isBlank()) {
                set.add(buildIssuer(t.keycloakRealm()));
            }
        }

        String platform = securityProps.getPlatformIssuer();
        if (platform != null && !platform.isBlank()) {
            set.add(platform);
        }

        // Belt-and-braces: also honour the static list. Useful for dev when
        // someone wants to allow an issuer that isn't in the DB yet.
        if (securityProps.getAllowedIssuers() != null) {
            for (String iss : securityProps.getAllowedIssuers()) {
                if (iss != null && !iss.isBlank()) set.add(iss.trim());
            }
        }
        return set;
    }

    private String buildIssuer(String realm) {
        String base = issuerBase.endsWith("/") ? issuerBase.substring(0, issuerBase.length() - 1) : issuerBase;
        return base + "/realms/" + realm;
    }
}
