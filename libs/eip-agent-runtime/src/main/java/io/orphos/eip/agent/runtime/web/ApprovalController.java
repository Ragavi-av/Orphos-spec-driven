package io.orphos.eip.agent.runtime.web;

import io.orphos.eip.agent.runtime.approval.ApprovalService;
import io.orphos.eip.agent.spec.ProposedAction;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The approval-inbox API exposed by the runtime (TO-BE design §8). Because {@link ProposedAction}s
 * share one envelope and one store, this is the single inbox regardless of which agent proposed the
 * write. Approving runs the write tool via the MCP gateway; rejecting discards it.
 */
@RestController
@RequestMapping("/approvals")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    /** Pending proposals for the calling tenant (from the {@code X-Tenant-Id} header). */
    @GetMapping
    public List<ProposedAction> pending(@RequestHeader("X-Tenant-Id") UUID tenantId) {
        return service.pending(tenantId);
    }

    /** Approve + execute a proposal; returns the tool's result text. */
    @PostMapping("/{actionId}/approve")
    public ResponseEntity<Map<String, String>> approve(@PathVariable UUID actionId) {
        String result = service.approve(actionId);
        return ResponseEntity.ok(Map.of("actionId", actionId.toString(), "state", "EXECUTED", "result", result));
    }

    /** Reject a proposal; nothing is executed. */
    @PostMapping("/{actionId}/reject")
    public ResponseEntity<Void> reject(@PathVariable UUID actionId) {
        service.reject(actionId);
        return ResponseEntity.noContent().build();
    }

    /** Body of an edit-before-approve request: the replacement arguments + an optional new summary. */
    public record EditRequest(Map<String, Object> arguments, String humanSummary) {}

    /**
     * Edit a PENDING proposal's arguments before approving. If the tool is bound to a confirmation
     * token (e.g. mail.send_email), the runtime re-issues it for the edited content automatically.
     */
    @PostMapping("/{actionId}/edit")
    public ResponseEntity<ProposedAction> edit(@PathVariable UUID actionId, @RequestBody EditRequest req) {
        ProposedAction updated = service.edit(actionId,
                req == null ? null : req.arguments(),
                req == null ? null : req.humanSummary());
        return ResponseEntity.ok(updated);
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(NoSuchElementException ex) {
        return ResponseEntity.status(404).body(Map.of("error", ex.getMessage()));
    }

    @org.springframework.web.bind.annotation.ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(409).body(Map.of("error", ex.getMessage()));
    }
}
