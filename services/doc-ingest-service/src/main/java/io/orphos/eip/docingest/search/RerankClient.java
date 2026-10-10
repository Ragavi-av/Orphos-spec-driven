package io.orphos.eip.docingest.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Cross-encoder re-ranking client.
 *
 * <p>Calls an external cross-encoder reranker over HTTP using the widely-used
 * HuggingFace Text-Embeddings-Inference (TEI) {@code /rerank} contract:
 * {@code POST {url} {"query": "...", "texts": ["...", ...]}} →
 * {@code [{"index": i, "score": s}, ...]}. A cross-encoder scores each
 * (query, passage) pair jointly, which is more precise than the bi-encoder
 * cosine similarity used for first-stage retrieval — at higher latency.</p>
 *
 * <p>If {@code doc-ingest.rerank.url} is not configured, re-ranking is a safe
 * pass-through (original order preserved) and a one-time notice is logged. This
 * lets the feature ship fully wired: deploy a TEI reranker (e.g.
 * {@code BAAI/bge-reranker-base}) and set the URL to activate it, with no code
 * change.</p>
 */
@Component
public class RerankClient {

    private static final Logger log = LogManager.getLogger(RerankClient.class);

    @Value("${doc-ingest.rerank.url:}")
    private String rerankUrl;

    @Value("${doc-ingest.rerank.timeout-ms:8000}")
    private int timeoutMs;

    private final ObjectMapper mapper = new ObjectMapper();
    private RestTemplate rest;
    private volatile boolean warnedUnconfigured = false;

    public boolean isConfigured() {
        return rerankUrl != null && !rerankUrl.isBlank();
    }

    /**
     * Returns the indices of {@code passages} re-ordered most-relevant first.
     * On any failure (or when unconfigured) returns the identity order so the
     * caller falls back to the first-stage ranking without erroring.
     */
    public List<Integer> rerankOrder(String query, List<String> passages) {
        List<Integer> identity = new ArrayList<>(passages.size());
        for (int i = 0; i < passages.size(); i++) identity.add(i);

        if (!isConfigured()) {
            if (!warnedUnconfigured) {
                log.info("Cross-encoder re-ranking requested but doc-ingest.rerank.url is not set — "
                    + "returning first-stage order. Configure a TEI reranker endpoint to enable it.");
                warnedUnconfigured = true;
            }
            return identity;
        }
        if (passages.isEmpty()) return identity;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new HashMap<>();
            body.put("query", query);
            body.put("texts", passages);

            String resp = restTemplate().postForObject(rerankUrl, new HttpEntity<>(body, headers), String.class);
            JsonNode arr = mapper.readTree(resp);
            if (!arr.isArray()) return identity;

            // Collect (index, score), sort by score desc, then append any passage
            // the reranker omitted (e.g. if it applied a top_n) so no candidate is lost.
            double[] scores = new double[passages.size()];
            boolean[] seen  = new boolean[passages.size()];
            java.util.Arrays.fill(scores, Double.NEGATIVE_INFINITY);
            for (JsonNode n : arr) {
                int idx = n.path("index").asInt(-1);
                if (idx < 0 || idx >= passages.size()) continue;
                scores[idx] = n.path("score").asDouble(Double.NEGATIVE_INFINITY);
                seen[idx] = true;
            }

            List<Integer> result = new ArrayList<>(identity);
            result.sort((a, b) -> {
                if (seen[a] != seen[b]) return seen[a] ? -1 : 1;   // ranked first
                return Double.compare(scores[b], scores[a]);
            });
            return result;
        } catch (Exception e) {
            log.warn("Cross-encoder rerank call failed ({}) — falling back to first-stage order: {}",
                rerankUrl, e.getMessage());
            return identity;
        }
    }

    private RestTemplate restTemplate() {
        if (rest == null) {
            var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
            factory.setConnectTimeout((int) Duration.ofMillis(timeoutMs).toMillis());
            factory.setReadTimeout(timeoutMs);
            rest = new RestTemplate(factory);
        }
        return rest;
    }
}
