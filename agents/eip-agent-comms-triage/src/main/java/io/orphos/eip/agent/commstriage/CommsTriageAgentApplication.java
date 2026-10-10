package io.orphos.eip.agent.commstriage;

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
 * The Communications Triage agent (TO-BE design §14): event-triggered, ACT_WITH_APPROVAL. It reads
 * the tenant's channel-derived tasks/notifications (Slack/Teams/…) and, when a message warrants a
 * reply, proposes a draft; the draft/send tools ({@code comms.draft_reply}, {@code comms.send_message})
 * are write tools routed through the approval store and executed only on human approval. The runtime
 * supplies the planner, MCP gateway, approval store, {@code /invoke} + {@code /approvals} APIs, and
 * self-registration — this class only declares the manifest.
 */
@SpringBootApplication
public class CommsTriageAgentApplication {

    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process. POD_IP, if the orchestrator injects it
        // (e.g. Kubernetes' downward API), takes priority over Java's own NIC lookup, which can
        // report an internal container bridge address rather than the one other services reach
        // this instance on.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(CommsTriageAgentApplication.class, args);
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
    public AgentManifest commsTriageManifest() {
        return new AgentManifest(
                "comms-triage",
                "1.0.0",
                "Communications Triage",
                // Ops-editable prompt resolved via eip.agent.runtime.prompts.comms-triage.goal (design §5.2).
                "comms-triage.goal",
                List.of("comms.list_notifications", "comms.list_tasks"),
                List.of("comms.draft_reply", "comms.send_message"),
                // Event-triggered: fired when a "comms.message.received" event is published for the
                // tenant via the orchestrator's EventRouter.
                new Trigger.Event("comms.message.received"),
                Autonomy.ACT_WITH_APPROVAL,
                "agent-planning");
    }
}
