package io.orphos.eip.common.security;

import org.springframework.security.core.GrantedAuthority;

import java.util.Objects;

/**
 * An authority the platform derived from a user's assigned roles rather than one
 * Keycloak assigned directly — {@code ROLE_member}, or the built-in base of a custom
 * role (a custom role "based on cfo" also yields {@code ROLE_cfo}).
 *
 * <p>Derived authorities take part in every {@code hasRole(...)} check like any other.
 * They are a distinct type only so decisions about the user's <em>own</em> role
 * assignments can skip them: the RBAC access map is keyed by the custom role itself
 * (whose grants were copied from its base and then narrowed), so evaluating it
 * against the base as well would silently undo the narrowing.
 * See {@link EipRoles#assignedRoles}.</p>
 */
public final class EipDerivedAuthority implements GrantedAuthority {

    private final String authority;

    public EipDerivedAuthority(String authority) {
        this.authority = Objects.requireNonNull(authority, "authority");
    }

    public static EipDerivedAuthority role(String role) {
        return new EipDerivedAuthority(EipRoles.ROLE_PREFIX + role);
    }

    @Override
    public String getAuthority() {
        return authority;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof EipDerivedAuthority other && authority.equals(other.authority);
    }

    @Override
    public int hashCode() {
        return authority.hashCode();
    }

    @Override
    public String toString() {
        return authority;
    }
}
