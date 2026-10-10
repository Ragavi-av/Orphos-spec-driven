package io.orphos.eip.admin.auth.controller;

import io.orphos.eip.admin.auth.dto.Dtos;
import io.orphos.eip.admin.auth.service.*;
import io.orphos.eip.admin.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin-only endpoints for configuring authentication.
 *
 * <p>RBAC is enforced two ways: the URL rule in {@code SecurityConfig}
 * ({@code /api/admin/**} → {@code hasRole("admin")}) AND the class-level
 * {@link PreAuthorize} below. Either alone would block non-admins; both
 * together protect against future refactors that move endpoints around.</p>
 */
@RestController
@RequestMapping("/api/admin/auth")
@PreAuthorize("hasAnyRole('admin','platform_admin','secops_admin')")
public class AdminAuthController {

    private final AuthConfigService config;
    private final UserAuthSwitchService switcher;
    private final AuthAnalyticsService analytics;
    private final AuthMonitoringService monitoring;
    private final AuditService audit;
    private final SamlAuthService saml;
    private final LdapAuthService ldap;

    public AdminAuthController(AuthConfigService config, UserAuthSwitchService switcher,
                               AuthAnalyticsService analytics, AuthMonitoringService monitoring,
                               AuditService audit, SamlAuthService saml, LdapAuthService ldap) {
        this.config = config;
        this.switcher = switcher;
        this.analytics = analytics;
        this.monitoring = monitoring;
        this.audit = audit;
        this.saml = saml;
        this.ldap = ldap;
    }

    // ---- A4: auth method configuration ----
    @GetMapping("/config")
    public Dtos.AuthMethodConfigView getConfig() { return config.getConfig(); }

    @PutMapping("/config")
    public Dtos.AuthMethodConfigView updateConfig(@Valid @RequestBody Dtos.AuthMethodConfigRequest req) {
        return config.updateConfig(req, CurrentUser.key());
    }

    @GetMapping("/providers")
    public List<Dtos.IdentityProviderView> listProviders() { return config.listProviders(); }

    @PutMapping("/providers")
    public Dtos.IdentityProviderView upsertProvider(@Valid @RequestBody Dtos.IdentityProviderRequest req) {
        return config.upsertProvider(req, CurrentUser.key());
    }

    @DeleteMapping("/providers/{name}")
    public ResponseEntity<Void> deleteProvider(@PathVariable String name) {
        config.deleteProvider(name, CurrentUser.key());
        return ResponseEntity.noContent().build();
    }

    // ---- A5: switch users between auth methods ----
    @PostMapping("/users/switch")
    public ResponseEntity<Void> switchMethod(@Valid @RequestBody Dtos.SwitchMethodRequest req,
                                             HttpServletRequest http) {
        switcher.switchMethod(req, CurrentUser.key(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    // ---- A3 (admin) provisioning ----
    @PostMapping("/saml/provision")
    public ResponseEntity<Void> provisionSaml(@RequestBody Map<String, String> body) {
        saml.provision(body.get("nameId"), body.get("providerName"), CurrentUser.key());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/saml/revoke")
    public ResponseEntity<Void> revokeSaml(@RequestBody Map<String, String> body) {
        saml.revoke(body.get("nameId"), CurrentUser.key());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ldap/provision")
    public ResponseEntity<Void> provisionLdap(@RequestBody Map<String, String> body) {
        ldap.provision(body.get("username"), body.get("providerName"), CurrentUser.key());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ldap/revoke")
    public ResponseEntity<Void> revokeLdap(@RequestBody Map<String, String> body) {
        ldap.revoke(body.get("username"), CurrentUser.key());
        return ResponseEntity.noContent().build();
    }

    // ---- A6: analytics ----
    @GetMapping("/analytics")
    public Dtos.AuthAnalyticsView analytics() { return analytics.summary24h(); }

    // ---- A7: monitoring ----
    @GetMapping("/security-alerts")
    public List<Dtos.SecurityAlertView> alerts() { return monitoring.currentAlerts(); }

    // ---- A8: audit ----
    @GetMapping("/audit")
    public List<Dtos.AuditEntryView> audit(@RequestParam(defaultValue = "100") int limit) {
        return audit.recent(limit);
    }
}
