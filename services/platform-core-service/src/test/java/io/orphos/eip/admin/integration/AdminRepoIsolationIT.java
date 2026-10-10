package io.orphos.eip.admin.integration;

import io.orphos.eip.admin.auth.model.AuthEventType;
import io.orphos.eip.admin.auth.model.AuthMethodType;
import io.orphos.eip.admin.auth.repo.AuthRepository;
import io.orphos.eip.admin.testsupport.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies repository-level tenant isolation for the auth repo — the other
 * half of the former admin-service's combined {@code AdminRepoIsolationIT}
 * (branding/email now live in admin-console-service's own copy of this
 * test). Each test seeds rows for two tenants and confirms that every read
 * returns only the active-tenant's rows — no cross-tenant leakage, and no
 * collisions when both tenants use the same "natural key" (provider name,
 * user email).
 */
class AdminRepoIsolationIT extends BaseIntegrationTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    private final AuthRepository auth = new AuthRepository(ARCADE_CLIENT);

    @Test
    void localUsersIsolatedByTenantEvenWithSameEmail() {
        // Pre-fix this would have failed at INSERT time because of the
        // single-column UNIQUE(email) index across the whole class. After
        // V2 drops that index and the compound (tenantId, email) UNIQUE
        // takes over, both inserts succeed.
        withTenant(TENANT_A, () -> auth.insertLocalUser("shared@example.test", "hash-a", "Alice A"));
        withTenant(TENANT_B, () -> auth.insertLocalUser("shared@example.test", "hash-b", "Bob B"));

        withTenant(TENANT_A, () -> {
            Optional<Map<String, Object>> u = auth.findLocalUserByEmail("shared@example.test");
            assertThat(u).isPresent();
            assertThat(u.get().get("displayName")).isEqualTo("Alice A");
        });
        withTenant(TENANT_B, () -> {
            Optional<Map<String, Object>> u = auth.findLocalUserByEmail("shared@example.test");
            assertThat(u).isPresent();
            assertThat(u.get().get("displayName")).isEqualTo("Bob B");
        });
    }

    @Test
    void identityProvidersIsolatedByTenant() {
        withTenant(TENANT_A, () -> auth.upsertProvider("azure", AuthMethodType.OIDC, true, false,
                List.of("acme.com"), Map.of("clientId", "a")));
        withTenant(TENANT_B, () -> auth.upsertProvider("azure", AuthMethodType.OIDC, true, false,
                List.of("beta.com"), Map.of("clientId", "b")));

        withTenant(TENANT_A, () -> {
            List<Map<String, Object>> providers = auth.listProviders();
            assertThat(providers).hasSize(1);
            assertThat(providers.get(0).get("allowedEmailDomains").toString()).contains("acme.com");
        });
        withTenant(TENANT_B, () -> {
            List<Map<String, Object>> providers = auth.listProviders();
            assertThat(providers).hasSize(1);
            assertThat(providers.get(0).get("allowedEmailDomains").toString()).contains("beta.com");
        });
    }

    @Test
    void updateAuthMethodConfigDoesNotAffectOtherTenants() {
        // Critical: the legacy updateAuthMethodConfig had NO WHERE clause and
        // would rewrite every tenant's AuthMethod row. This test would have
        // tenant B's flags being overwritten by tenant A's update.
        withTenant(TENANT_A, () -> {
            auth.getOrInitAuthMethodConfig();
            auth.updateAuthMethodConfig(true, true, false, false, AuthMethodType.OIDC);
        });
        withTenant(TENANT_B, () -> {
            auth.getOrInitAuthMethodConfig();
            auth.updateAuthMethodConfig(true, false, true, true, AuthMethodType.SAML);
        });

        withTenant(TENANT_A, () -> {
            Map<String, Object> cfg = auth.getOrInitAuthMethodConfig();
            assertThat(cfg.get("oidcEnabled")).isEqualTo(true);
            assertThat(cfg.get("samlEnabled")).isEqualTo(false);
            assertThat(cfg.get("defaultMethod")).isEqualTo("OIDC");
        });
        withTenant(TENANT_B, () -> {
            Map<String, Object> cfg = auth.getOrInitAuthMethodConfig();
            assertThat(cfg.get("oidcEnabled")).isEqualTo(false);
            assertThat(cfg.get("samlEnabled")).isEqualTo(true);
            assertThat(cfg.get("defaultMethod")).isEqualTo("SAML");
        });
    }

    @Test
    void authEventsIsolatedByTenant() {
        Instant since = Instant.now().minusSeconds(60);
        withTenant(TENANT_A, () -> {
            auth.recordEvent("a-user", AuthEventType.LOGIN_SUCCESS, AuthMethodType.LOCAL, true, "", "1.2.3.4");
            auth.recordEvent("a-user", AuthEventType.LOGIN_SUCCESS, AuthMethodType.LOCAL, true, "", "1.2.3.4");
        });
        withTenant(TENANT_B, () -> {
            auth.recordEvent("b-user", AuthEventType.LOGIN_SUCCESS, AuthMethodType.LOCAL, true, "", "5.6.7.8");
        });

        withTenant(TENANT_A, () -> {
            List<Map<String, Object>> events = auth.recentEvents(since);
            assertThat(events).hasSize(2);
            assertThat(events).allSatisfy(e ->
                    assertThat(e.get("userKey")).isEqualTo("a-user"));
        });
        withTenant(TENANT_B, () -> {
            List<Map<String, Object>> events = auth.recentEvents(since);
            assertThat(events).hasSize(1);
            assertThat(events.get(0).get("userKey")).isEqualTo("b-user");
        });
    }
}
