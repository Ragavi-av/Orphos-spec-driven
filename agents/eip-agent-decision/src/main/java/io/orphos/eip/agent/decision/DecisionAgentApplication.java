package io.orphos.eip.agent.decision;

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
 * The Decision Intelligence agent (TO-BE design §14): ACT_WITH_APPROVAL. It reads the decision log
 * and may propose recording a new decision; that write ({@code decision.log_decision}) is routed
 * through the approval store by the runtime and executed only on human approval. The runtime
 * supplies the planner, MCP gateway, approval store, {@code /invoke} + {@code /approvals} APIs, and
 * self-registration — this class only declares the manifest.
 */
@SpringBootApplication
public class DecisionAgentApplication {

    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process. POD_IP, if the orchestrator injects it
        // (e.g. Kubernetes' downward API), takes priority over Java's own NIC lookup, which can
        // report an internal container bridge address rather than the one other services reach
        // this instance on.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(DecisionAgentApplication.class, args);
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
    public AgentManifest decisionManifest() {
        return new AgentManifest(
                "decision",
                "1.0.0",
                "Decision Intelligence",
                // Ops-editable prompt resolved via eip.agent.runtime.prompts.decision.goal (design §5.2).
                "decision.goal",
                List.of("decision.search_decisions"),
                List.of("decision.log_decision"),
                // Event-triggered per the catalog (design §14): the orchestrator's EventRouter fires
                // this agent when a "decision.escalation" event is published for the tenant.
                new Trigger.Event("decision.escalation"),
                Autonomy.ACT_WITH_APPROVAL,
                "agent-planning");
    }
}
