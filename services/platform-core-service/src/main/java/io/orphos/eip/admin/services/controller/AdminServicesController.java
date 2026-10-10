package io.orphos.eip.admin.services.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Platform-admin-only endpoint for inspecting and controlling Docker containers
 * that make up the EIP stack.
 *
 * <p>Uses {@code curl --unix-socket /var/run/docker.sock} via {@link ProcessBuilder}
 * so no Docker Java SDK dependency is needed. The Docker socket must be mounted
 * into this container (see docker-compose.app.yml).</p>
 *
 * <p>Only containers in {@link #META} can be targeted by the control actions —
 * arbitrary container names are rejected with 400 to prevent accidental (or
 * malicious) control of containers outside the EIP stack.</p>
 *
 * <p>Each service is annotated with a functional {@code category} and the compose
 * {@code stack} it is launched from so the UI can group them, and — where the
 * service exposes an HTTP health endpoint — a {@code healthUrl} that is probed
 * on-demand to report <em>real</em> reachability rather than just Docker's
 * process state.</p>
 */
@RestController
@RequestMapping("/api/admin/services")
@PreAuthorize("hasRole('platform_admin')")
public class AdminServicesController {

    private static final Logger log = LogManager.getLogger(AdminServicesController.class);
    private static final String DOCKER_SOCK = "/var/run/docker.sock";

    /** Functional grouping (the "By function" view). */
    static final class Category {
        static final String PLATFORM      = "Platform & Gateway";
        static final String APPLICATION   = "Application Services";
        static final String INTELLIGENCE  = "Intelligence & Processing";
        static final String DATA          = "Data Stores";
        static final String AI            = "AI / ML";
        static final String AUTH          = "Auth & Secrets";
        static final String OBSERVABILITY = "Observability";
        static final String AGENTS        = "Agents";
        static final String MCP_SERVERS   = "MCP Servers";
        static final String PLUGINS       = "Plugins";
    }

    /** Compose-file grouping (the "By stack" view) — mirrors docker-compose.*.yml. */
    static final class Stack {
        static final String APPLICATION    = "Application";
        static final String DATA           = "Data";
        static final String AI             = "AI/ML";
        static final String INFRASTRUCTURE = "Infrastructure";
    }

    /**
     * Static metadata for every managed container. The key set doubles as the
     * control allow-list. {@code healthUrl} is optional (null = no HTTP probe;
     * fall back to Docker state/health). Health URLs use the internal container
     * network (this service shares the eip-net network) and are derived from the
     * healthcheck definitions / actuator ports in the docker-compose files.
     */
    private record ServiceMeta(String category, String stack, String healthUrl) {}

    private static final Map<String, ServiceMeta> META = buildMeta();

    private static Map<String, ServiceMeta> buildMeta() {
        Map<String, ServiceMeta> m = new LinkedHashMap<>();

        // ── Platform & Gateway ────────────────────────────────────────────────
        m.put("eip-traefik",           new ServiceMeta(Category.PLATFORM, Stack.INFRASTRUCTURE, null));
        m.put("eip-shell",             new ServiceMeta(Category.PLATFORM, Stack.APPLICATION,    "http://eip-shell:80/"));
        m.put("eip-platform-core",     new ServiceMeta(Category.PLATFORM, Stack.APPLICATION,    "http://eip-platform-core:8090/actuator/health"));
        m.put("eip-platform-registry", new ServiceMeta(Category.PLATFORM, Stack.APPLICATION,    "http://eip-platform-registry:8093/actuator/health"));

        // ── Application Services ──────────────────────────────────────────────
        m.put("eip-admin-console",     new ServiceMeta(Category.APPLICATION, Stack.APPLICATION, "http://eip-admin-console:8094/actuator/health"));
        m.put("eip-widgets",           new ServiceMeta(Category.APPLICATION, Stack.APPLICATION, "http://eip-widgets:8095/actuator/health"));
        m.put("eip-briefing",          new ServiceMeta(Category.APPLICATION, Stack.APPLICATION, "http://eip-briefing:8084/actuator/health"));
        m.put("eip-notification",      new ServiceMeta(Category.APPLICATION, Stack.APPLICATION, "http://eip-notification:8087/actuator/health"));
        m.put("eip-priority-engine",   new ServiceMeta(Category.APPLICATION, Stack.APPLICATION, "http://eip-priority-engine:8083/actuator/health"));
        m.put("eip-agents",            new ServiceMeta(Category.APPLICATION, Stack.APPLICATION, "http://eip-agents:8085/actuator/health"));
        m.put("eip-mail-insight",      new ServiceMeta(Category.APPLICATION, Stack.APPLICATION, "http://eip-mail-insight:8092/actuator/health"));
        m.put("eip-doc-ingest",        new ServiceMeta(Category.APPLICATION, Stack.APPLICATION, "http://eip-doc-ingest:8091/actuator/health"));
        m.put("eip-decision-log",      new ServiceMeta(Category.APPLICATION, Stack.APPLICATION, "http://eip-decision-log:8086/actuator/health"));

        // ── Intelligence & Processing ─────────────────────────────────────────
        m.put("eip-data-ingestion",    new ServiceMeta(Category.INTELLIGENCE, Stack.APPLICATION, "http://eip-data-ingestion:8096/actuator/health"));
        m.put("eip-okg-ingest",        new ServiceMeta(Category.INTELLIGENCE, Stack.APPLICATION, "http://eip-okg-ingest:8081/actuator/health"));
        m.put("eip-okg-reasoning",     new ServiceMeta(Category.INTELLIGENCE, Stack.APPLICATION, "http://eip-okg-reasoning:8088/actuator/health"));
        m.put("eip-graph-proxy",       new ServiceMeta(Category.INTELLIGENCE, Stack.APPLICATION, "http://eip-graph-proxy:8080/actuator/health"));
        m.put("eip-nl-query",          new ServiceMeta(Category.INTELLIGENCE, Stack.APPLICATION, "http://eip-nl-query:8082/api/nl-query/health"));
        // docling-serve (FastAPI) exposes /health → {"status":"ok"}; no Docker healthcheck otherwise.
        m.put("eip-docling",           new ServiceMeta(Category.INTELLIGENCE, Stack.APPLICATION, "http://eip-docling:5001/health"));
        // ── Voice (opt-in functionality) ──────────────────────────────────────
        // These two are created by compose but deliberately NOT started (see the
        // "voice" profile and the --no-start step in start.sh), so voice costs
        // nothing until a tenant has it on. Their lifecycle is owned by
        // io.orphos.eip.admin.voice.VoiceModuleReconciler, which follows the
        // per-tenant toggles in Admin → Voice; they stay in this allow-list so
        // the Services tab can still show and, if needed, control them.
        // Whisper is uvicorn/FastAPI: it serves the Swagger page at /docs but has
        // no health route, so /docs is the only honest liveness probe it offers.
        m.put("eip-whisper",           new ServiceMeta(Category.INTELLIGENCE, Stack.APPLICATION, "http://eip-whisper:9000/docs"));
        m.put("eip-voice",             new ServiceMeta(Category.INTELLIGENCE, Stack.APPLICATION, "http://eip-voice:8099/actuator/health"));

        // ── Data Stores ───────────────────────────────────────────────────────
        m.put("eip-arcadedb",          new ServiceMeta(Category.DATA, Stack.DATA,           null)); // Docker healthcheck (Basic-auth curl) already reports health
        m.put("eip-fuseki",            new ServiceMeta(Category.DATA, Stack.DATA,           "http://eip-fuseki:3030/$/ping"));
        // Qdrant liveness endpoint, auth-free 200 "healthz check passed"; no Docker healthcheck.
        m.put("eip-qdrant",            new ServiceMeta(Category.DATA, Stack.DATA,           "http://eip-qdrant:6333/healthz"));
        m.put("eip-kafka",             new ServiceMeta(Category.DATA, Stack.INFRASTRUCTURE, null)); // KRaft broker — TCP only, no HTTP health
        m.put("eip-postgres",          new ServiceMeta(Category.DATA, Stack.INFRASTRUCTURE, null)); // TCP only; Docker healthcheck (pg_isready) reports health

        // ── AI / ML ───────────────────────────────────────────────────────────
        // Ollama serves 200 "Ollama is running" at /; no Docker healthcheck.
        m.put("eip-ollama",            new ServiceMeta(Category.AI, Stack.AI, "http://eip-ollama:11434/"));
        m.put("eip-ollama-hi",         new ServiceMeta(Category.AI, Stack.AI, "http://eip-ollama-hi:11434/"));

        // ── Auth & Secrets ────────────────────────────────────────────────────
        m.put("eip-keycloak",          new ServiceMeta(Category.AUTH, Stack.INFRASTRUCTURE, null)); // health on separate mgmt port — rely on Docker health
        m.put("eip-infisical",         new ServiceMeta(Category.AUTH, Stack.INFRASTRUCTURE, "http://eip-infisical:8080/api/status"));
        m.put("eip-infisical-db",      new ServiceMeta(Category.AUTH, Stack.INFRASTRUCTURE, null)); // TCP only
        m.put("eip-infisical-redis",   new ServiceMeta(Category.AUTH, Stack.INFRASTRUCTURE, null)); // TCP only

        // ── Observability ─────────────────────────────────────────────────────
        m.put("eip-prometheus",        new ServiceMeta(Category.OBSERVABILITY, Stack.APPLICATION,    "http://eip-prometheus:9090/-/healthy"));
        m.put("eip-perses",            new ServiceMeta(Category.OBSERVABILITY, Stack.APPLICATION,    null)); // health path version-dependent — rely on Docker state
        // VictoriaLogs single binary answers /health with 200 "OK".
        m.put("eip-victoria-logs",     new ServiceMeta(Category.OBSERVABILITY, Stack.INFRASTRUCTURE, "http://eip-victoria-logs:9428/health"));
        // Jaeger all-in-one UI returns 200 at the root on 16686.
        m.put("eip-jaeger",            new ServiceMeta(Category.OBSERVABILITY, Stack.INFRASTRUCTURE, "http://eip-jaeger:16686/"));
        m.put("eip-fluent-bit",        new ServiceMeta(Category.OBSERVABILITY, Stack.INFRASTRUCTURE, null)); // only the :24224 forward input is enabled, no HTTP monitor server

        // ── MCP Servers (Tier-3, hot-pluggable) ───────────────────────────────
        // Independently deployable; zero-or-more may be present (invariant I5).
        // Entries here only surface when the matching container is actually
        // running, so listing all of them is safe. Each is a Spring Boot app on
        // internal port 8080 exposing /actuator/health (health,info). They also
        // self-register with platform-registry, so their UP/DOWN additionally
        // shows in the Capability Catalog / Agents tab; this panel adds Docker
        // lifecycle control (start/stop/restart) on top.
        m.put("eip-mcp-priority",       new ServiceMeta(Category.MCP_SERVERS, Stack.APPLICATION, "http://eip-mcp-priority:8080/actuator/health"));
        m.put("eip-mcp-decision",       new ServiceMeta(Category.MCP_SERVERS, Stack.APPLICATION, "http://eip-mcp-decision:8080/actuator/health"));
        m.put("eip-mcp-rag",            new ServiceMeta(Category.MCP_SERVERS, Stack.APPLICATION, "http://eip-mcp-rag:8080/actuator/health"));
        m.put("eip-mcp-mail",           new ServiceMeta(Category.MCP_SERVERS, Stack.APPLICATION, "http://eip-mcp-mail:8080/actuator/health"));
        m.put("eip-mcp-comms",          new ServiceMeta(Category.MCP_SERVERS, Stack.APPLICATION, "http://eip-mcp-comms:8080/actuator/health"));
        m.put("eip-mcp-reasoning",      new ServiceMeta(Category.MCP_SERVERS, Stack.APPLICATION, "http://eip-mcp-reasoning:8080/actuator/health"));
        m.put("eip-mcp-graph",          new ServiceMeta(Category.MCP_SERVERS, Stack.APPLICATION, "http://eip-mcp-graph:8080/actuator/health"));
        m.put("eip-mcp-ingest",         new ServiceMeta(Category.MCP_SERVERS, Stack.APPLICATION, "http://eip-mcp-ingest:8080/actuator/health"));

        // ── Agents (Tier-3, hot-pluggable) ────────────────────────────────────
        // Same lifecycle as the MCP servers above; separated here so the Services
        // panel mirrors the Capability Catalog / Tiers tree split (Agents vs
        // MCP Servers). Each orchestrates MCP tools under a per-tenant autonomy.
        m.put("eip-agent-briefing",     new ServiceMeta(Category.AGENTS, Stack.APPLICATION, "http://eip-agent-briefing:8080/actuator/health"));
        m.put("eip-agent-decision",     new ServiceMeta(Category.AGENTS, Stack.APPLICATION, "http://eip-agent-decision:8080/actuator/health"));
        m.put("eip-agent-email-triage", new ServiceMeta(Category.AGENTS, Stack.APPLICATION, "http://eip-agent-email-triage:8080/actuator/health"));
        m.put("eip-agent-comms-triage", new ServiceMeta(Category.AGENTS, Stack.APPLICATION, "http://eip-agent-comms-triage:8080/actuator/health"));
        m.put("eip-agent-attention",    new ServiceMeta(Category.AGENTS, Stack.APPLICATION, "http://eip-agent-attention:8080/actuator/health"));

        // ── Plugins (Module Federation remotes) ───────────────────────────────
        // These are static nginx servers for a remote's remoteEntry.js + chunks;
        // their nginx is `try_files $uri =404` with no index at `/`, so probing
        // `/` always 404s and reports a false "unreachable". Probe the federation
        // entrypoint the shell actually imports instead — a 200 there is the
        // meaningful signal that the remote is serving what the SPA needs.
        // (vite-plugin-federation emits filename `remoteEntry.js` under assets/.)
        m.put("eip-plugin-ai-copilot",    new ServiceMeta(Category.PLUGINS, Stack.APPLICATION, "http://eip-plugin-ai-copilot:80/assets/remoteEntry.js"));
        m.put("eip-plugin-email-scanner", new ServiceMeta(Category.PLUGINS, Stack.APPLICATION, "http://eip-plugin-email-scanner:80/assets/remoteEntry.js"));
        m.put("eip-plugin-doc-ingest",    new ServiceMeta(Category.PLUGINS, Stack.APPLICATION, "http://eip-plugin-doc-ingest:80/assets/remoteEntry.js"));
        m.put("eip-plugin-rag-search",    new ServiceMeta(Category.PLUGINS, Stack.APPLICATION, "http://eip-plugin-rag-search:80/assets/remoteEntry.js"));
        m.put("eip-plugin-widgets",       new ServiceMeta(Category.PLUGINS, Stack.APPLICATION, "http://eip-plugin-widgets:80/assets/remoteEntry.js"));
        m.put("eip-plugin-decision-log",  new ServiceMeta(Category.PLUGINS, Stack.APPLICATION, "http://eip-plugin-decision-log:80/assets/remoteEntry.js"));

        return Map.copyOf(m);
    }

    private static final Set<String> ALLOWED_ACTIONS = Set.of(
        "start", "stop", "restart", "pause", "unpause"
    );

    /** Extracts the health token Docker embeds in the status string, e.g. "Up 2 minutes (healthy)". */
    private static final Pattern HEALTH_PATTERN =
        Pattern.compile("\\((healthy|unhealthy|health: starting)\\)");

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * List all EIP containers with their current status, health and reachability.
     *
     * @param probe when {@code true} (default) each running service that exposes an
     *              HTTP health endpoint is actively probed for reachability. Pass
     *              {@code false} for a faster, Docker-only response.
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listServices(
            @RequestParam(name = "probe", defaultValue = "true") boolean probe) {
        String raw = dockerGet("/v1.43/containers/json?all=true");
        if (raw == null) {
            return ResponseEntity.internalServerError().build();
        }
        try {
            JsonNode all = mapper.readTree(raw);
            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode c : all) {
                String name = primaryName(c.path("Names"));
                ServiceMeta meta = META.get(name);
                if (meta == null) continue;

                String state  = c.path("State").asText("unknown");
                String status = c.path("Status").asText("");
                String health = parseHealth(status);

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("name",     name);
                row.put("image",    c.path("Image").asText(""));
                row.put("state",    state);
                row.put("status",   status);
                row.put("health",   health);
                row.put("category", meta.category());
                row.put("stack",    meta.stack());
                row.put("probeable", meta.healthUrl() != null);
                row.put("reachable", "unknown");   // filled in by the probe pass below
                row.put("created",  c.path("Created").asLong(0));
                String id = c.path("Id").asText("");
                row.put("id",       id.substring(0, Math.min(12, id.length())));
                result.add(row);
            }

            if (probe) {
                runProbes(result);
            }
            // realStatus depends on the probe result, so compute it last.
            for (Map<String, Object> row : result) {
                row.put("realStatus", realStatus(row));
            }

            // sort by name for a stable order (the UI re-groups client-side)
            result.sort((a, b) -> a.get("name").toString().compareTo(b.get("name").toString()));
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("Failed to parse Docker container list", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Perform a lifecycle action on a single container.
     *
     * @param name   container name (must be in {@link #META})
     * @param action one of: start, stop, restart, pause, unpause
     */
    @PostMapping("/{name}/{action}")
    public ResponseEntity<Map<String, String>> action(
            @PathVariable String name,
            @PathVariable String action) {

        if (!META.containsKey(name)) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Container not in allowed list: " + name));
        }
        if (!ALLOWED_ACTIONS.contains(action)) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Unknown action: " + action));
        }

        String path = "/v1.43/containers/" + name + "/" + action;
        int exitCode = dockerPost(path);
        if (exitCode == 0) {
            return ResponseEntity.ok(Map.of("result", "ok", "container", name, "action", action));
        }
        return ResponseEntity.internalServerError()
            .body(Map.of("error", "Docker returned non-zero for " + action + " on " + name));
    }

    // ── status derivation ───────────────────────────────────────────────────

    /** Maps Docker's status string to a health token: healthy | unhealthy | starting | none. */
    private String parseHealth(String status) {
        if (status == null) return "none";
        Matcher m = HEALTH_PATTERN.matcher(status);
        if (!m.find()) return "none";
        String token = m.group(1);
        return token.startsWith("health: ") ? "starting" : token;   // "health: starting" -> "starting"
    }

    /**
     * Collapses Docker state + health + probe reachability into a single,
     * user-meaningful status the UI colours directly.
     */
    private String realStatus(Map<String, Object> row) {
        String state     = String.valueOf(row.get("state"));
        String health    = String.valueOf(row.get("health"));
        String reachable  = String.valueOf(row.get("reachable"));
        switch (state) {
            case "running":
                if ("unhealthy".equals(health)) return "unhealthy";
                if ("starting".equals(health))  return "starting";
                if ("healthy".equals(health))   return "healthy";
                // No Docker healthcheck — lean on the active probe if we have one.
                if ("down".equals(reachable))   return "unreachable";
                if ("up".equals(reachable))     return "healthy";
                return "running";
            case "paused":     return "paused";
            case "exited":     return "stopped";
            case "created":    return "created";
            case "restarting": return "restarting";
            default:           return state;
        }
    }

    /** Probes, in parallel, every running service that has an HTTP health URL. */
    private void runProbes(List<Map<String, Object>> rows) {
        List<Map<String, Object>> targets = rows.stream()
            .filter(r -> "running".equals(r.get("state")))
            .filter(r -> Boolean.TRUE.equals(r.get("probeable")))
            .collect(Collectors.toList());
        if (targets.isEmpty()) return;

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(16, targets.size()));
        try {
            Map<Map<String, Object>, java.util.concurrent.Future<String>> futures = new ConcurrentHashMap<>();
            for (Map<String, Object> r : targets) {
                String url = META.get(r.get("name")).healthUrl();
                futures.put(r, pool.submit(() -> probe(url) ? "up" : "down"));
            }
            for (Map.Entry<Map<String, Object>, java.util.concurrent.Future<String>> e : futures.entrySet()) {
                try {
                    e.getKey().put("reachable", e.getValue().get(4, TimeUnit.SECONDS));
                } catch (Exception ex) {
                    e.getKey().put("reachable", "down");
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Returns true if an HTTP GET to {@code url} succeeds (2xx/3xx) within the timeout. */
    private boolean probe(String url) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "curl", "--silent", "--output", "/dev/null",
                "--max-time", "3", "--connect-timeout", "2",
                "--fail", url
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String dockerGet(String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "curl", "--silent", "--unix-socket", DOCKER_SOCK,
                "http://localhost" + path
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));
            p.waitFor();
            return out;
        } catch (Exception e) {
            log.error("Docker GET {} failed", path, e);
            return null;
        }
    }

    /** Returns the curl process exit code (0 = success, non-zero = error). */
    private int dockerPost(String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "curl", "--silent", "--unix-socket", DOCKER_SOCK,
                "-X", "POST", "http://localhost" + path
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            return p.exitValue();
        } catch (Exception e) {
            log.error("Docker POST {} failed", path, e);
            return -1;
        }
    }

    /** Docker returns Names as ["/eip-admin", ...] — strip the leading slash. */
    private String primaryName(JsonNode names) {
        if (names.isArray() && names.size() > 0) {
            return names.get(0).asText("").replaceFirst("^/", "");
        }
        return "";
    }
}
