package io.orphos.eip.admin.service;

import io.orphos.eip.admin.component.AssetValidator;
import io.orphos.eip.admin.component.CssSanitizer;
import io.orphos.eip.admin.repository.BrandingRepository;
import io.orphos.eip.admin.util.Ids;
import io.orphos.eip.admin.vo.BrandingDtos;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Platform Default → Tenant Override → Reset. Runs against an in-memory,
 * tenant-partitioned stand-in for {@link BrandingRepository} so the
 * inheritance rules are exercised without an ArcadeDB.
 */
class BrandingInheritanceTest {

    private static final TenantContext TENANT_A = TenantContext.of(UUID.randomUUID(), "a.test");
    private static final TenantContext TENANT_B = TenantContext.of(UUID.randomUUID(), "b.test");
    private static final TenantContext PLATFORM =
            TenantContext.of(UUID.fromString("00000000-0000-0000-0000-000000000000"), "platform-template");

    private final InMemoryBrandingRepository repo = new InMemoryBrandingRepository();
    private final BrandingService svc = new BrandingService(repo, new CssSanitizer(), new AssetValidator(),
            new AuditService(new TemplateServiceTest.NoopArcadeClient()));

    @Test
    void newTenantInheritsPlatformDefaultWithoutAnyRows() {
        definePlatformDefault("Orphos", "Default tagline", "Inter");

        var a = in(TENANT_A, svc::getActiveBranding);

        assertEquals("Orphos", a.profile().productName());
        assertEquals("Default tagline", a.profile().tagline());
        assertEquals("Inter", a.profile().fontFamily());
        assertEquals("#111111", a.theme().colors().primary());
        assertEquals(BrandingService.SOURCE_PLATFORM_DEFAULT, a.source());
        assertTrue(a.overriddenFields().isEmpty());
        assertTrue(repo.rowsOf(TENANT_A).isEmpty(), "nothing is copied into the tenant");
    }

    @Test
    void platformDefaultChangesReachInheritingTenants() {
        definePlatformDefault("Orphos", "Default tagline", "Inter");
        in(TENANT_A, svc::getActiveBranding);

        definePlatformDefault("Orphos", "New tagline", "Roboto");

        var a = in(TENANT_A, svc::getActiveBranding);
        assertEquals("New tagline", a.profile().tagline());
        assertEquals("Roboto", a.profile().fontFamily());
    }

    @Test
    void tenantOverrideIsIsolatedAndUntouchedFieldsKeepFollowingTheDefault() {
        definePlatformDefault("Orphos", "Default tagline", "Inter");

        // The tenant form is pre-filled with the effective values; the admin
        // changes only the tagline and saves the whole form.
        in(TENANT_A, () -> {
            var eff = svc.getActiveBranding().profile();
            return svc.upsertProfile(request(eff, "Acme tagline"), "tenant-admin");
        });

        var a = in(TENANT_A, svc::getActiveBranding);
        assertEquals("Acme tagline", a.profile().tagline());
        assertEquals(BrandingService.SOURCE_CUSTOM, a.source());
        assertEquals(List.of("tagline"), a.overriddenFields(),
                "fields saved equal to the default stay inherited");

        // Other tenants and the platform default are unaffected.
        assertEquals("Default tagline", in(TENANT_B, svc::getActiveBranding).profile().tagline());
        assertEquals("Default tagline", svc.getPlatformTemplate().profile().tagline());

        // A later platform change still reaches the fields A did not override.
        definePlatformDefault("Orphos", "Default tagline", "Roboto");
        a = in(TENANT_A, svc::getActiveBranding);
        assertEquals("Acme tagline", a.profile().tagline());
        assertEquals("Roboto", a.profile().fontFamily());
    }

