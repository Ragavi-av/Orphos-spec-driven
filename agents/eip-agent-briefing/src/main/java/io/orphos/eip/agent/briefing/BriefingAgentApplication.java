package io.orphos.eip.agent.briefing;

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
 * The Briefing agent (TO-BE design §14): schedule-triggered, SUGGEST_ONLY. This class is the whole
 * agent — declaring the {@link AgentManifest} bean is all it takes; {@code eip-agent-runtime}
 * supplies the planner loop, MCP gateway, {@code /invoke} endpoint, and startup self-registration.
 */
@SpringBootApplication
public class BriefingAgentApplication {

    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process. POD_IP, if the orchestrator injects it
        // (e.g. Kubernetes' downward API), takes priority over Java's own NIC lookup, which can
        // report an internal container bridge address rather than the one other services reach
        // this instance on.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(BriefingAgentApplication.class, args);
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

    /**
     * The agent's manifest. Required capability {@code priority.get_queue} is satisfied by
     * eip-mcp-priority in this slice; SLA/alerts are optional so the agent is not marked DEGRADED
     * when only the priority server is live. The fuller capability set (okg/decision tools) arrives
     * as those MCP servers land.
     */
    @Bean
    public AgentManifest briefingManifest() {
        return new AgentManifest(
                "briefing",
                "1.0.0",
                "Daily Briefing",
                // goalPromptKey — resolved by the runtime's GoalPromptResolver from
                // eip.agent.runtime.prompts.<key> (design §5.2), so the prompt is ops-editable and
                // not compiled in. See application.yml.
                "briefing.goal",
                List.of("priority.get_queue"),
                List.of("priority.get_sla", "priority.get_alerts"),
                new Trigger.Schedule("0 0 7 * * *"),
                Autonomy.SUGGEST_ONLY,
                "agent-planning");
    }
}
