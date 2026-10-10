package io.orphos.eip.setup.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.setup.dto.SetupDtos.Functionality;
import io.orphos.eip.setup.dto.SetupDtos.InstallPlan;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * The selectable-functionality catalogue and the resolution from a chosen set to
 * the concrete container list.
 *
 * <p><b>Dynamic source of truth.</b> Rather than hardcode the catalogue (which
 * drifts from the bring-up script), this reads it at runtime from
 * {@code startmodules.sh list-json} — the same script the wizard runs to start
 * the platform — so the install UI's options are always exactly what
 * {@code startmodules.bat} / {@code ./startmodules.sh} offers. A built-in fallback (the app-level
 * features) keeps the wizard usable when the script cannot be executed (no bash, or
 * launched outside a platform root).</p>
 */
@Service
public class FunctionalityCatalog {

    private static final Logger log = LogManager.getLogger(FunctionalityCatalog.class);

    private final SetupPaths paths;
    private final ShellLauncher shell;
    private final ObjectMapper mapper = new ObjectMapper()
            // Kept lenient: a single-element list emitted as a scalar still parses,
            // so plugins/services/mcp/agents survive a hand-edit of the catalogue.
            .enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Parsed once (cached); the catalogue is static for the wizard's lifetime. */
    private volatile Catalog cached;

    public FunctionalityCatalog(SetupPaths paths, ShellLauncher shell) {
        this.paths = paths;
        this.shell = shell;
    }

    // ── JSON shape emitted by `startmodules.sh list-json` ───────────────────
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Base(List<String> backend, List<String> ui) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record FnEntry(String key, String group, String description,
                   List<String> plugins, List<String> services,
                   List<String> mcp, List<String> agents, boolean ai) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Catalog(Base base, List<FnEntry> functionalities, Map<String, List<String>> aliases) {}

    private Catalog catalog() {
        Catalog c = cached;
        if (c == null) {
            synchronized (this) {
                if (cached == null) cached = load();
                c = cached;
            }
        }
        return c;
    }

    private Catalog load() {
        Catalog dyn = loadFromScript();
        if (dyn != null && dyn.functionalities() != null && !dyn.functionalities().isEmpty()) {
            log.info("Functionality catalogue loaded from startmodules.sh ({} functionalities).",
                    dyn.functionalities().size());
            return dyn;
        }
        log.warn("Using the built-in fallback functionality catalogue "
                + "(startmodules.sh list-json unavailable — no bash, missing script, or error).");
        return fallback();
    }

