package io.orphos.eip.admin.auth.controller;

import io.orphos.eip.admin.auth.dto.Dtos;
import io.orphos.eip.admin.auth.service.LocalAuthService;
import io.orphos.eip.admin.auth.service.OidcAuthService;
import io.orphos.eip.admin.auth.service.SamlAuthService;
import io.orphos.eip.admin.auth.service.LdapAuthService;
import io.orphos.eip.admin.common.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Public-facing auth endpoints used by the login UI. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final LocalAuthService local;
    private final OidcAuthService oidc;
    private final SamlAuthService saml;
    private final LdapAuthService ldap;

    public AuthController(LocalAuthService local, OidcAuthService oidc,
                          SamlAuthService saml, LdapAuthService ldap) {
        this.local = local;
        this.oidc = oidc;
        this.saml = saml;
        this.ldap = ldap;
    }

    @PostMapping("/register")
    public Dtos.LoginResponse register(@Valid @RequestBody Dtos.RegisterRequest req,
                                       HttpServletRequest http) {
        return local.register(req, http.getRemoteAddr());
    }

    @PostMapping("/login")
    public Dtos.LoginResponse login(@Valid @RequestBody Dtos.LoginRequest req,
                                    HttpServletRequest http) {
        return local.login(req, http.getRemoteAddr());
    }

    @PostMapping("/forgot")
    public ResponseEntity<Void> forgot(@Valid @RequestBody Dtos.ForgotPasswordRequest req,
                                       HttpServletRequest http) {
        local.forgot(req, http.getRemoteAddr());
        return ResponseEntity.accepted().build();
    }

    /** LDAP direct-bind endpoint; admin-provisioned users only. */
    @PostMapping("/ldap/login")
    public Dtos.LoginResponse ldapLogin(@RequestParam String providerName,
                                        @RequestBody Map<String, String> body,
                                        HttpServletRequest http) {
        return ldap.login(providerName,
                body.getOrDefault("username", ""),
                body.getOrDefault("password", ""),
                http.getRemoteAddr());
    }

    /**
     * Called after a successful OIDC exchange handled by Spring Security.
     * The Bearer JWT is the result of the Keycloak (or other OIDC) login flow.
     */
    @GetMapping("/oidc/reconcile")
    public Map<String, String> oidcReconcile(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam String providerName,
                                             HttpServletRequest http) {
        String userKey = oidc.reconcile(jwt, providerName, http.getRemoteAddr());
        return Map.of("userKey", userKey);
    }

    /**
     * SAML reconciliation hook. The actual assertion validation is performed
     * by Spring Security SAML; this endpoint accepts the resolved NameID.
     */
    @PostMapping("/saml/reconcile")
    public Map<String, String> samlReconcile(@RequestParam String providerName,
                                             @RequestBody Map<String, String> body,
                                             HttpServletRequest http) {
        String userKey = saml.reconcile(body.get("nameId"), providerName, http.getRemoteAddr());
        return Map.of("userKey", userKey);
    }

    /** Returns the caller's currently-bound auth method, useful for the UI. */
    @GetMapping("/me")
    public Map<String, String> me() {
        return Map.of("userKey", CurrentUser.key(),
                "admin", String.valueOf(CurrentUser.isAdmin()));
    }
}
