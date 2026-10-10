package io.orphos.eip.ingest;

import io.orphos.eip.ingest.component.OkgEventListener;
import io.orphos.eip.ingest.component.OkgIngestScheduler;
import io.orphos.eip.ingest.service.TenantInfoCache;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * OKG Ingest — pulls entities from external sources (Jira today) into the
 * tenant's ontology knowledge graph in ArcadeDB, via three ingestion paths:
 * <ul>
 *   <li>A scheduled poller ({@link io.orphos.eip.ingest.component.OkgIngestScheduler})
 *       that syncs every tenant with an enabled Jira connection on a fixed interval.</li>
 *   <li>An admin-triggered REST endpoint
 *       ({@link io.orphos.eip.ingest.controller.OkgSyncController}) for on-demand sync.</li>
 *   <li>A Kafka consumer ({@link io.orphos.eip.ingest.component.OkgEventListener}) on topic
 *       {@code okg-events} for other producers/connectors to push entities directly.</li>
 * </ul>
 *
 * <p><b>Multi-tenant contract:</b>
 * <ul>
 *   <li>Every Kafka message MUST carry an {@code X-Tenant-Id} header; messages
 *       missing it are logged and skipped, never defaulted to a tenant.</li>
 *   <li>Every ArcadeDB write is routed to the specific database resolved for
 *       that tenant ({@code TenantInfoCache}) — SHARED tenants share
 *       {@code orphos}, DEDICATED tenants get their own database — and every
 *       row is stamped {@code tenantId = :tenantId} within that database.</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
public class OkgIngestApplication {
    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process. POD_IP, if the orchestrator injects it
        // (e.g. Kubernetes' downward API), takes priority over Java's own NIC lookup, which can
        // report an internal container bridge address rather than the one other services reach
        // this instance on.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(OkgIngestApplication.class, args);
    }

    private static String resolveHostIp() {
        String podIp = System.getenv("POD_IP");
        if (podIp != null && !podIp.isBlank()) {
            return podIp;
        }
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "unknown";
        }
    }
}