    @Test
    void savingWhileInheritingDoesNotPinThePlatformThemeOrAssets() {
        definePlatformDefault("Orphos", "Default tagline", "Inter");
        in(TENANT_A, () -> svc.upsertProfile(
                request(in(TENANT_A, svc::getActiveBranding).profile(), "Acme"), "tenant-admin"));

        Map<String, Object> own = repo.activeProfileOf(TENANT_A);
        assertEquals("", own.get("logoAssetId"));
        assertEquals("", own.get("activeThemeId"));
        // The effective logo/theme are still the platform's.
        var a = in(TENANT_A, svc::getActiveBranding);
        assertEquals(repo.activeProfileOf(PLATFORM).get("logoAssetId"), a.profile().logoAssetId());
        assertEquals("#111111", a.theme().colors().primary());
    }

    @Test
    void resetRemovesOverridesAndReturnsToTheLiveDefault() {
        definePlatformDefault("Orphos", "Default tagline", "Inter");
        in(TENANT_A, () -> svc.upsertProfile(
                request(in(TENANT_A, svc::getActiveBranding).profile(), "Acme"), "tenant-admin"));
        in(TENANT_B, () -> svc.upsertProfile(
                request(in(TENANT_B, svc::getActiveBranding).profile(), "Beta"), "tenant-admin"));

        var reset = in(TENANT_A, () -> svc.restorePlatformDefault("tenant-admin"));

        assertEquals("Default tagline", reset.profile().tagline());
        assertEquals(BrandingService.SOURCE_PLATFORM_DEFAULT, reset.source());
        assertTrue(repo.rowsOf(TENANT_A).isEmpty());
        assertEquals("Beta", in(TENANT_B, svc::getActiveBranding).profile().tagline(),
                "resetting one tenant leaves the others alone");
        assertNotNull(svc.getPlatformTemplate().profile(), "the platform default survives a tenant reset");
    }

    @Test
    void noTenantResolvedServesThePlatformDefault() {
        definePlatformDefault("Orphos", "Default tagline", "Inter");
        TenantContextHolder.clear();

        var anon = svc.getActiveBranding();

        assertEquals("Default tagline", anon.profile().tagline());
        assertEquals("#111111", anon.theme().colors().primary());
        assertEquals(BrandingService.SOURCE_PLATFORM_DEFAULT, anon.source());
        assertTrue(anon.labels().isEmpty());
    }

    @Test
    void platformDefaultCannotBeResetOntoItself() {
        definePlatformDefault("Orphos", "Default tagline", "Inter");
        assertThrows(IllegalStateException.class,
                () -> in(PLATFORM, () -> svc.restorePlatformDefault("platform-admin")));
    }

    // ----- fixtures -----

    /** Seed the platform template directly: a theme, a logo and the profile pointing at them. */
    private void definePlatformDefault(String product, String tagline, String font) {
        in(PLATFORM, () -> {
            Map<String, Object> theme = new HashMap<>();
            theme.put("name", "platform");
            theme.put("mode", "dark");
            theme.put("colors", Map.of("primary", "#111111", "secondary", "#222222",
                    "accent", "#333333", "background", "#000000", "text", "#FFFFFF"));
            theme.put("sanitizedCss", "");
            repo.listThemes().stream().findFirst()
                    .ifPresent(t -> theme.put("id", t.get("id")));
            String themeId = repo.saveTheme(theme);
            String logoId = repo.activeProfileOrEmpty().map(p -> (String) p.get("logoAssetId"))
                    .orElseGet(() -> repo.insertAsset(null, "logo", "image/png", 1, new byte[]{1}));
            Map<String, Object> p = repo.activeProfileOrEmpty().orElseGet(HashMap::new);
            p.put("name", "platform-default");
            p.put("productName", product);
            p.put("tagline", tagline);
            p.put("fontFamily", font);
            p.put("activeThemeId", themeId);
            p.put("logoAssetId", logoId);
            p.put("statusGreen", 15);
            p.put("statusBlue", 30);
            p.put("statusAmber", 50);
            p.put("active", true);
            repo.setActive(repo.saveProfile(p));
            return null;
        });
    }

