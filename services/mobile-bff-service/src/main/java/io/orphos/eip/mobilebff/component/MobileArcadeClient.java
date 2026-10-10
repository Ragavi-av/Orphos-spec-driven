package io.orphos.eip.mobilebff.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.tenant.ArcadeFailureTranslator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin HTTP client around the ArcadeDB REST API for the mobile-bff-service.
 * Mirrors mail-insight-service's MailArcadeClient: parameter-bound SQL, basic auth,
 * shared orphos database. Backs the durable capture / device stores.
 */
@Component
public class MobileArcadeClient {

    @Autowired
    private RestTemplate rest;

    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${arcadedb.url}")
    private String url;

    @Value("${arcadedb.user:root}")
    private String user;

    @Value("${arcadedb.password:}")
    private String password;

    @Value("${arcadedb.database:orphos}")
    private String database;

    public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
        return exec("/api/v1/query/", sql, params);
    }

    public List<Map<String, Object>> command(String sql, Map<String, Object> params) {
        return exec("/api/v1/command/", sql, params);
    }

    public void commandNoResult(String sql, Map<String, Object> params) {
        exec("/api/v1/command/", sql, params);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> exec(String endpoint, String sql, Map<String, Object> params) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(user, password);

        Map<String, Object> body = new HashMap<>();
        body.put("language", "sql");
        body.put("command", sql);
        if (params != null && !params.isEmpty()) {
            body.put("params", params);
        }

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
        ResponseEntity<String> response = ArcadeFailureTranslator.translate("arcade",
                () -> rest.exchange(url + endpoint + database, HttpMethod.POST, entity, String.class));

        try {
            JsonNode root = mapper.readTree(response.getBody());
            JsonNode result = root.get("result");
            List<Map<String, Object>> out = new ArrayList<>();
            if (result != null && result.isArray()) {
                for (JsonNode node : result) {
                    out.add(mapper.convertValue(node, Map.class));
                }
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse ArcadeDB response: " + e.getMessage(), e);
        }
    }
}
