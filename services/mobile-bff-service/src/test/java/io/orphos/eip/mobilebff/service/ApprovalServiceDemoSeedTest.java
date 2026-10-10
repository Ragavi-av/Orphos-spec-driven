package io.orphos.eip.mobilebff.service;

import io.orphos.eip.mobilebff.component.DownstreamClient;
import io.orphos.eip.mobilebff.repository.WorkspaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Demo approvals must only ever be written into the configured demo tenant. Seeding any
 * tenant with an empty list put fake approvals in front of real executives on mobile while
 * the web (which hides approvals without a task) showed none.
 */
class ApprovalServiceDemoSeedTest {

    private static final String DEMO_TENANT = "127a2ec3-cb19-365e-9ebe-c9c19ed5e5d8";
    private static final String REAL_TENANT = "5d6f0c1e-0000-4000-8000-000000000001";

    private DownstreamClient ds;
    private WorkspaceStore workspace;
    private MobileConfigService config;

    @BeforeEach
    void setUp() {
        ds = mock(DownstreamClient.class);
        workspace = mock(WorkspaceStore.class);
        config = mock(MobileConfigService.class);
        when(config.get(anyString())).thenReturn(MobileConfigService.defaults());
        when(workspace.list(anyString(), anyString(), anyString())).thenReturn(new ArrayList<>());
    }

    private ApprovalService service(boolean seedEnabled, String demoTenantId) {
        return new ApprovalService(ds, workspace, config, "http://decision-log", "http://mail-insight",
                seedEnabled, demoTenantId);
    }

    @Test
    void doesNotSeedARealTenant() {
        service(true, DEMO_TENANT).listAndSync(REAL_TENANT, "owner-1", "Bearer t");
        verify(workspace, never()).add(eq("Approval"), anyString(), anyString(), any());
    }

    @Test
    void doesNotSeedWhenNoDemoTenantIsConfigured() {
        service(true, "").listAndSync(DEMO_TENANT, "owner-1", "Bearer t");
        verify(workspace, never()).add(eq("Approval"), anyString(), anyString(), any());
    }

    @Test
    void doesNotSeedWhenDisabled() {
        service(false, DEMO_TENANT).listAndSync(DEMO_TENANT, "owner-1", "Bearer t");
        verify(workspace, never()).add(eq("Approval"), anyString(), anyString(), any());
    }

    @Test
    void seedsOnlyTheDemoTenant() {
        service(true, DEMO_TENANT).listAndSync(DEMO_TENANT, "owner-1", "Bearer t");
        verify(workspace, atLeastOnce()).add(eq("Approval"), eq(DEMO_TENANT), eq("owner-1"), any());
    }
}
