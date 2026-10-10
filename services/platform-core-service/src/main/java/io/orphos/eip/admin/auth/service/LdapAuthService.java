package io.orphos.eip.admin.auth.service;

import io.orphos.eip.admin.auth.dto.Dtos;
import io.orphos.eip.admin.auth.model.AuthEventType;
import io.orphos.eip.admin.auth.model.AuthMethodType;
import io.orphos.eip.admin.auth.repo.AuthRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;
import org.springframework.ldap.query.LdapQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * A3 (part 2) - Admin-managed SSO via direct LDAP / AD bind.
 *
 * <p>One LDAP server per configured IdentityProvider record. The provider's
 * {@code config} map carries: {@code url}, {@code base}, {@code userDnPattern},
 * {@code managerDn}, {@code managerPassword} (the manager password is a
 * reference to an OpenBao key in production - never raw in the document).</p>
 *
 * <p>Like SAML, an admin must pre-provision a UserAuthBinding for the username.</p>
 */
@Service
public class LdapAuthService {

    private static final Logger log = LogManager.getLogger(LdapAuthService.class);

    private final AuthRepository repo;
    private final AuthMonitoringService monitor;
    private final IdpSecretStore idpSecrets;

    public LdapAuthService(AuthRepository repo, AuthMonitoringService monitor,
                           IdpSecretStore idpSecrets) {
        this.repo = repo;
        this.monitor = monitor;
        this.idpSecrets = idpSecrets;
    }

    public Dtos.LoginResponse login(String providerName, String username, String password,
                                    String remoteIp) {
        Optional<Map<String, Object>> opt = repo.findProviderByName(providerName);
        if (opt.isEmpty() || !Boolean.TRUE.equals(opt.get().get("enabled"))) {
            throw new IllegalStateException("LDAP provider not enabled");
        }

        // Admin-managed: refuse unknown users before talking to LDAP at all.
        var binding = repo.findBinding(username);
        if (binding.isEmpty()) {
            repo.recordEvent(username, AuthEventType.LOGIN_FAILURE, AuthMethodType.LDAP,
                    false, "no_admin_provisioning", remoteIp);
            throw new IllegalStateException("user not provisioned for LDAP access");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> storedCfg = (Map<String, Object>) opt.get().getOrDefault("config", Map.of());
        // Pull any vault-backed secrets (e.g. managerPassword) into a config
        // copy we can hand to the LDAP client. Plain values pass through.
        Map<String, Object> cfg = idpSecrets.resolveAll(storedCfg);

        LdapTemplate template = buildTemplate(cfg, username, password);
        try {
            // Trigger a bind by issuing the cheapest possible query.
            template.search(
                    LdapQueryBuilder.query().base(str(cfg.get("base"))).where("objectclass").is("*"),
                    (org.springframework.ldap.core.AttributesMapper<Object>) a -> a);
        } catch (Exception e) {
            repo.recordEvent(username, AuthEventType.LOGIN_FAILURE, AuthMethodType.LDAP,
                    false, "ldap_bind_failed", remoteIp);
            monitor.onFailedLogin(username, remoteIp);
            log.debug("LDAP bind failed: {}", e.getMessage());
            throw new IllegalArgumentException("invalid credentials");
        }

        repo.recordEvent(username, AuthEventType.LOGIN_SUCCESS, AuthMethodType.LDAP, true,
                "ok", remoteIp);
        return new Dtos.LoginResponse(username, AuthMethodType.LDAP, java.time.Instant.now());
    }

    public void provision(String username, String providerName, String actorKey) {
        repo.upsertBinding(username, AuthMethodType.LDAP, providerName);
        repo.appendAudit(actorKey, "LDAP_PROVISION", username, Map.of("provider", providerName));
    }

    public void revoke(String username, String actorKey) {
        repo.upsertBinding(username, AuthMethodType.LDAP, "__revoked__");
        repo.appendAudit(actorKey, "LDAP_REVOKE", username, Map.of());
    }

    private static LdapTemplate buildTemplate(Map<String, Object> cfg,
                                              String username, String password) {
        LdapContextSource source = new LdapContextSource();
        source.setUrl(str(cfg.get("url")));
        source.setBase(str(cfg.get("base")));
        String userDn = str(cfg.get("userDnPattern"))
                .replace("{0}", username);
        source.setUserDn(userDn);
        source.setPassword(password);
        source.afterPropertiesSet();
        return new LdapTemplate(source);
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
