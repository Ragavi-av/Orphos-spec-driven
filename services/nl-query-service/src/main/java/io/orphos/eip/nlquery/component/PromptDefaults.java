package io.orphos.eip.nlquery.component;

import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the bundled default prompt catalog from
 * {@code resources/prompts/default-prompts.yml}.
 *
 * <p>These defaults serve two purposes:
 * <ul>
 *   <li><b>Seed</b> — inserted into ArcadeDB (as version 1, active) the first
 *       time a tenant's prompts are read.</li>
 *   <li><b>Fallback</b> — used directly when ArcadeDB is empty or unreachable,
 *       so the service always has a working prompt even before/without a DB.</li>
 * </ul>
 *
 * <p>The catalog is the single source of truth for the prompt text — the Java
 * code no longer embeds the big prompt strings.</p>
 */
@Component
public class PromptDefaults {

    private static final Logger log = LogManager.getLogger(PromptDefaults.class);
    private static final String RESOURCE = "prompts/default-prompts.yml";

    /** One catalog entry. */
    public record PromptDef(String promptKey, String kind, String name,
                            String description, int orderIndex, String content) {}

    // Insertion-ordered so seeding preserves the file order.
    private final Map<String, PromptDef> byKey = new LinkedHashMap<>();

    @PostConstruct
    @SuppressWarnings("unchecked")
    public void load() {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            Map<String, Object> root = new Yaml().loadAs(
                    new String(in.readAllBytes(), StandardCharsets.UTF_8), Map.class);
            Object list = root == null ? null : root.get("prompts");
            if (!(list instanceof List<?> entries)) {
                log.error("default-prompts.yml has no 'prompts' list — prompt defaults are empty");
                return;
            }
            for (Object o : entries) {
                if (!(o instanceof Map<?, ?> m)) continue;
                String key = str(m.get("promptKey"));
                if (key == null || key.isBlank()) continue;
                byKey.put(key, new PromptDef(
                        key,
                        str(m.get("kind")),
                        str(m.get("name")),
                        str(m.get("description")),
                        intval(m.get("orderIndex")),
                        m.get("content") == null ? "" : String.valueOf(m.get("content"))));
            }
            log.info("Loaded {} default prompt entries from {}", byKey.size(), RESOURCE);
        } catch (Exception e) {
            log.error("Failed to load {} — prompt defaults unavailable: {}", RESOURCE, e.getMessage());
        }
    }

    /** All defaults, in file order. */
    public List<PromptDef> all() {
        return new ArrayList<>(byKey.values());
    }

    /** The default definition for a prompt key, if present. */
    public Optional<PromptDef> get(String promptKey) {
        return Optional.ofNullable(byKey.get(promptKey));
    }

    /** The default content for a key, or empty string when unknown. */
    public String content(String promptKey) {
        PromptDef d = byKey.get(promptKey);
        return d == null ? "" : d.content();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intval(Object o) {
        if (o instanceof Number n) return n.intValue();
        try { return o == null ? 0 : Integer.parseInt(o.toString().trim()); }
        catch (NumberFormatException e) { return 0; }
    }
}
