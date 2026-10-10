package io.orphos.eip.agent.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.agent.controller.EventIngressController;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Kafka backbone for event-driven agent triggering (TO-BE design §12 Phase 4). Consumes the
 * agent-events topic and dispatches each message to {@link EventRouter}. Gated on
 * {@code eip.orchestrator.kafka-enabled} so the orchestrator boots without a broker; the HTTP event
 * ingress ({@link EventIngressController}) is always available as an alternative.
 *
 * <p>Envelope (JSON body): <pre>{@code {"eventType": "...", "payload": { ... }}}</pre>
 * Every message MUST carry an {@code X-Tenant-Id} header (mirrors okg-ingest's listener) — messages
 * without it are skipped rather than defaulted, to avoid cross-tenant leakage on misconfiguration.</p>
 *
 * <p><b>Retry / dead-lettering:</b> {@code onEvent} does not catch a failure from
 * {@link EventRouter#route}/JSON parsing beyond the malformed-JSON case below — it propagates to
 * the container's {@code CommonErrorHandler} (see {@code KafkaErrorHandlingConfig}), which
 * redelivers with backoff and, on exhaustion, publishes to {@code eip.agent-events.DLT} rather than
 * silently dropping it. Only the missing-header and malformed-JSON cases are absorbed here — both
 * are poison pills (the header/body can't change on redelivery), so retrying them can never help.</p>
 */
@Component
@ConditionalOnProperty(prefix = "eip.orchestrator", name = "kafka-enabled", havingValue = "true")
public class AgentEventListener {

    private static final Logger log = LogManager.getLogger(AgentEventListener.class);

    private final EventRouter router;
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentEventListener(EventRouter router) {
        this.router = router;
    }

    @KafkaListener(
            topics = "${eip.orchestrator.events-topic:eip.agent-events}",
            groupId = "${spring.kafka.consumer.group-id:agent-orchestrator}")
    public void onEvent(ConsumerRecord<String, String> record) {
        UUID tenantId = extractTenantId(record);
        if (tenantId == null) {
            log.warn("agent-events message at offset {} is missing X-Tenant-Id — skipping.", record.offset());
            return;
        }
        JsonNode env;
        try {
            env = mapper.readTree(record.value());
        } catch (Exception e) {
            // Poison pill: the body is fixed at produce time, so retrying can never succeed.
            log.warn("agent-events message for tenant {} at offset {} is not valid JSON — skipping: {}",
                    tenantId, record.offset(), e.getMessage());
            return;
        }
        String eventType = env.path("eventType").asText("");
        Map<String, Object> payload = new HashMap<>();
        JsonNode payloadNode = env.get("payload");
        if (payloadNode != null && payloadNode.isObject()) {
            try {
                payload = mapper.convertValue(payloadNode,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            } catch (Exception e) {
                log.warn("agent-events message for tenant {} at offset {} has an unparseable payload — skipping: {}",
                        tenantId, record.offset(), e.getMessage());
                return;
            }
        }
        // No catch here — see class javadoc "Retry / dead-lettering". A routing failure
        // propagates to the container's error handler for backoff-retry and, on exhaustion,
        // a DLT publish, instead of being silently swallowed and lost.
        router.route(tenantId, eventType, payload);
    }

    private static UUID extractTenantId(ConsumerRecord<String, String> record) {
        Header h = record.headers().lastHeader("X-Tenant-Id");
        if (h == null) return null;
        try {
            return UUID.fromString(new String(h.value(), StandardCharsets.UTF_8).trim());
        } catch (Exception e) {
            return null;
        }
    }
}
