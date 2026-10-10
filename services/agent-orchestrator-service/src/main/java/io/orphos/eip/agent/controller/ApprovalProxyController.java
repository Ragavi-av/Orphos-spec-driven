package io.orphos.eip.agent.controller;

import io.orphos.eip.agent.component.OrchestratorRegistryClient;
import io.orphos.eip.agent.util.OrchestratorProperties;
import io.orphos.eip.agent.vo.DiscoveredAgent;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A routed, browser-reachable front for the approval inbox (TO-BE design §8). The approval API
 * itself lives in each agent runtime; since all agents share one {@code ProposedAction} store, this
 * proxy delegates to any live agent for the tenant — giving the single-inbox UI one stable URL
 * ({@code /api/orchestrator/approvals}) without exposing internal agent endpoints.
 *
 * <p><b>Authorization.</b> Deciding on an agent's proposed action executes it with the
 * platform's internal credentials, so it is restricted to {@code executive} and above
 * ({@code ceo}/{@code cto}/{@code cfo}/{@code admin}/{@code platform_admin} via the role
 * hierarchy, plus custom roles based on one of those) or an internal-service caller. The
 * tenant is the one bound by {@code TenantResolutionFilter} — the caller's own JWT tenant,
 * or the {@code X-Tenant-Id} of a verified platform admin — never a raw header, which any
 * tenant user could otherwise point at another tenant's inbox.</p>
 */
@RestController
@RequestMapping("/api/orchestrator/approvals")
public class ApprovalProxyController {

    private static final Logger log = LogManager.getLogger(ApprovalProxyController.class);

    static final String CAN_DECIDE = "hasRole('executive') or hasRole('service_internal')";
    private static final Pattern ACTION_ID = Pattern.compile("^[A-Za-z0-9_-]{1,128}$");

    private final OrchestratorRegistryClient registry;
    private final OrchestratorProperties props;
    private volatile HttpClient http;

    public ApprovalProxyController(OrchestratorRegistryClient registry, OrchestratorProperties props) {
        this.registry = registry;
        this.props = props;
    }

    private HttpClient http() {
        HttpClient client = http;
        if (client == null) {
            synchronized (this) {
                if (http == null) {
                    http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
                }
                client = http;
            }
        }
        return client;
    }

    /** Pending proposals for the tenant — delegates to a live agent's {@code GET /approvals}. */
    @GetMapping
    @PreAuthorize(CAN_DECIDE)
    public ResponseEntity<String> pending() {
        UUID tenantId = TenantContextHolder.requireTenantId();
        String base = liveAgentBaseUrl(tenantId);
        if (base == null) {
            return ResponseEntity.ok("[]");  // no live agent → empty inbox rather than an error
        }
        return relay("GET", base + "/approvals", tenantId, null);
    }

    /** Approve + execute a proposal. */
    @PostMapping("/{actionId}/approve")
    @PreAuthorize(CAN_DECIDE)
    public ResponseEntity<String> approve(@PathVariable String actionId) {
        UUID tenantId = TenantContextHolder.requireTenantId();
        if (!validActionId(actionId)) return badActionId();
        String base = liveAgentBaseUrl(tenantId);
        if (base == null) return ResponseEntity.status(503).body("{\"error\":\"No live agent to execute the action.\"}");
        return relay("POST", base + "/approvals/" + actionId + "/approve", tenantId, "");
    }

    /** Reject a proposal. */
    @PostMapping("/{actionId}/reject")
    @PreAuthorize(CAN_DECIDE)
    public ResponseEntity<String> reject(@PathVariable String actionId) {
        UUID tenantId = TenantContextHolder.requireTenantId();
        if (!validActionId(actionId)) return badActionId();
        String base = liveAgentBaseUrl(tenantId);
        if (base == null) return ResponseEntity.status(503).body("{\"error\":\"No live agent available.\"}");
        return relay("POST", base + "/approvals/" + actionId + "/reject", tenantId, "");
    }

    /** Edit a pending proposal's arguments before approval (the runtime re-issues any token). */
    @PostMapping("/{actionId}/edit")
    @PreAuthorize(CAN_DECIDE)
    public ResponseEntity<String> edit(@PathVariable String actionId,
                                       @RequestBody(required = false) String body) {
        UUID tenantId = TenantContextHolder.requireTenantId();
        if (!validActionId(actionId)) return badActionId();
        String base = liveAgentBaseUrl(tenantId);
        if (base == null) return ResponseEntity.status(503).body("{\"error\":\"No live agent available.\"}");
        return relay("POST", base + "/approvals/" + actionId + "/edit", tenantId, body == null ? "" : body);
    }

    /** Action ids are opaque tokens; anything else (e.g. {@code ../}) must not reach the relayed URL. */
    static boolean validActionId(String actionId) {
        return actionId != null && ACTION_ID.matcher(actionId).matches();
    }

    private static ResponseEntity<String> badActionId() {
        return ResponseEntity.badRequest().body("{\"error\":\"Invalid action id.\"}");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String liveAgentBaseUrl(UUID tenantId) {
        List<DiscoveredAgent> agents = registry.activeAgents(tenantId);
        return agents.stream()
                .map(DiscoveredAgent::baseUrl)
                .filter(b -> b != null && !b.isBlank())
                .findFirst()
                .map(b -> b.endsWith("/") ? b.substring(0, b.length() - 1) : b)
                .orElse(null);
    }

    private ResponseEntity<String> relay(String method, String url, UUID tenantId, String body) {
        try {
            HttpRequest.Builder req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(3))
                    .header("X-Internal-Token", props.getInternalToken())
                    .header("X-Tenant-Id", tenantId.toString());
            if ("POST".equals(method)) {
                req.header("Content-Type", "application/json")
                   .POST(HttpRequest.BodyPublishers.ofString(body == null ? "" : body, StandardCharsets.UTF_8));
            } else {
                req.GET();
            }
            HttpResponse<String> res = http().send(req.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return ResponseEntity.status(res.statusCode())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(res.body() == null ? "" : res.body());
        } catch (Exception e) {
            log.warn("Approval relay to {} failed: {}", url, e.getMessage());
            return ResponseEntity.status(502).body("{\"error\":\"Approval relay failed: " + e.getMessage() + "\"}");
        }
    }
}
