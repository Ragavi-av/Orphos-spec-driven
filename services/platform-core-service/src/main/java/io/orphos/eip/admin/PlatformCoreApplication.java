package io.orphos.eip.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.ldap.LdapAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Platform Core Service — tenant lifecycle + JWT-issuer/tenant discovery contract,
 * authentication/authorization config, secrets indirection, DB migrations, tenant
 * data onboarding, and platform ops. Carved out of the former admin-service; every
 * other service polls this service's {@code /api/internal/**} endpoints at boot.
 *
 * <p>LDAP auto-config is disabled at startup because LDAP is one of several
 * pluggable authentication providers and is only wired when an admin enables
 * an LDAP IdentityProvider record at runtime (see LdapAuthService).</p>
 */
@SpringBootApplication(exclude = { LdapAutoConfiguration.class })
@EnableAsync
@EnableScheduling
public class PlatformCoreApplication {
    public static void main(String[] args) {
        // Read by log4j2-json-layout.json's "hostIpAddress" field. Must be set before Spring
        // (and therefore Log4j2) starts, since it's resolved once at config load and reused for
        // every log line for the life of the process. POD_IP, if the orchestrator injects it
        // (e.g. Kubernetes' downward API), takes priority over Java's own NIC lookup, which can
        // report an internal container bridge address rather than the one other services reach
        // this instance on.
        System.setProperty("HOST_IP", resolveHostIp());
        SpringApplication.run(PlatformCoreApplication.class, args);
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
