package io.orphos.eip.decision;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Decision Log RAG service — tracks organizational decisions with AI-powered
 * detection, classification, impact prediction, semantic search and
 * cross-system correlation.
 *
 * <p>Scheduling powers the two background loops: the RAG sync sweep (pulls
 * decision candidates out of Document_RAG / Communication_RAG every 5 min)
 * and the escalation tracker (flags overdue implementations). Both run per
 * active tenant via {@code TenantTaskExecutor}; request-time flows get their
 * tenant from {@code TenantResolutionFilter} as everywhere else.</p>
 */
@SpringBootApplication
@EnableScheduling
public class DecisionLogApplication {
    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process. POD_IP, if the orchestrator injects it
        // (e.g. Kubernetes' downward API), takes priority over Java's own NIC lookup, which can
        // report an internal container bridge address rather than the one other services reach
        // this instance on.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(DecisionLogApplication.class, args);
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
