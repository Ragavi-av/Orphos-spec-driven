package io.orphos.eip.notification.controller;

import io.orphos.eip.common.tenant.ArcadeFailureTranslator;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.common.tenant.TenantDataUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    @Autowired
    private RestTemplate rest;

    @Value("${arcadedb.url:http://eip-arcadedb:2480}")
    private String arcadeUrl;

    @Value("${arcadedb.user:root}")
    private String arcadeUser;

    @Value("${arcadedb.password:}")
    private String arcadePassword;

    private static final String DB = "orphos";

    @GetMapping
    public ResponseEntity<Object> getAlerts() {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        String url = arcadeUrl + "/api/v1/query/" + DB;
        String sql = "SELECT id, title, severity, domain, status, message, createdAt, source " +
                     "FROM Alert WHERE tenantId = :tenantId AND status='ACTIVE' " +
                     "ORDER BY createdAt DESC LIMIT 50";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(arcadeUser, arcadePassword);

        Map<String, Object> body = new HashMap<>();
        body.put("language", "sql");
        body.put("command", sql);
        body.put("params", Map.of("tenantId", tenantId));
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        try {
            // ArcadeFailureTranslator surfaces tenant-DB outages with the
            // X-Tenant-DB-Status header so the SPA banner fires.
            ResponseEntity<Object> response = ArcadeFailureTranslator.translate("arcade",
                    () -> rest.exchange(url, HttpMethod.POST, entity, Object.class));
            return ResponseEntity.ok(response.getBody());
        } catch (TenantDataUnavailableException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(503).body(Map.of("error", "ArcadeDB unavailable: " + e.getMessage()));
        }
    }
}
