package io.orphos.eip.agent.controller;

import io.orphos.eip.agent.component.OrchestratorRegistryClient;
import io.orphos.eip.agent.config.SecurityConfig;
import io.orphos.eip.agent.util.OrchestratorProperties;
import io.orphos.eip.agent.vo.DiscoveredAgent;
import io.orphos.eip.common.security.RoleHierarchyAutoConfiguration;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.common.web.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deciding on an agent's proposed action executes it with internal credentials, so it is
 * restricted to executive-and-above. Runs the real SecurityConfig + method security with the
 * platform role hierarchy; no agent is live, so an authorised call ends in 503 (relay target
 * missing) rather than a real relay. No Mockito — see platform-core's AdminRbacTest.
 */
@WebMvcTest(controllers = ApprovalProxyController.class)
// GlobalExceptionHandler is imported as in production (CommonWebAutoConfiguration): its
// catch-all must not turn @PreAuthorize denials into 500s.
@Import({ SecurityConfig.class, GlobalExceptionHandler.class, ApprovalProxyRbacTest.StubBeans.class })
@ImportAutoConfiguration(RoleHierarchyAutoConfiguration.class)
@TestPropertySource(properties =
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration")
class ApprovalProxyRbacTest {

    @Autowired MockMvc mvc;

    @BeforeEach
    void bindTenant() {
        TenantContextHolder.set(TenantContext.of(UUID.randomUUID(), "rbac-test.invalid"));
    }

    @AfterEach
    void clearTenant() {
        TenantContextHolder.clear();
    }

    @Test @WithAnonymousUser
    void anonymousIsRejected() throws Exception {
        mvc.perform(post("/api/orchestrator/approvals/a1/approve")).andExpect(status().isUnauthorized());
    }

    @Test @WithMockUser(roles = "guest")
    void guestIsForbidden() throws Exception {
        mvc.perform(post("/api/orchestrator/approvals/a1/approve")).andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles = { "viewer", "member" })
    void viewerCannotApproveRejectEditOrList() throws Exception {
        mvc.perform(post("/api/orchestrator/approvals/a1/approve")).andExpect(status().isForbidden());
        mvc.perform(post("/api/orchestrator/approvals/a1/reject")).andExpect(status().isForbidden());
        mvc.perform(post("/api/orchestrator/approvals/a1/edit")).andExpect(status().isForbidden());
        mvc.perform(get("/api/orchestrator/approvals")).andExpect(status().isForbidden());
    }

    @Test @WithMockUser(roles = { "cfo", "member" })
    void cSuiteMayDecide() throws Exception {
        mvc.perform(post("/api/orchestrator/approvals/a1/approve")).andExpect(status().isServiceUnavailable());
    }

    @Test @WithMockUser(roles = { "admin", "member" })
    void adminMayDecideViaHierarchy() throws Exception {
        mvc.perform(post("/api/orchestrator/approvals/a1/reject")).andExpect(status().isServiceUnavailable());
    }

    @Test @WithMockUser(roles = { "executive", "member" })
    void pathTraversalInActionIdIsRejected() throws Exception {
        mvc.perform(post("/api/orchestrator/approvals/..%2F..%2Fadmin/approve"))
                .andExpect(status().is4xxClientError());
        mvc.perform(post("/api/orchestrator/approvals/a.b/approve")).andExpect(status().isBadRequest());
    }

    @TestConfiguration
    static class StubBeans {
        // OrchestratorProperties itself comes from the application's @EnableConfigurationProperties.
        @Bean
        OrchestratorRegistryClient orchestratorRegistryClient(OrchestratorProperties props) {
            return new OrchestratorRegistryClient(props) {
                @Override
                public List<DiscoveredAgent> activeAgents(UUID tenantId) {
                    return List.of();
                }
            };
        }

        @Bean
        AuthenticationManagerResolver<HttpServletRequest> jwtAuthenticationManagerResolver() {
            return request -> authentication -> null;
        }
    }
}
