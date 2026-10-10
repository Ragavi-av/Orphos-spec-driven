package io.orphos.eip.common.access;

import io.orphos.eip.common.security.EipDerivedAuthority;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Drives {@link AccessEnforcementFilter} by hand — no Spring context, no Mockito. */
class AccessEnforcementFilterTest {

    private static final LinkedHashMap<String, String> ROUTES = new LinkedHashMap<>(Map.of(
            "/api/nl-query/ask", "ask-orphos"));

    private MockHttpServletRequest req;
    private MockHttpServletResponse res;
    private MockFilterChain chain;

    @BeforeEach
    void setUp() {
        req = new MockHttpServletRequest("POST", "/api/nl-query/ask");
        res = new MockHttpServletResponse();
        chain = new MockFilterChain();
        TenantContextHolder.set(TenantContext.of(UUID.randomUUID(), "t.invalid"));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContextHolder.clear();
    }

    private static void authenticate(GrantedAuthority... authorities) {
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken("u", "p", List.of(authorities)));
    }

    private static SimpleGrantedAuthority role(String r) {
        return new SimpleGrantedAuthority("ROLE_" + r);
    }

    private boolean passed() {
        return chain.getRequest() != null;
    }

    @Test
    void failsClosedByDefaultWhenMapUnavailable() throws Exception {
        authenticate(role("viewer"));
        new AccessEnforcementFilter(() -> null, ROUTES).doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(passed()).isFalse();
    }

    @Test
    void failsOpenOnlyWhenOptedIn() throws Exception {
        authenticate(role("viewer"));
        AccessEnforcementFilter filter = new AccessEnforcementFilter(() -> null, ROUTES);
        filter.setFailOpen(true);
        filter.doFilter(req, res, chain);
        assertThat(passed()).isTrue();
    }

    @Test
    void failClosedResourcesStayClosedEvenInOpenMode() throws Exception {
        authenticate(role("viewer"));
        AccessEnforcementFilter filter = new AccessEnforcementFilter(() -> null, ROUTES, Set.of("ask-orphos"));
        filter.setFailOpen(true);
        filter.doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(503);
    }

    @Test
    void guestOnlyUserIsDenied() throws Exception {
        authenticate(role("guest"), role("default-roles-eip"), role("offline_access"));
        new AccessEnforcementFilter(Map::of, ROUTES).doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void viewerAllowedByDefaultAndDeniedByGrant() throws Exception {
        authenticate(role("viewer"));
        new AccessEnforcementFilter(Map::of, ROUTES).doFilter(req, res, chain);
        assertThat(passed()).isTrue();

        // Fresh request: OncePerRequestFilter marks the first one as already filtered.
        MockHttpServletRequest again = new MockHttpServletRequest("POST", "/api/nl-query/ask");
        MockHttpServletResponse denied = new MockHttpServletResponse();
        MockFilterChain deniedChain = new MockFilterChain();
        new AccessEnforcementFilter(() -> Map.of("viewer", Map.of("ask-orphos", "deny")), ROUTES)
                .doFilter(again, denied, deniedChain);
        assertThat(denied.getStatus()).isEqualTo(403);
    }

    @Test
    void derivedBaseRoleDoesNotUndoCustomRoleNarrowing() throws Exception {
        // operations_head was copied from cfo and then denied ask-orphos. The derived
        // ROLE_cfo (for coarse rules) must not re-open it through cfo's default allow.
        authenticate(role("operations_head"), EipDerivedAuthority.role("cfo"), EipDerivedAuthority.role("member"));
        new AccessEnforcementFilter(() -> Map.of("operations_head", Map.of("ask-orphos", "deny")), ROUTES)
                .doFilter(req, res, chain);
        assertThat(res.getStatus()).isEqualTo(403);
    }

    @Test
    void platformAdminAndInternalCallersBypass() throws Exception {
        authenticate(role("platform_admin"));
        new AccessEnforcementFilter(() -> null, ROUTES).doFilter(req, res, chain);
        assertThat(passed()).isTrue();

        SecurityContextHolder.clearContext();
        authenticate(role("service_internal"));
        MockFilterChain internal = new MockFilterChain();
        new AccessEnforcementFilter(() -> null, ROUTES).doFilter(
                new MockHttpServletRequest("POST", "/api/nl-query/ask"), new MockHttpServletResponse(), internal);
        assertThat(internal.getRequest()).isNotNull();
    }

    @Test
    void unmappedPathsAreIgnored() throws Exception {
        authenticate(role("guest"));
        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/api/nl-query/health");
        new AccessEnforcementFilter(() -> null, ROUTES).doFilter(other, res, chain);
        assertThat(passed()).isTrue();
    }
}
