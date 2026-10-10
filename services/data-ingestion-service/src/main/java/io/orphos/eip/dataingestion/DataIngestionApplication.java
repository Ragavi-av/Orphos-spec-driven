package io.orphos.eip.dataingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Data Ingestion Service — enterprise data-source connectivity (JDBC / REST),
 * metadata discovery, source→target mapping, and scheduled or on-demand
 * batch extract/load into the tenant's ArcadeDB.
 *
 * <p>Tenant context is bound per HTTP request by eip-common's
 * {@code TenantResolutionFilter}; scheduled flows bind it explicitly via
 * {@code TenantContextHolder.runWith(...)}.</p>
 */
@SpringBootApplication
@EnableScheduling
public class DataIngestionApplication {

    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process. POD_IP, if the orchestrator injects it
        // (e.g. Kubernetes' downward API), takes priority over Java's own NIC lookup, which can
        // report an internal container bridge address rather than the one other services reach
        // this instance on.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(DataIngestionApplication.class, args);
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
