package io.orphos.eip.dataingestion.component;

import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.dataingestion.repository.AuditLogRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** Requirement 8.4 — audit-trail capture and action derivation. */
class AuditTrailInterceptorTest {

    private static final String BASE = "/api/data-ingestion/admin";

    private AuditLogRepository repo;
    private AuditTrailInterceptor interceptor;
    private UUID tenantId;

    @BeforeEach
    void setUp() {
        repo = mock(AuditLogRepository.class);
        interceptor = new AuditTrailInterceptor(repo);
        tenantId = UUID.randomUUID();
        TenantContextHolder.set(new TenantContext(tenantId, null, false));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void recordsSuccessfulMutatingRequestWithJwtActor() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .subject("1234-sub").claim("preferred_username", "alice").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        MockHttpServletRequest req = request("POST", BASE + "/datasources");
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(201);

        interceptor.afterCompletion(req, res, new Object(), null);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(repo).insert(eq(tenantId.toString()), eq("alice"),
                eq("DATASOURCE_CREATED"), detail.capture());
        assertThat(detail.getValue()).isEqualTo("POST " + BASE + "/datasources -> 201");
    }

    @Test
    void fallsBackToSubjectWhenNoUsernameClaim() {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("1234-sub").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        MockHttpServletRequest req = request("DELETE", BASE + "/datasources/ds-1");
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(204);

        interceptor.afterCompletion(req, res, new Object(), null);

        verify(repo).insert(eq(tenantId.toString()), eq("1234-sub"),
                eq("DATASOURCE_DELETED"), anyString());
    }

    @Test
    void appendsControllerProvidedDetail() {
        MockHttpServletRequest req = request("PUT", BASE + "/datasources/ds-1/classification");
        req.setAttribute(AuditTrailInterceptor.DETAIL_ATTRIBUTE, "classification=RESTRICTED tags=[pii]");
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);

        interceptor.afterCompletion(req, res, new Object(), null);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(repo).insert(eq(tenantId.toString()), anyString(),
                eq("DATASOURCE_CLASSIFIED"), detail.capture());
        assertThat(detail.getValue()).contains("classification=RESTRICTED tags=[pii]");
    }

    @Test
    void skipsReadsFailuresForeignPathsAndMissingTenant() {
        // GET — not mutating
        interceptor.afterCompletion(request("GET", BASE + "/audit"), ok(), new Object(), null);
        // 4xx — not successful
        MockHttpServletResponse notFound = new MockHttpServletResponse();
        notFound.setStatus(404);
        interceptor.afterCompletion(request("DELETE", BASE + "/datasources/ds-9"), notFound, new Object(), null);
        // handler exception — not successful
        interceptor.afterCompletion(request("POST", BASE + "/mappings"), ok(), new Object(), new RuntimeException("boom"));
        // outside the admin surface
        interceptor.afterCompletion(request("POST", "/api/other/thing"), ok(), new Object(), null);
        // no tenant bound — skip gracefully, never crash
        TenantContextHolder.clear();
        interceptor.afterCompletion(request("POST", BASE + "/datasources"), ok(), new Object(), null);

        verifyNoInteractions(repo);
    }

    @Test
    void auditFailureNeverBreaksTheRequest() {
        doThrow(new RuntimeException("arcade down"))
                .when(repo).insert(anyString(), anyString(), anyString(), anyString());
        // Must not throw.
        interceptor.afterCompletion(request("POST", BASE + "/datasources"), ok(), new Object(), null);
    }

    @Test
    void derivesActionsFromMethodAndPath() {
        assertThat(action("POST", "/datasources")).isEqualTo("DATASOURCE_CREATED");
        assertThat(action("PUT", "/datasources/ds-1")).isEqualTo("DATASOURCE_UPDATED");
        assertThat(action("DELETE", "/datasources/ds-1")).isEqualTo("DATASOURCE_DELETED");
        assertThat(action("POST", "/datasources/ds-1/test")).isEqualTo("DATASOURCE_TESTED");
        assertThat(action("POST", "/datasources/test")).isEqualTo("DATASOURCE_TESTED");
        assertThat(action("PUT", "/datasources/ds-1/classification")).isEqualTo("DATASOURCE_CLASSIFIED");
        assertThat(action("POST", "/mappings")).isEqualTo("MAPPING_CREATED");
        assertThat(action("POST", "/mappings/validate")).isEqualTo("MAPPING_VALIDATED");
        assertThat(action("PUT", "/mappings/map-1")).isEqualTo("MAPPING_UPDATED");
        assertThat(action("POST", "/jobs/job-1/trigger")).isEqualTo("JOB_TRIGGERED");
        assertThat(action("POST", "/jobs/job-1/run")).isEqualTo("JOB_TRIGGERED");
        assertThat(action("POST", "/jobs")).isEqualTo("JOB_CREATED");
        assertThat(action("POST", "/datasources/ds-1/metadata/tables/ORDERS/refresh"))
                .isEqualTo("METADATA_DISCOVERED");
    }

    private static String action(String method, String rel) {
        return AuditTrailInterceptor.deriveAction(method, BASE + rel);
    }

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRequestURI(uri);
        return req;
    }

    private static MockHttpServletResponse ok() {
        MockHttpServletResponse res = new MockHttpServletResponse();
        res.setStatus(200);
        return res;
    }
}
