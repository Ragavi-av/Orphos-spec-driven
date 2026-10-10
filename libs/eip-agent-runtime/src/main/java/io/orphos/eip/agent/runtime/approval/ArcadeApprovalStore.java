package io.orphos.eip.agent.runtime.approval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.agent.spec.ApprovalState;
import io.orphos.eip.agent.spec.ProposedAction;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * ArcadeDB-backed {@link ApprovalStore} (TO-BE design §5.3). Persists the uniform
 * {@link ProposedAction} envelope as the {@code ProposedAction} document type; {@code arguments}
 * is stored as JSON. Present only when the agent configures {@code arcadedb.url}.
 */
public class ArcadeApprovalStore implements ApprovalStore {

    private static final Logger log = LogManager.getLogger(ArcadeApprovalStore.class);

    private final ArcadeDbTemplate arcade;
    private final ObjectMapper mapper = new ObjectMapper();

    public ArcadeApprovalStore(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    /** Best-effort schema creation so a write-capable agent works without a separate bootstrap. */
    @PostConstruct
    void ensureSchema() {
        try {
            arcade.commandNoResult("CREATE DOCUMENT TYPE ProposedAction IF NOT EXISTS", Map.of());
            for (String prop : List.of(
                    "CREATE PROPERTY ProposedAction.actionId    STRING",
                    "CREATE PROPERTY ProposedAction.tenantId    STRING",
                    "CREATE PROPERTY ProposedAction.agentId     STRING",
                    "CREATE PROPERTY ProposedAction.toolName    STRING",
                    "CREATE PROPERTY ProposedAction.argumentsJson STRING",
                    "CREATE PROPERTY ProposedAction.humanSummary STRING",
                    "CREATE PROPERTY ProposedAction.proposedAt  STRING",
                    "CREATE PROPERTY ProposedAction.state       STRING")) {
                try {
                    arcade.commandNoResult(prop, Map.of());
                } catch (Exception ignored) {
                    // CREATE PROPERTY has no IF NOT EXISTS — a duplicate throws; ignore.
                }
            }
            arcade.commandNoResult("CREATE INDEX IF NOT EXISTS ON ProposedAction (actionId) UNIQUE", Map.of());
        } catch (Exception e) {
            log.warn("ProposedAction schema bootstrap skipped: {}", e.getMessage());
        }
    }

    @Override
    public ProposedAction save(ProposedAction a) {
        Map<String, Object> params = new HashMap<>();
        params.put("actionId", a.actionId().toString());
        params.put("tenantId", a.tenantId().toString());
        params.put("agentId", a.agentId());
        params.put("toolName", a.toolName());
        params.put("argumentsJson", writeJson(a.arguments()));
        params.put("humanSummary", a.humanSummary());
        params.put("proposedAt", (a.proposedAt() != null ? a.proposedAt() : Instant.now()).toString());
        params.put("state", a.state().name());
        arcade.commandNoResult(
                "INSERT INTO ProposedAction SET actionId = :actionId, tenantId = :tenantId, agentId = :agentId, "
                        + "toolName = :toolName, argumentsJson = :argumentsJson, humanSummary = :humanSummary, "
                        + "proposedAt = :proposedAt, state = :state",
                params);
        return a;
    }

    @Override
    public List<ProposedAction> findPending(UUID tenantId) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT FROM ProposedAction WHERE tenantId = :tenantId AND state = 'PENDING'",
                Map.of("tenantId", tenantId.toString()));
        List<ProposedAction> out = new ArrayList<>();
        for (Map<String, Object> row : rows) out.add(map(row));
        return out;
    }

    @Override
    public Optional<ProposedAction> findById(UUID actionId) {
        List<Map<String, Object>> rows = arcade.query(
                "SELECT FROM ProposedAction WHERE actionId = :actionId",
                Map.of("actionId", actionId.toString()));
        return rows.isEmpty() ? Optional.empty() : Optional.of(map(rows.get(0)));
    }

    @Override
    public void updateState(UUID actionId, ApprovalState state) {
        arcade.command(
                "UPDATE ProposedAction SET state = :state WHERE actionId = :actionId",
                Map.of("state", state.name(), "actionId", actionId.toString()));
    }

    @Override
    public void updateArguments(UUID actionId, Map<String, Object> arguments, String humanSummary) {
        Map<String, Object> params = new HashMap<>();
        params.put("actionId", actionId.toString());
        params.put("argumentsJson", writeJson(arguments));
        params.put("humanSummary", humanSummary == null ? "" : humanSummary);
        arcade.command(
                "UPDATE ProposedAction SET argumentsJson = :argumentsJson, humanSummary = :humanSummary "
                        + "WHERE actionId = :actionId",
                params);
    }

    // ── mapping ──────────────────────────────────────────────────────────────

    private ProposedAction map(Map<String, Object> row) {
        return new ProposedAction(
                uuid(row.get("actionId")),
                uuid(row.get("tenantId")),
                str(row.get("agentId")),
                str(row.get("toolName")),
                readJson(str(row.get("argumentsJson"))),
                str(row.get("humanSummary")),
                parseInstant(row.get("proposedAt")),
                parseState(row.get("state")));
    }

    private Map<String, Object> readJson(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try {
            return mapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeJson(Map<String, Object> args) {
        try {
            return mapper.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static UUID uuid(Object o) {
        return o == null ? null : UUID.fromString(o.toString());
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }

    private static Instant parseInstant(Object o) {
        if (o == null || o.toString().isBlank()) return Instant.now();
        try {
            return Instant.parse(o.toString());
        } catch (Exception e) {
            return Instant.now();
        }
    }

    private static ApprovalState parseState(Object o) {
        if (o == null) return ApprovalState.PENDING;
        try {
            return ApprovalState.valueOf(o.toString());
        } catch (Exception e) {
            return ApprovalState.PENDING;
        }
    }
}
