package io.orphos.eip.nlquery.controller;

import io.orphos.eip.common.security.InternalTokens;
import io.orphos.eip.nlquery.component.PromptConfigStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Internal, service-to-service read access to the tenant's active prompt
 * texts. Lets sibling services (mail-insight, doc-ingest) build their LLM
 * prompts from the same admin-editable, versioned store that backs the
 * copilot — the callers keep a bundled fallback so a miss here never breaks
 * their pipeline.
 *
 * <p>Guarded like {@link InternalCompletionController}: a shared
 * {@code X-Internal-Token} plus the acting tenant in {@code X-Tenant-Id}.</p>
 */
@RestController
@RequestMapping("/api/nl-query/internal")
public class InternalPromptController {

    private final PromptConfigStore prompts;
    private final String internalToken;

    public InternalPromptController(PromptConfigStore prompts,
                                    @Value("${eip.security.internal-service-token:${EIP_SECURITY_INTERNAL_SERVICE_TOKEN:}}") String internalToken) {
        this.prompts = prompts;
        this.internalToken = internalToken == null ? "" : internalToken.trim();
    }

    /**
     * Active content for each requested key (comma-separated), falling back to
     * the bundled default per key. Keys with no active row AND no bundled
     * default are omitted from the response, so callers can apply their own
     * built-in fallback.
     */
    @GetMapping("/prompts")
    public ResponseEntity<?> prompts(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                     @RequestHeader(value = "X-Tenant-Id", required = false) String tenantHeader,
                                     @RequestParam String keys) {
        if (internalToken.isEmpty() || token == null || !InternalTokens.matches(internalToken, token.trim())) {
            return ResponseEntity.status(401).body(Map.of("error", "invalid internal token"));
        }
        UUID tenantId = parseUuid(tenantHeader);
        if (tenantId == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing or invalid X-Tenant-Id"));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String raw : keys.split(",")) {
            String key = raw.trim();
            if (key.isEmpty()) continue;
            String text = prompts.text(tenantId, key);
            if (text != null && !text.isBlank()) out.put(key, text);
        }
        return ResponseEntity.ok(Map.of("prompts", out));
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
