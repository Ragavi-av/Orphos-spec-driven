package io.orphos.eip.common.security;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Maps a validated Keycloak JWT to Spring authorities — the one place a role
 * enters the platform, so the trust rules live here rather than in every consumer.
 *
 * <ol>
 *   <li>Reads roles from a dotted claim path (default {@code realm_access.roles}) and
 *       prefixes each with {@code ROLE_} (a value already carrying the prefix is
 *       normalised, not double-prefixed).</li>
 *   <li><b>Drops synthetic roles</b> ({@code service_internal}, {@code member}): they are
 *       only ever granted by the platform itself, so one arriving in a token is forged
 *       or misconfigured.</li>
 *   <li><b>Binds {@code platform_admin} to the platform realm.</b> The role is honoured
 *       only when the token's issuer is the platform realm. A tenant realm that defines a
 *       role with that name (a tenant admin can create realm roles in Keycloak directly)
 *       therefore gains nothing — without this, such a token would pass every
 *       {@code hasRole('platform_admin')} rule and could switch tenants.</li>
 *   <li>Adds {@code ROLE_member} (derived) when the user holds any real role beyond guest.</li>
 *   <li>Adds each custom role's built-in base (derived), looked up through
 *       {@link CustomRoleBaseSource} — only for tokens that actually carry a custom role.</li>
 * </ol>
 */
public class EipJwtAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Logger log = LogManager.getLogger(EipJwtAuthoritiesConverter.class);

    private final String claimPath;
    private final String rolePrefix;
    private final String platformRealm;
    private final String platformIssuer;
    private final String tenantClaim;
    private final Supplier<CustomRoleBaseSource> customRoleBases;

    public EipJwtAuthoritiesConverter(String claimPath, String rolePrefix, String platformRealm,
                                      String platformIssuer, String tenantClaim,
                                      Supplier<CustomRoleBaseSource> customRoleBases) {
        this.claimPath = claimPath;
        this.rolePrefix = rolePrefix == null ? EipRoles.ROLE_PREFIX : rolePrefix;
        this.platformRealm = platformRealm == null || platformRealm.isBlank() ? "platform" : platformRealm;
        this.platformIssuer = platformIssuer;
        this.tenantClaim = tenantClaim == null || tenantClaim.isBlank() ? "tenant_id" : tenantClaim;
        this.customRoleBases = customRoleBases == null ? () -> null : customRoleBases;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<String> roles = rawRoles(jwt);

        roles.remove(EipRoles.SERVICE_INTERNAL);
        roles.remove(EipRoles.MEMBER);
        if (roles.contains(EipRoles.PLATFORM_ADMIN) && !isPlatformIssuer(jwt.getClaimAsString("iss"))) {
            roles.remove(EipRoles.PLATFORM_ADMIN);
            log.warn("Ignored platform_admin role on a token from non-platform issuer {} (sub={})",
                    jwt.getClaimAsString("iss"), jwt.getSubject());
        }

        List<GrantedAuthority> out = new ArrayList<>(roles.size() + 2);
        for (String r : roles) out.add(new SimpleGrantedAuthority(rolePrefix + r));

        if (roles.stream().anyMatch(EipRoles::confersMembership)) {
            out.add(new EipDerivedAuthority(rolePrefix + EipRoles.MEMBER));
        }
        addCustomRoleBases(jwt, roles, out);
        return out;
    }

    private void addCustomRoleBases(Jwt jwt, Set<String> roles, List<GrantedAuthority> out) {
        List<String> custom = roles.stream()
                .filter(r -> !EipRoles.isBuiltIn(r) && EipRoles.confersMembership(r))
                .toList();
        if (custom.isEmpty()) return;
        CustomRoleBaseSource source = customRoleBases.get();
        UUID tenantId = parseUuid(jwt.getClaimAsString(tenantClaim));
        if (source == null || tenantId == null) return;

        Map<String, String> bases;
        try {
            bases = source.customRoleBases(tenantId);
        } catch (Exception e) {
            log.debug("Custom role base lookup failed for tenant {}: {}", tenantId, e.getMessage());
            return;
        }
        if (bases == null) return;
        Set<String> added = new LinkedHashSet<>();
        for (String r : custom) {
            String base = CustomRoleBaseSource.builtInBase(r, bases);
            if (base != null && !roles.contains(base) && added.add(base)) {
                out.add(new EipDerivedAuthority(rolePrefix + base));
            }
        }
    }

    private Set<String> rawRoles(Jwt jwt) {
        Set<String> roles = new LinkedHashSet<>();
        Object value = navigateClaim(jwt.getClaims(), claimPath);
        if (!(value instanceof Collection<?> raw)) return roles;
        for (Object role : raw) {
            if (role == null) continue;
            String name = role.toString().trim();
            if (name.startsWith(rolePrefix)) name = name.substring(rolePrefix.length());
            if (!name.isEmpty()) roles.add(name);
        }
        return roles;
    }

    boolean isPlatformIssuer(String issuer) {
        if (issuer == null || issuer.isBlank()) return false;
        String iss = issuer.replaceAll("/+$", "");
        if (platformIssuer != null && !platformIssuer.isBlank()
                && iss.equals(platformIssuer.replaceAll("/+$", ""))) {
            return true;
        }
        return iss.endsWith("/realms/" + platformRealm);
    }

    /**
     * Walks a dotted claim path through nested {@code Map}s. Returns the
     * final value or {@code null} if any segment is missing / not a Map.
     */
    static Object navigateClaim(Map<String, Object> claims, String path) {
        if (claims == null || path == null || path.isBlank()) return null;
        Object current = claims;
        for (String segment : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> m)) return null;
            current = m.get(segment);
            if (current == null) return null;
        }
        return current;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