    /** Runs {@code startmodules.sh list-json} and parses its JSON. Null on any failure. */
    private Catalog loadFromScript() {
        Path script = paths.targetDir().resolve("startmodules.sh");
        if (!Files.isReadable(script) || shell.bash().isEmpty()) return null;
        try {
            // list-json exits before touching Docker, so this is safe to run at
            // startup on any host that has bash.
            Process p = new ProcessBuilder(shell.command("startmodules.sh", List.of("list-json")))
                    .directory(paths.targetDir().toFile())
                    .start();
            String out;
            try (var in = p.getInputStream()) {
                out = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); return null; }
            // The mode prints ONLY the JSON, but trim to the first '{' as a guard.
            int i = out.indexOf('{');
            if (i < 0) return null;
            return mapper.readValue(out.substring(i), Catalog.class);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.warn("Could not read functionality catalogue from startmodules.sh: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, FnEntry> byKey(Catalog cat) {
        Map<String, FnEntry> m = new LinkedHashMap<>();
        for (FnEntry f : cat.functionalities()) m.put(f.key(), f);
        return m;
    }

    private static List<String> nz(List<String> l) {
        return l == null ? List.of() : l;
    }

    /** The selectable functionalities (server-authoritative catalogue). */
    public List<Functionality> all() {
        Catalog cat = catalog();
        List<Functionality> out = new ArrayList<>();
        for (FnEntry f : cat.functionalities()) {
            Set<String> containers = new LinkedHashSet<>();
            containers.addAll(nz(f.plugins()));
            containers.addAll(nz(f.services()));
            containers.addAll(nz(f.mcp()));
            containers.addAll(nz(f.agents()));
            out.add(new Functionality(f.key(), f.description(), f.group(),
                    List.copyOf(containers), f.ai()));
        }
        return out;
    }

    /** True for any catalogue functionality key OR alias key. */
    public boolean isKnown(String key) {
        if (key == null) return false;
        String k = key.trim();
        Catalog cat = catalog();
        return byKey(cat).containsKey(k) || (cat.aliases() != null && cat.aliases().containsKey(k));
    }

    /**
     * Resolves the chosen functionality/alias keys into the concrete,
     * de-duplicated container list (base control-plane + UI + each feature's
     * plugins/services/MCP/agents) and whether the Ollama AI layer is required.
     * Aliases are expanded exactly like startmodules.sh. Unknown keys are ignored.
     */
    public InstallPlan resolve(List<String> functionalities) {
        Catalog cat = catalog();
        Map<String, FnEntry> byKey = byKey(cat);
        Map<String, List<String>> aliases = cat.aliases() == null ? Map.of() : cat.aliases();

        // Accepted = the selected keys the platform understands (alias keys kept
        // verbatim — startmodules expands them at run time). Expanded = the feature
        // keys the container preview is built from.
        List<String> accepted = new ArrayList<>();
        LinkedHashSet<String> expanded = new LinkedHashSet<>();
        for (String raw : (functionalities == null ? List.<String>of() : functionalities)) {
            if (raw == null) continue;
            String key = raw.trim();
            if (aliases.containsKey(key)) {
                accepted.add(key);
                expanded.addAll(aliases.get(key));
            } else if (byKey.containsKey(key)) {
                accepted.add(key);
                expanded.add(key);
            }
        }

        Set<String> containers = new LinkedHashSet<>();
        containers.addAll(nz(cat.base() == null ? null : cat.base().backend()));
        containers.addAll(nz(cat.base() == null ? null : cat.base().ui()));
        boolean needsAi = false;
        for (String key : expanded) {
            FnEntry f = byKey.get(key);
            if (f == null) continue;
            containers.addAll(nz(f.plugins()));
            containers.addAll(nz(f.services()));
            containers.addAll(nz(f.mcp()));
            containers.addAll(nz(f.agents()));
            needsAi = needsAi || f.ai();
        }
        List<String> serviceList = List.copyOf(containers);
        return new InstallPlan(accepted, serviceList, needsAi, serviceList.size());
    }

    // ── Built-in fallback (app features only) ────────────────────────────────
    // Used only when startmodules.sh cannot be run. Mirrors its `app`-group
    // functionalities + the mandatory base so the wizard stays usable in dev.
    private static Catalog fallback() {
        Base base = new Base(
                List.of("platform-core", "platform-registry", "admin-console", "widgets"),
                List.of("shell", "plugin-widgets"));
        List<FnEntry> fns = List.of(
                new FnEntry("admin", "app", "Platform admin console (tenant / branding / config / RBAC / onboarding)",
                        List.of(), List.of(), List.of(), List.of(), false),
                new FnEntry("core", "app", "Lean core knowledge-graph & insight services (graph, OKG, priority, briefing, decision log)",
                        List.of("plugin-decision-log"),
                        List.of("graph-proxy", "okg-ingest", "okg-reasoning", "priority-engine", "briefing-service", "decision-log"),
                        List.of(), List.of(), true),
                new FnEntry("dataingestion", "app", "Enterprise data ingestion (sources, mappings, batch jobs)",
                        List.of(), List.of("data-ingestion", "okg-ingest"), List.of(), List.of(), false),
                new FnEntry("docingest", "app", "Document ingestion (doc-ingest + Docling parser)",
                        List.of("plugin-doc-ingest"), List.of("doc-ingest", "docling"), List.of(), List.of(), true),
                new FnEntry("aicopilot", "app", "AI Co-pilot (NL query, RAG document search, SPARQL chat)",
                        List.of("plugin-ai-copilot", "plugin-rag-search"),
                        List.of("nl-query", "doc-ingest", "graph-proxy"), List.of(), List.of(), true),
                new FnEntry("communication", "app", "Email + channel communication (mail insight, Slack/Teams, notifications)",
                        List.of("plugin-email-scanner"),
                        List.of("mail-insight", "notification-service", "nl-query"), List.of(), List.of(), true));
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        aliases.put("email", List.of("communication"));
        return new Catalog(base, fns, aliases);
    }
}
