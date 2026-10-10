package io.orphos.eip.admin.config;

import io.orphos.eip.admin.component.SmtpSecretStore;
import io.orphos.eip.admin.service.EmailAnalyticsService;
import io.orphos.eip.admin.service.EmailBackupService;
import io.orphos.eip.admin.service.EmailConfigService;
import io.orphos.eip.admin.service.EmailSendService;
import io.orphos.eip.admin.service.TemplateService;
import io.orphos.eip.core.arcadedb.ArcadeDbProperties;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.admin.service.AuditService;
import io.orphos.eip.admin.controller.AdminBrandingController;
import io.orphos.eip.admin.controller.BrandingController;
import io.orphos.eip.admin.repository.BrandingRepository;
import io.orphos.eip.admin.component.AssetValidator;
import io.orphos.eip.admin.service.BrandingService;
import io.orphos.eip.admin.component.CssSanitizer;
import io.orphos.eip.admin.controller.AdminEmailController;
import io.orphos.eip.admin.repository.EmailRepository;
import io.orphos.eip.admin.component.SecretRefService;
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
 * Verifies the admin-console-service RBAC contract end-to-end through Spring
 * Security's filter chain, for the branding/email surface (the other half of
 * the former admin-service's combined {@code AdminRbacTest} — auth-config
 * now lives in platform-core-service's own copy of this test):
 * <ul>
 *   <li>Anonymous → 401 on admin endpoints, 2xx on public branding</li>
 *   <li>Authenticated with a non-admin role → 403</li>
 *   <li>Authenticated with ROLE_admin → 2xx</li>
 * </ul>
 *
 * <p>We deliberately avoid Mockito here - its inline mock-maker doesn't
 * play well with the JDK 25 runtime Surefire picks up, and we don't need
 * mocks to test the security filter chain: the gate is enforced before
 * any controller method runs, so a real (but stubbed) bean graph works
 * fine.</p>
 */
@WebMvcTest(controllers = { AdminBrandingController.class, AdminEmailController.class, BrandingController.class })
@Import({ SecurityConfig.class, AdminRbacTest.StubBeans.class })
@TestPropertySource(properties = {
        // @WithMockUser bypasses JWT validation - drop the resource-server auto-config so
        // Spring doesn't try to reach Keycloak's JWKS endpoint during context load.
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration",
        "admin.branding.allowed-mime=image/png"
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
    void anonymousGets401OnAdminBrandingProfiles() throws Exception {
        mvc.perform(get("/api/admin/branding/profiles")).andExpect(status().isUnauthorized());
    }

    @Test @WithAnonymousUser
    void anonymousGets401OnAdminEmailConfig() throws Exception {
        mvc.perform(get("/api/admin/email/config")).andExpect(status().isUnauthorized());
    }

    @Test @WithAnonymousUser
    void publicBrandingActiveIsReachable() throws Exception {
        // /api/branding/public/** is in the permitAll list - must pass the filter.
        mvc.perform(get("/api/branding/public/active")).andExpect(status().is2xxSuccessful());
    }

    // ---------- non-admin authenticated ----------
    @Test @WithMockUser(roles = "ceo")
    void ceoGets403OnAdminBrandingProfiles() throws Exception {
        mvc.perform(get("/api/admin/branding/profiles")).andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles = "viewer")
    void viewerGets403OnAdminEmailConfig() throws Exception {
        mvc.perform(get("/api/admin/email/config")).andExpect(status().isForbidden());
    }

    // ---------- admin authenticated ----------
    @Test @WithMockUser(roles = "admin")
    void adminPassesBrandingProfilesFilter() throws Exception {
        mvc.perform(get("/api/admin/branding/profiles")).andExpect(status().is2xxSuccessful());
    }

    @Test @WithMockUser(roles = "admin")
    void adminPassesEmailConfigFilter() throws Exception {
        mvc.perform(get("/api/admin/email/config")).andExpect(status().is2xxSuccessful());
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
        @Bean SmtpSecretStore smtpSecretStore(SecretRefService s) { return new SmtpSecretStore(s); }

        @Bean BrandingRepository brandingRepository(ArcadeDbTemplate c) { return new BrandingRepository(c); }
        @Bean EmailRepository emailRepository(ArcadeDbTemplate c) { return new EmailRepository(c); }

        @Bean AuditService auditService(ArcadeDbTemplate c) { return new AuditService(c); }

        @Bean CssSanitizer cssSanitizer() { return new CssSanitizer(); }
        @Bean AssetValidator assetValidator() { return new AssetValidator(); }
        @Bean BrandingService brandingService(BrandingRepository r, CssSanitizer cs,
                                              AssetValidator v, AuditService a) {
            return new BrandingService(r, cs, v, a);
        }

        @Bean EmailConfigService emailConfigService(EmailRepository r, SmtpSecretStore s, AuditService a) {
            return new EmailConfigService(r, s, a);
        }
        @Bean TemplateService templateService(EmailRepository r, AuditService a) {
            return new TemplateService(r, a);
        }
        @Bean EmailAnalyticsService emailAnalyticsService(EmailRepository r) {
            return new EmailAnalyticsService(r);
        }
        @Bean EmailSendService emailSendService(EmailRepository r, EmailConfigService c, TemplateService t) {
            return new EmailSendService(r, c, t);
        }
        @Bean EmailBackupService emailBackupService(EmailRepository r, EmailConfigService c,
                                                     TemplateService t, AuditService a) {
            return new EmailBackupService(r, c, t, a);
        }
    }
}
