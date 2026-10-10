package io.orphos.eip.agent.attention;

import io.orphos.eip.agent.spec.AgentManifest;
import io.orphos.eip.agent.spec.Autonomy;
import io.orphos.eip.agent.spec.Trigger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * The Attention / Priority agent (TO-BE design §14): schedule-triggered, SUGGEST_ONLY. It combines
 * the priority-engine work queue/alerts with okg-reasoning insights to surface what most needs
 * attention for the tenant. Read-only — it proposes no writes, so no approval path is engaged. The
 * runtime supplies the planner, MCP gateway, {@code /invoke} endpoint, and self-registration; this
 * class only declares the manifest.
 */
@SpringBootApplication
public class AttentionAgentApplication {

    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process. POD_IP, if the orchestrator injects it
        // (e.g. Kubernetes' downward API), takes priority over Java's own NIC lookup, which can
        // report an internal container bridge address rather than the one other services reach
        // this instance on.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(AttentionAgentApplication.class, args);
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

    @Bean
    public AgentManifest attentionManifest() {
        return new AgentManifest(
                "attention",
                "1.0.0",
                "Attention / Priority",
                // Ops-editable prompt resolved via eip.agent.runtime.prompts.attention.goal (design §5.2).
                "attention.goal",
                List.of("priority.get_queue", "priority.get_alerts"),
                List.of("priority.get_sla", "okg.get_insights"),
                new Trigger.Schedule("0 30 7 * * *"),
                Autonomy.SUGGEST_ONLY,
                "agent-planning");
    }
}
