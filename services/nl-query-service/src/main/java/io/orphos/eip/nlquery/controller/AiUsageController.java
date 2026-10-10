package io.orphos.eip.nlquery.controller;

import io.orphos.eip.nlquery.service.AiUsageRecorder;
import io.orphos.eip.nlquery.util.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only views over the AiUsage audit table.
 *
 *  - /me/usage           self-serve: current user's history + aggregates
 *  - /usage/summary      admin-only: org-wide aggregates
 *  - /users/{sub}/usage  admin-only: a specific user's history + aggregates
 *
 * No endpoint returns prompt/response text — only metric fields.
 */
@RestController
@RequestMapping("/api/nl-query")
public class AiUsageController {

    private final AiUsageRecorder recorder;

    public AiUsageController(AiUsageRecorder recorder) {
        this.recorder = recorder;
    }

    // ── Self-serve ─────────────────────────────────────────────────────────

    @GetMapping("/me/usage")
    public ResponseEntity<Map<String, Object>> myUsage(
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        String sub = currentSub();
        return ResponseEntity.ok(buildView(sub, limit));
    }

    // ── Admin views ────────────────────────────────────────────────────────

    @GetMapping("/usage/summary")
    @PreAuthorize("hasAnyRole('admin','platform_admin','data_ai_admin')")
    public ResponseEntity<Map<String, Object>> orgSummary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scope", "org");
        out.put("aggregates", recorder.queryAggregates(null));
        return ResponseEntity.ok(out);
    }

    @GetMapping("/users/{userSub}/usage")
    @PreAuthorize("hasAnyRole('admin','platform_admin','data_ai_admin')")
    public ResponseEntity<Map<String, Object>> userUsage(
            @PathVariable String userSub,
            @RequestParam(name = "limit", defaultValue = "50") int limit) {
        return ResponseEntity.ok(buildView(userSub, limit));
    }

    // ── Shared ─────────────────────────────────────────────────────────────

    private Map<String, Object> buildView(String userSub, int limit) {
        List<Map<String, Object>> recent     = recorder.queryRecent(userSub, limit);
        List<Map<String, Object>> aggregates = recorder.queryAggregates(userSub);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("userSub", userSub);
        out.put("aggregates", aggregates);
        out.put("recent", recent);
        return out;
    }

    private String currentSub() {
        return CurrentUser.sub()
                .orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }
}
