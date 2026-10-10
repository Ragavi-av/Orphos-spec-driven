package io.orphos.eip.mobilebff.service;

import io.orphos.eip.mobilebff.component.DownstreamClient;
import io.orphos.eip.mobilebff.repository.WorkspaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An approval is recorded as decided only once its linked task / decision is closed, so the
 * approval store, mail-insight and the decision log can never disagree after a partial failure.
 */
class ApprovalServiceDecideTest {

    private static final String TENANT = "127a2ec3-cb19-365e-9ebe-c9c19ed5e5d8";
    private static final String OWNER = "owner-1";

    private DownstreamClient ds;
    private WorkspaceStore workspace;
    private ApprovalService service;

    @BeforeEach
    void setUp() {
        ds = mock(DownstreamClient.class);
        workspace = mock(WorkspaceStore.class);
        service = new ApprovalService(ds, workspace, mock(MobileConfigService.class),
                "http://decision-log", "http://mail-insight", false, "");
    }

    private void pendingApproval(String sourceRef) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", "appr-1");
        row.put("sourceId", "task-" + sourceRef);
        row.put("sourceRef", sourceRef);
        row.put("status", "PENDING");
        row.put("expiresAt", Instant.now().toEpochMilli() + 3_600_000L);
        when(workspace.findById("Approval", TENANT, OWNER, "appr-1")).thenReturn(row);
    }

    @Test
    void emailTaskNotClosed_decisionNotRecorded() {
        pendingApproval("mi-42");
        when(ds.patch(eq("http://mail-insight"), anyString(), anyString(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.decide(TENANT, OWNER, "appr-1", "APPROVE", "Bearer t"))
                .isInstanceOf(ApprovalService.LinkedTaskUpdateException.class);
        verify(workspace, never()).updateById(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void emailTaskClosed_decisionRecorded() {
        pendingApproval("mi-42");
        when(ds.patch(eq("http://mail-insight"), anyString(), anyString(), anyString(), any())).thenReturn(true);

        service.decide(TENANT, OWNER, "appr-1", "APPROVE", "Bearer t");
        verify(workspace).updateById(eq("Approval"), eq(TENANT), eq(OWNER), eq("appr-1"), anyMap());
    }

    @Test
    void decisionLogNotUpdated_decisionNotRecordedAndTaskLeftOpen() {
        pendingApproval("ds-decision-7");
        when(ds.patch(eq("http://decision-log"), anyString(), anyString(), anyString(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.decide(TENANT, OWNER, "appr-1", "REJECT", "Bearer t"))
                .isInstanceOf(ApprovalService.LinkedTaskUpdateException.class);
        verify(ds, never()).patch(eq("http://mail-insight"), anyString(), anyString(), anyString(), any());
        verify(workspace, never()).updateById(anyString(), anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    void decisionLogUpdated_recordedEvenIfMailOverrideFails() {
        pendingApproval("ds-decision-7");
        when(ds.patch(eq("http://decision-log"), startsWith("/api/decisions/7/"), anyString(), anyString(), any()))
                .thenReturn(true);
        when(ds.patch(eq("http://mail-insight"), anyString(), anyString(), anyString(), any())).thenReturn(false);

        service.decide(TENANT, OWNER, "appr-1", "REJECT", "Bearer t");
        verify(workspace).updateById(eq("Approval"), eq(TENANT), eq(OWNER), eq("appr-1"), anyMap());
    }
}
