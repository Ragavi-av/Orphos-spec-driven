package io.orphos.eip.admin.integration;

import io.orphos.eip.admin.repository.BrandingRepository;
import io.orphos.eip.admin.repository.EmailRepository;
import io.orphos.eip.admin.testsupport.BaseIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies repository-level tenant isolation for the branding/email repos —
 * the other half of the former admin-service's combined
 * {@code AdminRepoIsolationIT} (auth now lives in platform-core-service's
 * own copy of this test). Each test seeds rows for two tenants and confirms
 * that every read returns only the active-tenant's rows — no cross-tenant
 * leakage, and no collisions when both tenants use the same "natural key"
 * (profile name, template code).
 */
class AdminRepoIsolationIT extends BaseIntegrationTest {

    private static final UUID TENANT_A = UUID.randomUUID();
    private static final UUID TENANT_B = UUID.randomUUID();

    private final BrandingRepository branding = new BrandingRepository(ARCADE_CLIENT);
    private final EmailRepository    email    = new EmailRepository(ARCADE_CLIENT);

    // ── Branding ────────────────────────────────────────────────────────

    @Test
    void brandingProfilesIsolatedByTenant() {
        // Same profile name in both tenants — would have collided under the
        // legacy single-column UNIQUE index. Migration V2 + the updated
        // SchemaBootstrap together make this work.
        withTenant(TENANT_A, () -> {
            Map<String, Object> p = new HashMap<>();
            p.put("name", "default");
            p.put("productName", "Tenant A");
            branding.saveProfile(p);
        });
        withTenant(TENANT_B, () -> {
            Map<String, Object> p = new HashMap<>();
            p.put("name", "default");
            p.put("productName", "Tenant B");
            branding.saveProfile(p);
        });

        withTenant(TENANT_A, () -> {
            List<Map<String, Object>> rows = branding.listProfiles();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("productName")).isEqualTo("Tenant A");
        });
        withTenant(TENANT_B, () -> {
            List<Map<String, Object>> rows = branding.listProfiles();
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("productName")).isEqualTo("Tenant B");
        });
    }

    @Test
    void setActiveProfileDoesNotDeactivateOtherTenants() {
        // Tenant A creates + activates a profile. Tenant B creates + activates
        // its own profile. Tenant A's profile must REMAIN active — the legacy
        // setActive method had no WHERE clause and would flip A's row back to
        // inactive when B activated its own.
        String[] aProfileId = new String[1];
        String[] bProfileId = new String[1];

        withTenant(TENANT_A, () -> {
            Map<String, Object> p = new HashMap<>();
            p.put("name", "active-test");
            aProfileId[0] = branding.saveProfile(p);
            branding.setActive(aProfileId[0]);
        });
        withTenant(TENANT_B, () -> {
            Map<String, Object> p = new HashMap<>();
            p.put("name", "active-test");
            bProfileId[0] = branding.saveProfile(p);
            branding.setActive(bProfileId[0]);
        });

        withTenant(TENANT_A, () -> {
            Optional<Map<String, Object>> active = branding.findActiveProfile();
            assertThat(active).isPresent();
            assertThat(active.get().get("id")).isEqualTo(aProfileId[0]);
        });
        withTenant(TENANT_B, () -> {
            Optional<Map<String, Object>> active = branding.findActiveProfile();
            assertThat(active).isPresent();
            assertThat(active.get().get("id")).isEqualTo(bProfileId[0]);
        });
    }

    // ── Email ───────────────────────────────────────────────────────────

    @Test
    void emailTemplatesIsolatedByTenant() {
        withTenant(TENANT_A, () -> {
            Map<String, Object> t = new HashMap<>();
            t.put("code", "welcome");
            t.put("name", "Welcome (A)");
            t.put("subject", "Welcome to Acme");
            email.upsertTemplate(t);
        });
        withTenant(TENANT_B, () -> {
            Map<String, Object> t = new HashMap<>();
            t.put("code", "welcome");
            t.put("name", "Welcome (B)");
            t.put("subject", "Welcome to Beta");
            email.upsertTemplate(t);
        });

        withTenant(TENANT_A, () -> {
            Optional<Map<String, Object>> t = email.findTemplate("welcome");
            assertThat(t).isPresent();
            assertThat(t.get().get("name")).isEqualTo("Welcome (A)");
        });
        withTenant(TENANT_B, () -> {
            Optional<Map<String, Object>> t = email.findTemplate("welcome");
            assertThat(t).isPresent();
            assertThat(t.get().get("name")).isEqualTo("Welcome (B)");
        });
    }

    @Test
    void setSingleEnabledConfigDoesNotAffectOtherTenants() {
        // Both tenants enable their own EmailConfig named "primary". Without
        // the tenantId scoping the first UPDATE in setSingleEnabled would
        // disable EVERY tenant's enabled config.
        withTenant(TENANT_A, () -> {
            Map<String, Object> c = new HashMap<>();
            c.put("name", "primary");
            c.put("host", "smtp-a.test");
            c.put("enabled", false);
            email.upsertConfig(c);
            email.setSingleEnabled("primary");
        });
        withTenant(TENANT_B, () -> {
            Map<String, Object> c = new HashMap<>();
            c.put("name", "primary");
            c.put("host", "smtp-b.test");
            c.put("enabled", false);
            email.upsertConfig(c);
            email.setSingleEnabled("primary");
        });

        withTenant(TENANT_A, () -> {
            Optional<Map<String, Object>> active = email.findActiveConfig();
            assertThat(active).isPresent();
            assertThat(active.get().get("host")).isEqualTo("smtp-a.test");
        });
        withTenant(TENANT_B, () -> {
            Optional<Map<String, Object>> active = email.findActiveConfig();
            assertThat(active).isPresent();
            assertThat(active.get().get("host")).isEqualTo("smtp-b.test");
        });
    }
}
