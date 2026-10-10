package io.orphos.eip.mailinsight.access;

import io.orphos.eip.mailinsight.channels.ChannelConfigRepository;
import io.orphos.eip.mailinsight.repo.MailboxConfigRepository;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service endpoint exposing this service's source systems — email
 * connections and communication channels (Slack/Teams) — for the platform-core
 * data-flow aggregation, with their last scheduled-scan time. These sources feed
 * mail-derived tasks/notifications, not the ontology graph, so
 * {@code ontologyEntities} is empty. Guarded by the shared internal token.
 */
@RestController
@RequestMapping("/api/internal/sources")
public class InternalSourcesController {

    private final MailboxConfigRepository mailboxes;
    private final ChannelConfigRepository channels;

    @Value("${eip.security.internal-service-token:${eip.security.tenant-discovery.service-token:${EIP_INTERNAL_SERVICE_TOKEN:}}}")
    private String internalToken;

    public InternalSourcesController(MailboxConfigRepository mailboxes, ChannelConfigRepository channels) {
        this.mailboxes = mailboxes;
        this.channels = channels;
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> sources(
            @RequestHeader(value = "X-Internal-Token", required = false) String token,
            @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId) {

        if (internalToken == null || internalToken.isBlank() || !constantTimeEquals(internalToken, token)) {
            return ResponseEntity.status(401).build();
        }
        UUID tid;
        try { tid = UUID.fromString(String.valueOf(tenantId).trim()); }
        catch (Exception e) { return ResponseEntity.badRequest().build(); }

        List<Map<String, Object>> out = TenantContextHolder.callWith(
                new TenantContext(tid, null, false), this::collect);
        return ResponseEntity.ok(out);
    }

    private List<Map<String, Object>> collect() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : mailboxes.listConfigs()) {
            int count = safeCount(() -> mailboxes.listMailboxesByConnection(str(c.get("id"))).size());
            out.add(row("Email", str(c.get("name")), str(c.get("provider")),
                    c.get("enabled"), str(c.get("lastScheduledScanAt")), count));
        }
        for (Map<String, Object> c : channels.listConnections()) {
            int count = safeCount(() -> channels.listAccountsByConnection(str(c.get("id"))).size());
            out.add(row("Communication Channel", str(c.get("name")), str(c.get("kind")),
                    c.get("enabled"), str(c.get("lastScheduledScanAt")), count));
        }
        return out;
    }

    private static Map<String, Object> row(String category, String name, String kind,
                                           Object enabled, String lastRefreshAt, int count) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("service", "mail-insight");
        r.put("category", category);
        r.put("name", name);
        r.put("kind", kind);
        r.put("enabled", enabled);
        r.put("status", "");
        r.put("lastRefreshAt", lastRefreshAt);
        r.put("count", count);
        r.put("ontologyEntities", List.of());
        return r;
    }

    private interface IntSupplier { int get(); }
    private static int safeCount(IntSupplier s) { try { return s.get(); } catch (Exception e) { return 0; } }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
