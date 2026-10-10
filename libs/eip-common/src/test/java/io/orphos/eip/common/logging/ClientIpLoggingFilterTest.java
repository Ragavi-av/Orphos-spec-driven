package io.orphos.eip.common.logging;

import jakarta.servlet.FilterChain;
import org.apache.logging.log4j.ThreadContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the three properties the JSON log layout and the filter chain depend on:
 * the IP is in the MDC <em>while</em> the chain runs, it is gone afterwards, and
 * the filter still sorts ahead of every other EIP filter.
 *
 * <p>No Spring context: the filter is constructed by hand and driven with
 * Spring's mock Servlet objects, matching {@code TenantResolutionFilterTest}.</p>
 */
class ClientIpLoggingFilterTest {

    private final ClientIpLoggingFilter filter = new ClientIpLoggingFilter();

    @AfterEach
    void clearMdc() {
        ThreadContext.clearAll();
    }

    @Test
    @DisplayName("the caller's IP is in the MDC for the duration of the chain")
    void ipVisibleDuringChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        AtomicReference<String> seenByChain = new AtomicReference<>();
        FilterChain chain = (req, res) -> seenByChain.set(ThreadContext.get(ClientIpLoggingFilter.MDC_KEY));

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(seenByChain.get()).isEqualTo("203.0.113.7");
    }

    @Test
    @DisplayName("the MDC entry is removed afterwards, so it cannot leak onto the next request")
    void mdcClearedAfterChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> { });

        assertThat(ThreadContext.get(ClientIpLoggingFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("the MDC entry is removed even when the chain throws")
    void mdcClearedWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        FilterChain boom = (req, res) -> { throw new IllegalStateException("downstream failed"); };

        try {
            filter.doFilter(request, new MockHttpServletResponse(), boom);
        } catch (Exception expected) {
            // the filter must not swallow it; we only care that the MDC was cleaned up
        }

        assertThat(ThreadContext.get(ClientIpLoggingFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("runs before the rate-limit, tenant-resolution and access-enforcement filters")
    void ordersFirst() {
        assertThat(filter.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
    }

    @Test
    @DisplayName("the MDC key still matches the one log4j2-json-layout.json resolves")
    void mdcKeyMatchesLogLayoutContract() {
        assertThat(ClientIpLoggingFilter.MDC_KEY).isEqualTo("clientIp");
    }
}
