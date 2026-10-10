package io.orphos.eip.mobilebff.component;

import com.fasterxml.jackson.databind.JsonNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Thin HTTP client to the existing platform services. Forwards the caller's own bearer
 * token and X-Tenant-Id, so downstream authorization and tenant scoping are unchanged.
 * Returns null on any failure so aggregation can degrade to a partial response.
 */
@Component
public class DownstreamClient {

    private static final Logger log = LogManager.getLogger(DownstreamClient.class);
    private final RestClient http = RestClient.create();

    public JsonNode get(String baseUrl, String path, String authorization, String tenantId) {
        try {
            return http.get()
                    .uri(baseUrl + path)
                    .header("Authorization", authorization)
                    .header("X-Tenant-Id", tenantId)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            log.warn("downstream GET {}{} failed: {}", baseUrl, path, e.getMessage());
            return null;
        }
    }

    /** JSON PATCH with the caller's identity + tenant. Returns false on any failure. */
    public boolean patch(String baseUrl, String path, String authorization, String tenantId, Object body) {
        try {
            http.patch()
                    .uri(baseUrl + path)
                    .header("Authorization", authorization)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("downstream PATCH {}{} failed: {}", baseUrl, path, e.getMessage());
            return false;
        }
    }

    /**
     * Forward an uploaded file to a downstream multipart endpoint (e.g. doc-ingest
     * /upload), preserving the caller's identity + tenant and adding string form
     * fields. Null {@code bytes} sends the form fields alone. Returns null on any
     * failure so the caller can still persist locally.
     *
     * <p>The body is a plain {@link MultiValueMap}, written by the servlet stack's form
     * converter. Not {@code MultipartBodyBuilder}: it links against reactive-streams, which
     * this service doesn't ship, so every multipart forward died with NoClassDefFoundError.
     * That's also why {@code LinkageError} is caught below.</p>
     */
    public JsonNode postFile(String baseUrl, String path, String authorization, String tenantId,
                             byte[] bytes, String filename, Map<String, String> fields) {
        try {
            MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
            if (bytes != null) {
                parts.add("file", new ByteArrayResource(bytes) {
                    @Override public String getFilename() { return filename; }
                });
            }
            if (fields != null) {
                fields.forEach((k, v) -> { if (v != null && !v.isBlank()) parts.add(k, v); });
            }
            return http.post()
                    .uri(baseUrl + path)
                    .header("Authorization", authorization)
                    .header("X-Tenant-Id", tenantId)
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception | LinkageError e) {
            log.warn("downstream POST {}{} (multipart) failed: {}", baseUrl, path, e.getMessage());
            return null;
        }
    }
}
