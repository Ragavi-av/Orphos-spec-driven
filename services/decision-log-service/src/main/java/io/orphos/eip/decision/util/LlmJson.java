package io.orphos.eip.decision.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Optional;

/**
 * Lenient JSON extraction from LLM completions. Small models wrap JSON in
 * markdown fences or chatter around it — this finds the first balanced
 * {@code {...}} object and parses that, mirroring mail-insight's
 * LlmClassifier approach.
 */
public final class LlmJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LlmJson() {}

    public static Optional<JsonNode> parse(String completion) {
        if (completion == null || completion.isBlank()) return Optional.empty();
        String candidate = completion.strip();
        int start = candidate.indexOf('{');
        if (start < 0) return Optional.empty();
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < candidate.length(); i++) {
            char c = candidate.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        return Optional.of(MAPPER.readTree(candidate.substring(start, i + 1)));
                    } catch (Exception e) {
                        return Optional.empty();
                    }
                }
            }
        }
        return Optional.empty();
    }

    public static String text(JsonNode node, String field, String fallback) {
        if (node == null) return fallback;
        String v = node.path(field).asText("");
        return v.isBlank() ? fallback : v;
    }

    public static double clamp01(double d) {
        return Math.max(0.0, Math.min(1.0, d));
    }
}
