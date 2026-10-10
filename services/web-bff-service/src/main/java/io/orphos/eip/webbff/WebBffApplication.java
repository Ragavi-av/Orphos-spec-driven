package io.orphos.eip.webbff;

import io.orphos.eip.webbff.config.WebBffProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Web BFF — the aggregation edge for the Orphos web dashboard. Picks up eip-common
 * (tenant filter, CORS, multi-issuer JWT, client-IP MDC, RestTemplate factory) and
 * eip-core-security by auto-configuration; no shared-lib component scan needed.
 */
@SpringBootApplication
@EnableConfigurationProperties(WebBffProperties.class)
public class WebBffApplication {

    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process. POD_IP, if the orchestrator injects it
        // (e.g. Kubernetes' downward API), takes priority over Java's own NIC lookup, which can
        // report an internal container bridge address rather than the one other services reach
        // this instance on.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(WebBffApplication.class, args);
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
