package io.orphos.eip.ingest.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.ingest.repository.OkgGraphRepository;
import io.orphos.eip.ingest.service.TenantInfoCache;
import io.orphos.eip.ingest.vo.TenantRoutingInfo;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Consumes generic ingestion events from the {@code okg-events} topic — an
 * alternate/future path alongside the built-in Jira poller, for producers
 * (other data-source connectors, batch backfills, etc.) that want to push
 * entities directly rather than being polled.
 *
 * <p>Message envelope (JSON body):
 * <pre>{@code
 * {
 *   "entityType": "project" | "person" | "issue" | "issueLink",
 *   "source": "JIRA" | ...,
 *   "data": { ...entity-specific fields... }
 * }
 * }</pre>
 *
 * <p>Every message MUST carry an {@code X-Tenant-Id} header (set by the
 * producer) — messages missing it are logged and skipped rather than
 * defaulted to some tenant, since defaulting would risk mixing tenants' data
 * on a producer misconfiguration. Reading headers off the raw
 * {@link ConsumerRecord} (rather than Spring's {@code @Header} binding) since
 * {@code X-Tenant-Id} isn't a Spring-standard header name.</p>
 *
 * <h2>Retry / dead-lettering</h2>
 * <p>{@code onEvent} deliberately does NOT catch a processing failure from
 * {@link #handle} — it propagates to the container, whose {@code CommonErrorHandler}
 * (see {@code KafkaErrorHandlingConfig}) redelivers with exponential backoff and, once
 * exhausted, publishes to {@code okg-events.DLT} instead of the message being silently
 * acked and lost. Two cases are still absorbed here rather than left to retry, because
 * retrying them can never succeed: a missing {@code X-Tenant-Id} (a producer
 * misconfiguration — the header will be missing again on any redelivery) and malformed
 * JSON (the body doesn't change on redelivery either). Routing that can't currently be
 * resolved (tenant genuinely inactive, or {@code TenantInfoCache}'s upstream call
 * failing) is NOT a poison pill in that sense — it may well succeed on the next
 * redelivery attempt — so it now throws instead of being logged-and-dropped.</p>
 */
@Component
public class OkgEventListener {

    private static final Logger log = LogManager.getLogger(OkgEventListener.class);

    private final OkgGraphRepository graph;
    private final TenantInfoCache tenantInfo;
    private final io.orphos.eip.core.arcadedb.ArcadeConnectionResolver resolver;
    private final ObjectMapper mapper = new ObjectMapper();

    public OkgEventListener(OkgGraphRepository graph, TenantInfoCache tenantInfo,
                            io.orphos.eip.core.arcadedb.ArcadeConnectionResolver resolver) {
        this.graph = graph;
        this.tenantInfo = tenantInfo;
        this.resolver = resolver;
    }

    @KafkaListener(topics = "${okg.kafka.topic:okg-events}", groupId = "${spring.kafka.consumer.group-id:okg-ingest}")
    public void onEvent(ConsumerRecord<String, String> record) {
        UUID tenantId = extractTenantId(record);
        if (tenantId == null) {
            log.warn("okg-events message at offset {} is missing X-Tenant-Id — skipping (partition {})",
                    record.offset(), record.partition());
            return;
        }
        // No catch here — see class javadoc "Retry / dead-lettering". A failure propagates to
        // the container's error handler for backoff-retry and, on exhaustion, a DLT publish.
        TenantContextHolder.runWith(TenantContext.of(tenantId, null), () -> handle(tenantId, record.value()));
    }

    private void handle(UUID tenantId, String payload) {
        JsonNode env;
        try {
            env = mapper.readTree(payload);
        } catch (Exception e) {
            // Poison pill: the body is fixed at the time it was produced, so retrying can
            // never succeed. Log and skip rather than let it exhaust the retry budget.
            log.warn("okg-events message for tenant {} is not valid JSON — skipping: {}", tenantId, e.getMessage());
            return;
        }

        TenantRoutingInfo routing = tenantInfo.find(tenantId).filter(TenantRoutingInfo::active).orElse(null);
        if (routing == null) {
            // Unlike the JSON case, this MAY succeed on redelivery (a transient
            // TenantInfoCache/platform-core lookup failure) — let the container retry it
            // rather than dropping it here. If the tenant really is inactive/deleted, the
            // retries will exhaust and the message lands on the DLT, which is still strictly
            // better than today's silent, permanent drop.
            throw new IllegalStateException(
                    "Cannot resolve routing (or tenant inactive) for " + tenantId);
        }
        String targetDb = routing.dataset();
        io.orphos.eip.core.arcadedb.ArcadeDbTemplate arcade = resolver.templateFor(routing.arcadeConnectionId());

        String entityType = env.path("entityType").asText("");
        String source = env.path("source").asText("UNKNOWN");
        JsonNode data = env.path("data");

        switch (entityType) {
            case "project" -> graph.upsertProject(arcade, targetDb, tenantId, source,
                    data.path("externalId").asText(null), data.path("name").asText(""));
            case "person" -> graph.upsertPerson(arcade, targetDb, tenantId, source,
                    data.path("externalId").asText(null), data.path("displayName").asText(""));
            case "issueLink" -> graph.upsertIssueLink(arcade, targetDb, tenantId, source,
                    data.path("fromExternalId").asText(null), data.path("toExternalId").asText(null),
                    data.path("linkType").asText("relatesTo"));
            case "issue" -> graph.upsertIssue(arcade, targetDb, new OkgGraphRepository.IssueRecord(
                    tenantId, source,
                    data.path("externalId").asText(null),
                    data.path("projectExternalId").asText(null),
                    data.path("summary").asText(""),
                    data.path("statusName").asText(""),
                    data.path("statusCategory").asText(""),
                    data.path("issueTypeName").asText(""),
                    textOrNull(data, "priorityName"),
                    textOrNull(data, "assigneeExternalId"),
                    textOrNull(data, "reporterExternalId"),
                    textOrNull(data, "dueDate"),
                    textOrNull(data, "jiraCreatedAt"),
                    textOrNull(data, "jiraUpdatedAt")));
            default -> log.warn("Unknown entityType '{}' in okg-events message for tenant {} — skipping",
                    entityType, tenantId);
        }
    }

    private static String textOrNull(JsonNode data, String field) {
        JsonNode v = data.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText(null);
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
