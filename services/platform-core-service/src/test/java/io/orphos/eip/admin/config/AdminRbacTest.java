package io.orphos.eip.admin.config;

import io.orphos.eip.core.arcadedb.ArcadeDbProperties;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.auth.controller.AdminAuthController;
import io.orphos.eip.admin.auth.controller.AuthController;
import io.orphos.eip.admin.auth.repo.AuthRepository;
import io.orphos.eip.admin.auth.service.*;
import io.orphos.eip.admin.secrets.SecretRefService;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.secrets.SecretsClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the platform-core-service RBAC contract end-to-end through Spring
 * Security's filter chain, for the auth/authz-config surface (the other half
 * of the former admin-service's combined {@code AdminRbacTest} — branding
 * and email now live in admin-console-service's own copy of this test):
 * <ul>
 *   <li>Anonymous → 401 on admin auth-config endpoint</li>
 *   <li>Authenticated with a non-admin role → 403</li>
 *   <li>Authenticated with ROLE_admin → 2xx</li>
 *   <li>Non-admin endpoints (e.g. /api/auth/me) → 2xx for any signed-in role</li>
 * </ul>
 *
 * <p>We deliberately avoid Mockito here - its inline mock-maker doesn't
 * play well with the JDK 25 runtime Surefire picks up, and we don't need
 * mocks to test the security filter chain: the gate is enforced before
 * any controller method runs, so a real (but stubbed) bean graph works
 * fine.</p>
 */
@WebMvcTest(controllers = { AdminAuthController.class, AuthController.class })
@Import({ SecurityConfig.class, AdminRbacTest.StubBeans.class })
@TestPropertySource(properties = {
        // @WithMockUser bypasses JWT validation - drop the resource-server auto-config so
        // Spring doesn't try to reach Keycloak's JWKS endpoint during context load.
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration",
        "admin.auth.bcrypt-strength=4"
})
class AdminRbacTest {

    @Autowired MockMvc mvc;

    /**
     * The repositories now require a tenant context (admin-repo cross-tenant
     * leakage fix). {@code @WithMockUser} bypasses the JWT decoder, so the
     * production {@code TenantResolutionFilter} never runs in this test — we
     * bind context manually before each request and clear it after.
     */
    @BeforeEach
    void bindTenantContext() {
        TenantContextHolder.set(TenantContext.of(UUID.randomUUID(), "rbac-test.invalid"));
    }

    @AfterEach
    void clearTenantContext() {
        TenantContextHolder.clear();
    }

    // ---------- anonymous ----------
    @Test @WithAnonymousUser
    void anonymousGets401OnAdminAuthConfig() throws Exception {
        mvc.perform(get("/api/admin/auth/config")).andExpect(status().isUnauthorized());
    }

    // ---------- non-admin authenticated ----------
    @Test @WithMockUser(roles = "executive")
    void executiveGets403OnAdminAuthConfig() throws Exception {
        mvc.perform(get("/api/admin/auth/config")).andExpect(status().isForbidden());
    }

    // ---------- admin authenticated ----------
    @Test @WithMockUser(roles = "admin")
    void adminPassesAuthConfigFilter() throws Exception {
        mvc.perform(get("/api/admin/auth/config")).andExpect(status().is2xxSuccessful());
    }

    // ---------- non-admin user endpoints ----------
    @Test @WithMockUser(roles = "executive")
    void anyAuthenticatedUserCanCallAuthMe() throws Exception {
        mvc.perform(get("/api/auth/me")).andExpect(status().is2xxSuccessful());
    }

    /**
     * Hand-rolled stub beans for everything the controllers transitively need.
     * No Mockito, so this works on every JDK version. The point is the
     * security filter chain - controller method bodies don't run for
     * anonymous/forbidden requests, and for the admin-success cases we just
     * need anything that returns without throwing.
     */
    static class StubBeans {

        /**
         * Satisfies the {@code oauth2ResourceServer().jwt()} dependency in
         * SecurityConfig at context-load time. {@code @WithMockUser} populates
         * the SecurityContext directly, so this decoder is never invoked - it
         * just needs to exist as a bean.
         */
        @Bean JwtDecoder jwtDecoder() {
            return token -> { throw new IllegalStateException("test decoder should not be called"); };
        }

        /**
         * SecurityConfig switched to multi-issuer JWT validation (uses an
         * AuthenticationManagerResolver instead of a single JwtDecoder), so
         * a Resolver stub is now also required at context-load time. The
         * lambda is never invoked under {@code @WithMockUser} for the same
         * reason the JwtDecoder above isn't.
         */
        @Bean AuthenticationManagerResolver<HttpServletRequest> jwtAuthenticationManagerResolver() {
            return request -> { throw new IllegalStateException("test resolver should not be called"); };
        }

        @Bean ArcadeDbTemplate arcadeDbTemplate() {
            return new ArcadeDbTemplate(new ArcadeDbProperties()) {
                @Override public List<Map<String, Object>> query(String sql, Map<String, Object> p) { return List.of(); }
                @Override public List<Map<String, Object>> command(String sql, Map<String, Object> p) { return List.of(); }
                @Override public void commandNoResult(String sql, Map<String, Object> p) {}
            };
        }

        @Bean SecretsClient secretsClient() { return new SecretsClient("", "", "", "", ""); }
        @Bean SecretRefService secretRefService(SecretsClient c) { return new SecretRefService(c); }
        @Bean IdpSecretStore idpSecretStore(SecretRefService s) { return new IdpSecretStore(s); }

        @Bean AuthRepository authRepository(ArcadeDbTemplate c) { return new AuthRepository(c); }

        @Bean AuthMonitoringService authMonitoringService() { return new AuthMonitoringService(); }
        @Bean AuditService auditService(AuthRepository r) { return new AuditService(r); }
        @Bean AuthAnalyticsService authAnalyticsService(AuthRepository r) { return new AuthAnalyticsService(r); }
        @Bean AuthConfigService authConfigService(AuthRepository r, IdpSecretStore i) {
            // RBAC tests don't exercise SSO federation — Keycloak/TenantRepository
            // are null (federation paths are null-guarded).
            return new AuthConfigService(r, i, null, null);
        }
        @Bean UserAuthSwitchService userAuthSwitchService(AuthRepository r) {
            return new UserAuthSwitchService(r);
        }
        @Bean OidcAuthService oidcAuthService(AuthRepository r) { return new OidcAuthService(r); }
        @Bean SamlAuthService samlAuthService(AuthRepository r) { return new SamlAuthService(r); }
        @Bean LdapAuthService ldapAuthService(AuthRepository r, AuthMonitoringService m, IdpSecretStore i) {
            return new LdapAuthService(r, m, i);
        }
        @Bean LocalAuthService localAuthService(AuthRepository r,
                                                 org.springframework.security.crypto.password.PasswordEncoder e,
                                                 AuthMonitoringService m) {
            return new LocalAuthService(r, e, m);
        }
    }
}
