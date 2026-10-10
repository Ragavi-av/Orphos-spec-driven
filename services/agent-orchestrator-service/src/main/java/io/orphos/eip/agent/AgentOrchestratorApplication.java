package io.orphos.eip.agent;

import io.orphos.eip.agent.util.OrchestratorProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Agent orchestrator — currently a stub. The {@code agent.cycle-ms: 900000}
 * configuration property is the placeholder for the 15-minute orchestration
 * loop that will:
 * <ol>
 *   <li>Fetch pending agent tasks/goals from ArcadeDB.</li>
 *   <li>Orchestrate LLM agent invocations.</li>
 *   <li>Persist decisions/outcomes back to ArcadeDB.</li>
 * </ol>
 *
 * <p><b>Multi-tenant requirement when implemented:</b>
 * <ul>
 *   <li>Inject {@code io.orphos.eip.common.tenant.TenantTaskExecutor} and wrap
 *       the scheduled cycle in {@code tenantExecutor.forEachTenant(tenantId -> ...)}
 *       so each tenant's agents run in isolation.</li>
 *   <li>Every ArcadeDB query MUST include {@code WHERE tenantId = :tenantId}.</li>
 *   <li>Inserts MUST stamp {@code tenantId = :tenantId}.</li>
 *   <li>If agents publish to Kafka, set the {@code X-Tenant-Id} header on every
 *       outbound message so consumers can re-bind context.</li>
 * </ul>
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(OrchestratorProperties.class)
public class AgentOrchestratorApplication {
    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process. POD_IP, if the orchestrator injects it
        // (e.g. Kubernetes' downward API), takes priority over Java's own NIC lookup, which can
        // report an internal container bridge address rather than the one other services reach
        // this instance on.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(AgentOrchestratorApplication.class, args);
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
