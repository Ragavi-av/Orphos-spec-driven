package io.orphos.eip.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EipJwtAuthoritiesConverterTest {

    private static final String TENANT_ISS = "http://localhost:8443/realms/tenant-a";
    private static final String PLATFORM_ISS = "http://localhost:8443/realms/platform";
    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static EipJwtAuthoritiesConverter converter(CustomRoleBaseSource source) {
        return new EipJwtAuthoritiesConverter("realm_access.roles", "ROLE_", "platform", null,
                "tenant_id", () -> source);
    }

    private static Jwt jwt(String issuer, String... roles) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .issuer(issuer).subject("user-1")
                .claim("tenant_id", TENANT.toString())
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300))
                .build();
    }

    private static Set<String> names(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    @Test
    void mapsRealmRolesWithPrefixAndDerivesMember() {
        Collection<GrantedAuthority> out = converter(null).convert(jwt(TENANT_ISS, "cfo", "offline_access"));
        assertThat(names(out)).containsExactlyInAnyOrder("ROLE_cfo", "ROLE_offline_access", "ROLE_member");
        assertThat(out.stream().filter(a -> a.getAuthority().equals("ROLE_member")))
                .allMatch(a -> a instanceof EipDerivedAuthority);
    }

    @Test
    void guestOnlyUserIsNotAMember() {
        Collection<GrantedAuthority> out = converter(null)
                .convert(jwt(TENANT_ISS, "guest", "default-roles-tenant-a", "uma_authorization"));
        assertThat(names(out)).doesNotContain("ROLE_member");
    }

    @Test
    void platformAdminRoleFromTenantRealmIsDropped() {
        Collection<GrantedAuthority> out = converter(null).convert(jwt(TENANT_ISS, "platform_admin", "viewer"));
        assertThat(names(out)).doesNotContain("ROLE_platform_admin").contains("ROLE_viewer");
    }

    @Test
    void platformAdminRoleFromPlatformRealmIsKept() {
        Collection<GrantedAuthority> out = converter(null).convert(jwt(PLATFORM_ISS, "platform_admin"));
        assertThat(names(out)).contains("ROLE_platform_admin", "ROLE_member");
    }

    @Test
    void syntheticRolesInTokenAreDropped() {
        Collection<GrantedAuthority> out = converter(null)
                .convert(jwt(TENANT_ISS, "service_internal", "ROLE_service_internal", "member", "guest"));
        assertThat(names(out)).containsExactly("ROLE_guest");
    }

    @Test
    void alreadyPrefixedRolesAreNormalisedNotDoubled() {
        Collection<GrantedAuthority> out = converter(null).convert(jwt(TENANT_ISS, "ROLE_viewer"));
        assertThat(names(out)).contains("ROLE_viewer").doesNotContain("ROLE_ROLE_viewer");
    }

    @Test
    void customRoleInheritsItsBuiltInBaseAsDerivedAuthority() {
        CustomRoleBaseSource source = tenant -> Map.of("operations_head", "cfo");
        Collection<GrantedAuthority> out = converter(source).convert(jwt(TENANT_ISS, "operations_head"));
        assertThat(names(out)).contains("ROLE_operations_head", "ROLE_cfo", "ROLE_member");
        assertThat(out.stream().filter(a -> a.getAuthority().equals("ROLE_cfo")))
                .allMatch(a -> a instanceof EipDerivedAuthority);
    }

    @Test
    void customRoleChainResolvesThroughCustomBases() {
        CustomRoleBaseSource source = tenant -> Map.of("junior_ops", "operations_head", "operations_head", "cto");
        assertThat(names(converter(source).convert(jwt(TENANT_ISS, "junior_ops")))).contains("ROLE_cto");
    }

    @Test
    void platformAdminIsNeverInheritable() {
        CustomRoleBaseSource source = tenant -> Map.of("super_ops", "platform_admin");
        assertThat(names(converter(source).convert(jwt(TENANT_ISS, "super_ops"))))
                .doesNotContain("ROLE_platform_admin");
    }

    @Test
    void unavailableBaseSourceGrantsNothingExtra() {
        CustomRoleBaseSource source = tenant -> null;
        assertThat(names(converter(source).convert(jwt(TENANT_ISS, "operations_head"))))
                .containsExactlyInAnyOrder("ROLE_operations_head", "ROLE_member");
    }

    @Test
    void builtInOnlyTokensNeverConsultTheBaseSource() {
        AtomicInteger calls = new AtomicInteger();
        CustomRoleBaseSource source = tenant -> { calls.incrementAndGet(); return Map.of(); };
        converter(source).convert(jwt(TENANT_ISS, "cfo", "viewer", "default-roles-tenant-a"));
        assertThat(calls).hasValue(0);
    }
}
