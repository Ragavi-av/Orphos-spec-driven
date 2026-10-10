package io.orphos.eip.common.tenant;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises {@link TenantResolutionFilter} request-by-request. Each test pins
 * exactly one resolution path — JWT claim, platform-admin header, trusted
 * internal header, host lookup, or rejection — so a regression in any
 * one path is unambiguous.
 *
 * <p>No Spring context: the filter is constructed by hand and driven with
 * Spring's mock Servlet objects.</p>
 */
class TenantResolutionFilterTest {

    private static final UUID TENANT_A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT_B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private TenantProperties props;
    private RecordingLookup lookup;
    private TenantResolutionFilter filter;
    private MockHttpServletRequest req;
    private MockHttpServletResponse res;
    private RecordingChain chain;

    @BeforeEach
    void setUp() {
        props = new TenantProperties();
        // Defaults are fine: jwtClaim=tenant_id, headerName=X-Tenant-Id,
        // trustedHeaderSources=[loopback], excludes actuator/swagger paths.
        lookup = new RecordingLookup();
        filter = new TenantResolutionFilter(props, lookup);
        req = new MockHttpServletRequest();
        res = new MockHttpServletResponse();
        chain = new RecordingChain();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    // ── JWT claim path (the dominant case) ───────────────────────────────

    @Test
    void bindsTenantFromJwtClaim() throws Exception {
        Jwt jwt = jwtBuilder()
                .claim("tenant_id", TENANT_A.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));

        req.setRequestURI("/api/anything");
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.tenantSeen).isEqualTo(TENANT_A);
        // Bound during the chain, cleared after.
        assertThat(TenantContextHolder.currentOrNull()).isNull();
    }

    @Test
    void noBindingWhenJwtHasNoTenantClaim() throws Exception {
        Jwt jwt = jwtBuilder()
                // No tenant_id claim.
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));

        req.setRequestURI("/api/anything");
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.tenantSeen).isNull();
    }

    @Test
    void invalidUuidInJwtClaimYieldsNoBinding() throws Exception {
        Jwt jwt = jwtBuilder()
                .claim("tenant_id", "not-a-uuid")
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));

        req.setRequestURI("/api/anything");
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        // Malformed claim must not produce a bound context — falls through
        // safely rather than crashing the request.
        assertThat(chain.tenantSeen).isNull();
    }

    // ── Tenant user header spoofing rejected ─────────────────────────────

    @Test
    void tenantScopedJwtUserCannotSpoofOtherTenantViaHeader() throws Exception {
        // JWT identifies the user as tenant A. They submit X-Tenant-Id: tenant B
        // in an attempt to scope queries to a different tenant. The filter MUST
        // use the JWT claim and ignore the header.
        Jwt jwt = jwtBuilder()
                .claim("tenant_id", TENANT_A.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));

        req.setRequestURI("/api/anything");
        req.addHeader("X-Tenant-Id", TENANT_B.toString());
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.tenantSeen)
                .as("Header MUST NOT override JWT claim for tenant-scoped users")
                .isEqualTo(TENANT_A);
    }

    // ── Platform-admin override ───────────────────────────────────────────

    @Test
    void platformAdminWithHeaderBindsToHeaderValue() throws Exception {
        // Platform admin JWT — recognised by ROLE_platform_admin authority
        // AND by the issuer URL ending in /realms/platform.
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .issuer("http://localhost:8443/realms/platform")
                .subject("ops-admin")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt,
                        List.of(new SimpleGrantedAuthority("ROLE_platform_admin"))));

        req.setRequestURI("/api/admin/tenants");
        req.addHeader("X-Tenant-Id", TENANT_B.toString());
        chain.captureTenantOnInvoke();
        chain.captureFlagsOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.tenantSeen).isEqualTo(TENANT_B);
        assertThat(chain.platformAdminSeen)
                .as("Platform admin acting on behalf of a tenant carries the flag forward")
                .isTrue();
    }

    @Test
    void platformAdminWithoutHeaderBindsNothing() throws Exception {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .issuer("http://localhost:8443/realms/platform")
                .subject("ops-admin")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt,
                        List.of(new SimpleGrantedAuthority("ROLE_platform_admin"))));

        req.setRequestURI("/api/admin/tenants");
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        // No header → no binding. Platform-admin endpoints that need to operate
        // cross-tenant must accept the header explicitly.
        assertThat(chain.tenantSeen).isNull();
    }

    @Test
    void tenantRealmTokenWithPlatformAdminRoleCannotSwitchTenant() throws Exception {
        // A tenant realm can define a realm role literally named "platform_admin".
        // Holding it on a tenant-issued token must NOT unlock the header override.
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .issuer("http://localhost:8443/realms/tenant-a")
                .subject("tenant-user")
                .claim("tenant_id", TENANT_A.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt,
                        List.of(new SimpleGrantedAuthority("ROLE_platform_admin"))));

        req.setRequestURI("/api/admin/tenants");
        req.addHeader("X-Tenant-Id", TENANT_B.toString());
        chain.captureTenantOnInvoke();
        chain.captureFlagsOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.tenantSeen).as("header ignored; JWT tenant wins").isEqualTo(TENANT_A);
        assertThat(chain.platformAdminSeen).isFalse();
    }

    @Test
    void platformRealmTokenWithoutRoleIsNotPlatformAdmin() throws Exception {
        Jwt jwt = Jwt.withTokenValue("t")
                .header("alg", "none")
                .issuer("http://localhost:8443/realms/platform")
                .subject("platform-user-without-role")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));

        req.setRequestURI("/api/admin/tenants");
        req.addHeader("X-Tenant-Id", TENANT_B.toString());
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.tenantSeen).as("no role → no header override").isNull();
    }

    // ── Trusted internal header (no JWT) ──────────────────────────────────

    @Test
    void trustedInternalHeaderAcceptedWhenNoJwt() throws Exception {
        // No JWT → service-to-service call. Default trusted sources are
        // loopback only, so a request from 127.0.0.1 with the header
        // is honoured.
        req.setRequestURI("/api/internal/kafka-relay");
        req.addHeader("X-Tenant-Id", TENANT_A.toString());
        req.setRemoteAddr("127.0.0.1");
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.tenantSeen).isEqualTo(TENANT_A);
    }

    @Test
    void untrustedSourceHeaderRejected() throws Exception {
        req.setRequestURI("/api/internal/kafka-relay");
        req.addHeader("X-Tenant-Id", TENANT_A.toString());
        // Public IP — not in the trusted sources list.
        req.setRemoteAddr("203.0.113.10");
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.tenantSeen)
                .as("Header from untrusted source must not bind the holder")
                .isNull();
    }

    @Test
    void anonymousAuthBehavesAsUnauthenticated() throws Exception {
        // Spring Security can populate the context with an anonymous token
        // even when nothing's authenticated. The filter should treat that as
        // "no JWT" — header path still requires a trusted source.
        SecurityContextHolder.getContext().setAuthentication(
                new AnonymousAuthenticationToken(
                        "key", "anonymous",
                        List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        req.setRequestURI("/api/internal/foo");
        req.addHeader("X-Tenant-Id", TENANT_A.toString());
        req.setRemoteAddr("203.0.113.10");
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.tenantSeen).isNull();
    }

    // ── Host header lookup ────────────────────────────────────────────────

    @Test
    void hostHeaderResolvesViaTenantLookup() throws Exception {
        lookup.register(TENANT_A, "acme.example", true);

        req.setRequestURI("/api/public/tenants/by-domain/acme.example");
        req.addHeader("Host", "acme.example");
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.tenantSeen).isEqualTo(TENANT_A);
    }

    @Test
    void hostHeaderStripsPortBeforeLookup() throws Exception {
        lookup.register(TENANT_A, "acme.example", true);

        req.setRequestURI("/api/public/tenants/by-domain/acme.example");
        req.addHeader("Host", "acme.example:5173");
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.tenantSeen).isEqualTo(TENANT_A);
    }

    @Test
    void inactiveTenantInLookupIsNotBound() throws Exception {
        lookup.register(TENANT_A, "acme.example", false);

        req.setRequestURI("/api/public/tenants/by-domain/acme.example");
        req.addHeader("Host", "acme.example");
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.tenantSeen)
                .as("Suspended/deleted tenants must not be bound")
                .isNull();
    }

    // ── Exclude paths ─────────────────────────────────────────────────────

    @Test
    void excludePathIsSkippedEntirely() throws Exception {
        req.setRequestURI("/actuator/health");
        // Even with a perfectly valid JWT, the filter shouldn't touch the holder
        // on excluded paths — those probes pre-date tenant resolution.
        Jwt jwt = jwtBuilder().claim("tenant_id", TENANT_A.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        // The chain still runs, but tenant context is NOT bound because
        // shouldNotFilter returned true.
        assertThat(chain.invoked).isTrue();
        assertThat(chain.tenantSeen).isNull();
    }

    @Test
    void filterDisabledByPropertyIsSkipped() throws Exception {
        props.setEnabled(false);
        Jwt jwt = jwtBuilder().claim("tenant_id", TENANT_A.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));

        req.setRequestURI("/api/anything");
        chain.captureTenantOnInvoke();

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(chain.tenantSeen).isNull();
    }

    // ── Cleanup ───────────────────────────────────────────────────────────

    @Test
    void tenantContextIsClearedAfterChainCompletes() throws Exception {
        Jwt jwt = jwtBuilder().claim("tenant_id", TENANT_A.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));

        req.setRequestURI("/api/anything");

        filter.doFilter(req, res, chain);

        // After the chain returns, the next request on this thread must start fresh.
        assertThat(TenantContextHolder.currentOrNull())
                .as("Filter must clear the holder so the next request starts clean")
                .isNull();
    }

    @Test
    void tenantContextIsClearedEvenOnChainException() throws Exception {
        Jwt jwt = jwtBuilder().claim("tenant_id", TENANT_A.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of()));

        req.setRequestURI("/api/anything");
        chain.throwOnInvoke(new RuntimeException("downstream boom"));

        try {
            filter.doFilter(req, res, chain);
        } catch (RuntimeException expected) {
            // Filter rethrows the chain exception.
        }
        assertThat(TenantContextHolder.currentOrNull())
                .as("Filter's finally block must clear the holder even on chain failure")
                .isNull();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static Jwt.Builder jwtBuilder() {
        return Jwt.withTokenValue("t")
                .header("alg", "none")
                .issuer("http://localhost:8443/realms/tenant-abc")
                .subject("user-sub")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
    }

    /**
     * Captures the {@link TenantContextHolder} state at the moment the filter
     * chain hands off downstream. Tests assert on these captured values
     * because the filter clears the holder after the chain returns — so
     * we have to snapshot during, not after.
     */
    private static class RecordingChain implements FilterChain {
        boolean invoked = false;
        UUID tenantSeen = null;
        boolean platformAdminSeen = false;
        boolean captureTenant = false;
        boolean captureFlags = false;
        RuntimeException toThrow = null;
        AtomicReference<TenantContext> snapshot = new AtomicReference<>();

        void captureTenantOnInvoke() { captureTenant = true; }
        void captureFlagsOnInvoke() { captureFlags = true; }
        void throwOnInvoke(RuntimeException e) { toThrow = e; }

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            invoked = true;
            TenantContext ctx = TenantContextHolder.currentOrNull();
            snapshot.set(ctx);
            if (captureTenant) tenantSeen = (ctx != null) ? ctx.tenantId() : null;
            if (captureFlags)  platformAdminSeen = (ctx != null) && ctx.platformAdmin();
            if (toThrow != null) throw toThrow;
        }
    }

    /**
     * Hand-rolled {@link TenantLookupService} stub. Keeps tests free of
     * Mockito (per the project's documented preference).
     */
    private static class RecordingLookup implements TenantLookupService {
        private TenantSummary registered;

        void register(UUID id, String domain, boolean active) {
            registered = new TenantSummary(id, domain, active);
        }

        @Override
        public Optional<TenantSummary> findByDomain(String domain) {
            if (registered != null && registered.domain().equalsIgnoreCase(domain)) {
                return Optional.of(registered);
            }
            return Optional.empty();
        }

        @Override
        public Optional<TenantSummary> findById(UUID tenantId) {
            if (registered != null && registered.tenantId().equals(tenantId)) {
                return Optional.of(registered);
            }
            return Optional.empty();
        }
    }
}
