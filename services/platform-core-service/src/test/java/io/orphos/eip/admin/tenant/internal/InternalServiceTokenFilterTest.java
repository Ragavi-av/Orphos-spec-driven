package io.orphos.eip.admin.tenant.internal;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the {@link InternalServiceTokenFilter} security gate.
 *
 * <p>The filter is the only thing standing between an attacker and the
 * service-to-service discovery endpoints — every code path here corresponds
 * to a way that gate could fail. We exercise the filter directly with mock
 * Servlet objects (no Spring Security context, no MockMvc) so the test pins
 * exactly the filter logic.</p>
 */
class InternalServiceTokenFilterTest {

    private static final String VALID_TOKEN = "s3cret-token-32-chars-long-xxxxxx";
    private InternalServiceTokenFilter filter;
    private MockHttpServletRequest req;
    private MockHttpServletResponse res;
    private RecordingChain chain;

    @BeforeEach
    void setUp() {
        filter = new InternalServiceTokenFilter();
        req = new MockHttpServletRequest();
        res = new MockHttpServletResponse();
        chain = new RecordingChain();
    }

    // ── Path-scope ─────────────────────────────────────────────────────────

    @Test
    void filterIsNoOpOnNonInternalPaths() throws Exception {
        // Token is set, but the path is outside /api/internal/ — should pass
        // through unchanged. The whole protected family is path-scoped.
        ReflectionTestUtils.setField(filter, "expectedToken", VALID_TOKEN);
        req.setRequestURI("/api/admin/tenants");
        req.setMethod("GET");

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void filterIsNoOpWhenTokenUnconfigured() throws Exception {
        // Blank token = dev mode = filter doesn't enforce. The /api/internal/
        // path is reached and passes through.
        ReflectionTestUtils.setField(filter, "expectedToken", "");
        req.setRequestURI("/api/internal/tenant-issuers");
        req.setMethod("GET");

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void filterIsNoOpWhenTokenNull() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", null);
        req.setRequestURI("/api/internal/tenants/abc");
        req.setMethod("GET");

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
    }

    // ── Enforcement: token set, request to /api/internal/ ────────────────

    @Test
    void rejectsRequestWithoutTokenHeader() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", VALID_TOKEN);
        req.setRequestURI("/api/internal/tenant-issuers");
        req.setMethod("GET");
        // No X-Service-Token header set.

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getContentType()).contains("application/json");
        assertThat(res.getContentAsString()).contains("missing_service_token");
    }

    @Test
    void rejectsRequestWithEmptyTokenHeader() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", VALID_TOKEN);
        req.setRequestURI("/api/internal/tenant-issuers");
        req.setMethod("GET");
        req.addHeader("X-Service-Token", "");

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsRequestWithWrongToken() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", VALID_TOKEN);
        req.setRequestURI("/api/internal/active-tenants");
        req.setMethod("GET");
        req.addHeader("X-Service-Token", "wrong-token-value-xxxxxxxxxxxxxxxx");

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsRequestWithTokenOfDifferentLength() throws Exception {
        // Short token != valid token. The length check is fine to leak (an
        // attacker knowing the token length doesn't give them the token).
        ReflectionTestUtils.setField(filter, "expectedToken", VALID_TOKEN);
        req.setRequestURI("/api/internal/tenant-issuers");
        req.setMethod("GET");
        req.addHeader("X-Service-Token", "short");

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void rejectsRequestWithTokenWithCorrectLengthButWrongContent() throws Exception {
        // Crucial: this exercises the constant-time content comparison rather
        // than the cheap length check. Wrong content of equal length still
        // rejects.
        ReflectionTestUtils.setField(filter, "expectedToken", VALID_TOKEN);
        // Build a same-length string that differs only in the LAST char so
        // the comparison must run end-to-end.
        String sameLength = VALID_TOKEN.substring(0, VALID_TOKEN.length() - 1) + 'X';

        req.setRequestURI("/api/internal/tenant-issuers");
        req.setMethod("GET");
        req.addHeader("X-Service-Token", sameLength);

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isFalse();
        assertThat(res.getStatus()).isEqualTo(401);
    }

    @Test
    void acceptsRequestWithCorrectToken() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", VALID_TOKEN);
        req.setRequestURI("/api/internal/tenant-issuers");
        req.setMethod("GET");
        req.addHeader("X-Service-Token", VALID_TOKEN);

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
        // Filter doesn't touch the response on accept — the downstream chain
        // sets the status.
        assertThat(res.getStatus()).isEqualTo(200);
    }

    @Test
    void acceptsActiveTenantsEndpoint() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", VALID_TOKEN);
        req.setRequestURI("/api/internal/active-tenants");
        req.setMethod("GET");
        req.addHeader("X-Service-Token", VALID_TOKEN);

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
    }

    @Test
    void acceptsTenantByIdEndpoint() throws Exception {
        ReflectionTestUtils.setField(filter, "expectedToken", VALID_TOKEN);
        req.setRequestURI("/api/internal/tenants/11111111-1111-1111-1111-111111111111");
        req.setMethod("GET");
        req.addHeader("X-Service-Token", VALID_TOKEN);

        filter.doFilter(req, res, chain);

        assertThat(chain.invoked).isTrue();
    }

    // ── Minimal recording chain (avoids Mockito per project convention) ──

    private static class RecordingChain implements FilterChain {
        final AtomicBoolean called = new AtomicBoolean(false);
        boolean invoked = false;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            called.set(true);
            invoked = true;
        }
    }
}
