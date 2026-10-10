package io.orphos.eip.reasoning;

import io.orphos.eip.reasoning.component.OntologyService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * OKG Reasoning — runs a scheduled per-tenant cycle
 * ({@code reasoning.cycle-interval-ms}, default 15 minutes) that:
 * <ol>
 *   <li>Loads {@code eip-core.owl} at startup into a Jena {@code OntModel}
 *       (OWL/rule reasoner via {@code OntModelSpec.OWL_MEM_RULE_INF}) — see
 *       {@link io.orphos.eip.reasoning.component.OntologyService}.</li>
 *   <li>Each cycle: loads the tenant's ingested facts from ArcadeDB, runs Jena
 *       inference over the ontology's one transitive relationship
 *       ({@code blocks}/{@code blockedBy}), layers on computed analytical
 *       facts (overdue/blocked/overloaded) that OWL can't express natively,
 *       and writes the results to Fuseki — see
 *       {@link io.orphos.eip.reasoning.service.ReasoningService}.</li>
 * </ol>
 *
 * <p><b>Multi-tenant contract:</b> the cycle runs via
 * {@code TenantTaskExecutor.forEachTenant(...)} — one reasoning model per
 * tenant, never shared. Every ArcadeDB read is routed to the tenant's
 * specific database. Fuseki output is partitioned per tenant: SHARED tenants
 * get a named graph ({@code urn:eip:tenant:{uuid}}, the same convention
 * graph-proxy-service already reads); DEDICATED tenants get their own
 * dataset's default graph.</p>
 */
@SpringBootApplication
@EnableScheduling
public class OkgReasoningApplication {
    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process. POD_IP, if the orchestrator injects it
        // (e.g. Kubernetes' downward API), takes priority over Java's own NIC lookup, which can
        // report an internal container bridge address rather than the one other services reach
        // this instance on.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(OkgReasoningApplication.class, args);
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
