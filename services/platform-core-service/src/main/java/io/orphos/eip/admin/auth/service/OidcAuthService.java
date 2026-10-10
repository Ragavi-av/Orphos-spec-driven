package io.orphos.eip.admin.auth.service;

import io.orphos.eip.admin.auth.model.AuthEventType;
import io.orphos.eip.admin.auth.model.AuthMethodType;
import io.orphos.eip.admin.auth.repo.AuthRepository;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A2 - SSO with self-registration (OIDC).
 *
 * On first successful OIDC callback for an unknown user, we auto-provision a
 * binding so the user is recognised on subsequent logins. The actual OIDC
 * exchange is handled by Spring Security's resource-server filter (Keycloak
 * is the broker); this service just reconciles the resulting JWT with our
 * local UserAuthBinding records.
 */
@Service
public class OidcAuthService {

    private final AuthRepository repo;

    public OidcAuthService(AuthRepository repo) {
        this.repo = repo;
    }

    /**
     * Reconcile an incoming JWT-authenticated principal.
     *
     * @return the canonical userKey we want to use everywhere downstream.
     */
    public String reconcile(Jwt jwt, String providerName, String remoteIp) {
        String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new IllegalArgumentException("missing subject in token");
        }

        var provider = repo.findProviderByName(providerName);
        boolean selfRegistration = provider.map(p ->
                Boolean.TRUE.equals(p.get("allowSelfRegistration"))).orElse(false);
        @SuppressWarnings("unchecked")
        List<String> allowedDomains = provider.map(p ->
                (List<String>) p.getOrDefault("allowedEmailDomains", List.of())).orElse(List.of());

        Optional<Map<String, Object>> binding = repo.findBinding(sub);
        if (binding.isEmpty()) {
            if (!selfRegistration) {
                repo.recordEvent(sub, AuthEventType.LOGIN_FAILURE, AuthMethodType.OIDC,
                        false, "self_registration_disabled", remoteIp);
                throw new IllegalStateException("self-registration is disabled for this provider");
            }
            // Optional email-domain gate. We never log the email value itself.
            String email = jwt.getClaimAsString("email");
            if (!allowedDomains.isEmpty()) {
                String domain = email == null ? "" : email.substring(email.indexOf('@') + 1);
                if (!allowedDomains.contains(domain)) {
                    repo.recordEvent(sub, AuthEventType.LOGIN_FAILURE, AuthMethodType.OIDC,
                            false, "domain_not_allowed", remoteIp);
                    throw new IllegalStateException("email domain not permitted");
                }
            }
            repo.upsertBinding(sub, AuthMethodType.OIDC, providerName);
            repo.recordEvent(sub, AuthEventType.REGISTER, AuthMethodType.OIDC, true,
                    "oidc_self_registered", remoteIp);
        }

        repo.recordEvent(sub, AuthEventType.LOGIN_SUCCESS, AuthMethodType.OIDC, true,
                "ok", remoteIp);
        return sub;
    }
}