    private static BrandingDtos.BrandingProfileRequest request(BrandingDtos.BrandingProfileView eff,
                                                               String tagline) {
        return new BrandingDtos.BrandingProfileRequest(
                eff.name(), eff.productName(), tagline, eff.country(), eff.defaultLanguage(),
                eff.activeThemeId(), eff.logoAssetId(), eff.backgroundAssetId(), eff.faviconAssetId(),
                eff.fontFamily(), eff.fontSize(), eff.timezone(), eff.currency(),
                eff.statusGreen(), eff.statusBlue(), eff.statusAmber());
    }

    private static <T> T in(TenantContext ctx, Supplier<T> work) {
        return TenantContextHolder.callWith(ctx, work);
    }

    /** Tenant-partitioned in-memory rows; mirrors the scoping of the real repository. */
    static class InMemoryBrandingRepository extends BrandingRepository {
        private final Map<String, Map<String, Map<String, Object>>> profiles = new HashMap<>();
        private final Map<String, Map<String, Map<String, Object>>> themes = new HashMap<>();
        private final Map<String, Map<String, Map<String, Object>>> assets = new HashMap<>();

        InMemoryBrandingRepository() { super(new TemplateServiceTest.NoopArcadeClient()); }

        private static String tenant() { return TenantContextHolder.requireTenantId().toString(); }
        private static Map<String, Map<String, Object>> of(Map<String, Map<String, Map<String, Object>>> m) {
            return m.computeIfAbsent(tenant(), k -> new LinkedHashMap<>());
        }

        List<Object> rowsOf(TenantContext ctx) {
            String t = ctx.tenantId().toString();
            List<Object> out = new ArrayList<>();
            out.addAll(profiles.getOrDefault(t, Map.of()).values());
            out.addAll(themes.getOrDefault(t, Map.of()).values());
            out.addAll(assets.getOrDefault(t, Map.of()).values());
            return out;
        }

        Map<String, Object> activeProfileOf(TenantContext ctx) {
            return in(ctx, () -> findActiveProfile().orElseThrow());
        }

        Optional<Map<String, Object>> activeProfileOrEmpty() {
            return findActiveProfile().map(HashMap::new);
        }

        @Override public List<Map<String, Object>> listProfiles() { return new ArrayList<>(of(profiles).values()); }
        @Override public Optional<Map<String, Object>> findProfile(String id) { return Optional.ofNullable(of(profiles).get(id)); }
        @Override public Optional<Map<String, Object>> findActiveProfile() {
            return of(profiles).values().stream().filter(p -> Boolean.TRUE.equals(p.get("active"))).findFirst();
        }
        @Override public Optional<Map<String, Object>> findProfileByName(String name) {
            return of(profiles).values().stream().filter(p -> Objects.equals(name, p.get("name"))).findFirst();
        }
        @Override public String saveProfile(Map<String, Object> p) {
            String id = (String) p.getOrDefault("id", Ids.newId());
            Map<String, Object> row = new HashMap<>(p);
            row.put("id", id);
            of(profiles).put(id, row);
            return id;
        }
        @Override public void setActive(String id) {
            of(profiles).values().forEach(p -> p.put("active", id.equals(p.get("id"))));
        }
        @Override public List<Map<String, Object>> listThemes() { return new ArrayList<>(of(themes).values()); }
        @Override public Optional<Map<String, Object>> findTheme(String id) { return Optional.ofNullable(of(themes).get(id)); }
        @Override public String saveTheme(Map<String, Object> t) {
            String id = (String) t.getOrDefault("id", Ids.newId());
            Map<String, Object> row = new HashMap<>(t);
            row.put("id", id);
            of(themes).put(id, row);
            return id;
        }
        @Override public String insertAsset(String profileId, String kind, String contentType, long size, byte[] data) {
            String id = Ids.newId();
            of(assets).put(id, new HashMap<>(Map.of("id", id, "kind", kind, "contentType", contentType)));
            return id;
        }
        @Override public boolean ownsAsset(String id) { return of(assets).containsKey(id); }
        @Override public void deleteAllAppearance() {
            profiles.remove(tenant());
            themes.remove(tenant());
            assets.remove(tenant());
        }
        @Override public List<Map<String, Object>> listLabels() { return List.of(); }
    }
}
