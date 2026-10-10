package io.orphos.eip.common.security;

import java.util.Set;

/**
 * SPI for the list of issuer URLs this service trusts. Re-evaluated on every
 * JWT validation (results from
 * {@link MultiIssuerJwtAutoConfiguration#multiIssuerAuthenticationManagerResolver}
 * are cached per-issuer, but the membership check is cheap), so implementations
 * MAY return a live, mutable result.
 *
 * <p>Default implementation: {@link ConfigBackedAllowedIssuersProvider}, which
 * reads from {@code eip.security.allowed-issuers}. platform-core-service overrides this
 * bean with a DB-backed implementation that walks the {@code Tenant} doc type.</p>
 */
public interface AllowedIssuersProvider {

    /**
     * @return the set of issuer URLs (exact match) this service will accept JWTs from.
     * MUST include the platform-superadmin realm issuer where applicable —
     * implementations decide whether to bake that in or compose with config.
     */
    Set<String> allowedIssuers();
}
