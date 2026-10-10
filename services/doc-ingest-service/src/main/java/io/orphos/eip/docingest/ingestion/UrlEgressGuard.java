package io.orphos.eip.docingest.ingestion;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SSRF guard for server-side URL fetches (the {@code POST /api/doc-ingest/url}
 * ingestion path). Any authenticated tenant user supplies the URL, so without
 * these checks the server can be steered at cloud metadata
 * ({@code 169.254.169.254}), internal-only services, or its own
 * {@code /api/internal/*} endpoints.
 *
 * <p>Enforced on every hop (the fetcher validates before the initial request
 * and again after each redirect):</p>
 * <ul>
 *   <li>scheme must be http/https;</li>
 *   <li>if {@code doc-ingest.url.allowed-hosts} is non-empty, the host must be
 *       on it (suffix match, so {@code example.com} also allows
 *       {@code docs.example.com});</li>
 *   <li>every resolved IP is rejected if it is loopback, link-local (incl. the
 *       169.254/16 metadata range), site-local/RFC-1918 private, unique-local
 *       IPv6 (fc00::/7), multicast, or the wildcard address.</li>
 * </ul>
 *
 * <p>Note: validating resolved IPs leaves a small TOCTOU window against DNS
 * rebinding (the JDK HttpClient re-resolves when it connects). The host
 * allowlist closes it entirely for locked-down deployments; the range checks
 * are the defence-in-depth default when no allowlist is configured.</p>
 */
@Component
public class UrlEgressGuard {

    private static final Logger log = LogManager.getLogger(UrlEgressGuard.class);

    private final Set<String> allowedSchemes = Set.of("http", "https");
    private final List<String> allowedHosts;

    public UrlEgressGuard(
            @Value("${doc-ingest.url.allowed-hosts:}") String allowedHostsCsv) {
        this.allowedHosts = List.of(allowedHostsCsv.toLowerCase(Locale.ROOT).split(","))
                .stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (allowedHosts.isEmpty()) {
            log.info("URL ingestion egress: no host allowlist set — private/link-local/"
                + "loopback ranges blocked, all other public hosts permitted. Set "
                + "doc-ingest.url.allowed-hosts to lock egress to specific domains.");
        } else {
            log.info("URL ingestion egress restricted to hosts (and subdomains): {}", allowedHosts);
        }
    }

    /**
     * Validate a URL (or a redirect target) before it is fetched.
     *
     * @throws IngestionException if the URL is not safe to fetch server-side.
     */
    public void validate(URI url) {
        if (url == null || url.getScheme() == null) {
            throw new IngestionException("URL has no scheme: " + url);
        }
        String scheme = url.getScheme().toLowerCase(Locale.ROOT);
        if (!allowedSchemes.contains(scheme)) {
            throw new IngestionException("Blocked URL scheme '" + scheme + "' (only http/https allowed)");
        }
        String host = url.getHost();
        if (host == null || host.isBlank()) {
            throw new IngestionException("URL has no host: " + url);
        }
        String lowerHost = host.toLowerCase(Locale.ROOT);

        if (!allowedHosts.isEmpty()) {
            boolean ok = allowedHosts.stream()
                    .anyMatch(h -> lowerHost.equals(h) || lowerHost.endsWith("." + h));
            if (!ok) {
                throw new IngestionException("Host '" + host + "' is not on the URL ingestion allowlist");
            }
        }

        InetAddress[] resolved;
        try {
            resolved = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IngestionException("Could not resolve host '" + host + "'");
        }
        for (InetAddress ip : resolved) {
            if (isBlocked(ip)) {
                throw new IngestionException(
                    "Blocked fetch to non-public address for host '" + host + "' (" + ip.getHostAddress() + ")");
            }
        }
    }

    /** True if the address is in a range that must never be reachable via user-supplied URLs. */
    private boolean isBlocked(InetAddress ip) {
        if (ip.isLoopbackAddress()     // 127.0.0.0/8, ::1
            || ip.isLinkLocalAddress() // 169.254/16 (cloud metadata), fe80::/10
            || ip.isSiteLocalAddress() // 10/8, 172.16/12, 192.168/16
            || ip.isAnyLocalAddress()  // 0.0.0.0, ::
            || ip.isMulticastAddress()) {
            return true;
        }
        byte[] a = ip.getAddress();
        // IPv6 unique-local fc00::/7 (Java has no predicate for it).
        if (a.length == 16 && (a[0] & 0xFE) == 0xFC) return true;
        // IPv4 carrier-grade NAT 100.64.0.0/10 — also effectively internal.
        if (a.length == 4 && (a[0] & 0xFF) == 100 && (a[1] & 0xC0) == 64) return true;
        return false;
    }
}
