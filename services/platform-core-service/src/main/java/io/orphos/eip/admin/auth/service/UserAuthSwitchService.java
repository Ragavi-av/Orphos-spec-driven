package io.orphos.eip.admin.auth.service;

import io.orphos.eip.admin.auth.dto.Dtos;
import io.orphos.eip.admin.auth.model.AuthEventType;
import io.orphos.eip.admin.auth.model.AuthMethodType;
import io.orphos.eip.admin.auth.repo.AuthRepository;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * A5 - User authentication switching.
 *
 * Lets an admin (or, where policy allows, the user themselves) move a user
 * between authentication methods - e.g. from LOCAL to OIDC after enabling SSO.
 * Always writes both an AuthEvent (METHOD_SWITCHED) and an AuthAuditLog entry.
 */
@Service
public class UserAuthSwitchService {

    private final AuthRepository repo;

    public UserAuthSwitchService(AuthRepository repo) {
        this.repo = repo;
    }

    public void switchMethod(Dtos.SwitchMethodRequest req, String actorKey, String remoteIp) {
        if (req.target() == null) {
            throw new IllegalArgumentException("target method is required");
        }
        if (req.target() == AuthMethodType.OIDC || req.target() == AuthMethodType.SAML
                || req.target() == AuthMethodType.LDAP) {
            if (req.providerName() == null || req.providerName().isBlank()) {
                throw new IllegalArgumentException("providerName is required for SSO methods");
            }
            // Make sure the named provider is actually enabled before flipping a user onto it,
            // otherwise we strand them.
            var prov = repo.findProviderByName(req.providerName());
            if (prov.isEmpty() || !Boolean.TRUE.equals(prov.get().get("enabled"))) {
                throw new IllegalStateException("target provider not enabled");
            }
        }
        var existing = repo.findBinding(req.userKey());
        String from = existing.map(b -> b.getOrDefault("method", "LOCAL").toString()).orElse("NONE");

        repo.upsertBinding(req.userKey(), req.target(), req.providerName());
        repo.recordEvent(req.userKey(), AuthEventType.METHOD_SWITCHED, req.target(), true,
                "switched_from_" + from, remoteIp);
        repo.appendAudit(actorKey, "AUTH_METHOD_SWITCH", req.userKey(),
                Map.of("from", from, "to", req.target().name(),
                        "provider", req.providerName() == null ? "" : req.providerName()));
    }
}
