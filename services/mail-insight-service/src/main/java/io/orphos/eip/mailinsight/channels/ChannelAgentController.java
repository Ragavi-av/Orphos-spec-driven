package io.orphos.eip.mailinsight.channels;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Communication-channel agent API behind the feed's channel-item AI actions and
 * the comms MCP tools. Two-step contract: {@code /draft} returns an editable
 * proposal, {@code /send} posts exactly what was confirmed back to the channel.
 * Reachable by internal callers (eip-mcp-comms) via the shared X-Internal-Token
 * and by authenticated tenant users; visibility is enforced by
 * {@code MailInsightRepository} (items owned by another user resolve to unknown).
 */
@RestController
@RequestMapping("/api/mail-insight/comms/items/{id}/agent")
public class ChannelAgentController {

    private static final Logger log = LogManager.getLogger(ChannelAgentController.class);

    private final ChannelAgentService agent;

    public ChannelAgentController(ChannelAgentService agent) {
        this.agent = agent;
    }

    /** Lets the UI hide the AI actions when the comms agent is not configured. */
    @GetMapping("/status")
    public Map<String, Object> status(@PathVariable String id) {
        return Map.of("enabled", agent.isEnabled());
    }

    public record DraftRequest(String action, String instruction) {}

    @PostMapping("/draft")
    public ResponseEntity<?> draft(@PathVariable String id, @RequestBody(required = false) DraftRequest req) {
        try {
            String instruction = req == null || req.instruction() == null ? "" : req.instruction().trim();
            return ResponseEntity.ok(agent.draft(id, instruction));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.warn("Comms agent draft failed for item {}: {}", id, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "draft failed: " + e.getMessage()));
        }
    }

    public record SendBody(String action, String bodyText) {}

    @PostMapping("/send")
    public ResponseEntity<?> send(@PathVariable String id, @RequestBody SendBody body) {
        try {
            return ResponseEntity.ok(agent.send(id, body == null ? null : body.bodyText()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Comms agent send failed for item {}: {}", id, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "send failed: " + e.getMessage()));
        }
    }
}
