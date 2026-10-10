package io.orphos.eip.nlquery.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.nlquery.service.CopilotSessionStore;
import io.orphos.eip.nlquery.util.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-serve CRUD over the current user's AI Co-pilot chat sessions.
 * Sessions are stored server-side in ArcadeDB (see CopilotSessionStore)
 * instead of browser localStorage, so history follows the user across
 * devices. Strictly per-user: the Keycloak sub comes from the token, never
 * from the request, so no endpoint can touch another user's history.
 */
@RestController
@RequestMapping("/api/nl-query/me/copilot/sessions")
public class CopilotSessionController {

    /** Guard against unbounded payloads; localStorage had a ~5MB quota per origin. */
    private static final int MAX_MESSAGES_JSON_CHARS = 1_000_000;

    private final CopilotSessionStore store;
    private final ObjectMapper mapper;

    public CopilotSessionController(CopilotSessionStore store, ObjectMapper mapper) {
        this.store = store;
        this.mapper = mapper;
    }

    public record SessionPayload(String label, String startedAt, String endedAt,
                                 List<Map<String, Object>> messages) {}

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        List<Map<String, Object>> docs = store.list(currentSub(), limit);
        List<Map<String, Object>> sessions = new ArrayList<>(docs.size());
        for (Map<String, Object> doc : docs) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("id",        doc.get("sessionId"));
            s.put("label",     blankToNull(doc.get("label")));
            s.put("startedAt", blankToNull(doc.get("startedAt")));
            s.put("endedAt",   blankToNull(doc.get("endedAt")));
            s.put("messages",  parseMessages(doc.get("messagesJson")));
            sessions.add(s);
        }
        return ResponseEntity.ok(Map.of("sessions", sessions));
    }

    @PutMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> upsert(@PathVariable String sessionId,
                                                      @RequestBody SessionPayload payload) {
        String messagesJson;
        try {
            messagesJson = mapper.writeValueAsString(
                payload.messages() == null ? List.of() : payload.messages());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "messages is not serialisable"));
        }
        if (messagesJson.length() > MAX_MESSAGES_JSON_CHARS) {
            return ResponseEntity.badRequest().body(Map.of("error", "session too large"));
        }
        store.upsert(currentSub(), sessionId, payload.label(),
                     payload.startedAt(), payload.endedAt(), messagesJson);
        return ResponseEntity.ok(Map.of("id", sessionId));
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> delete(@PathVariable String sessionId) {
        store.delete(currentSub(), sessionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAll() {
        store.deleteAll(currentSub());
        return ResponseEntity.noContent().build();
    }

    private List<Map<String, Object>> parseMessages(Object messagesJson) {
        if (!(messagesJson instanceof String s) || s.isBlank()) return List.of();
        try {
            return mapper.readValue(s, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String blankToNull(Object v) {
        return (v instanceof String s && !s.isBlank()) ? s : null;
    }

    private String currentSub() {
        return CurrentUser.sub()
                .orElseThrow(() -> new IllegalStateException("No authenticated user"));
    }
}
