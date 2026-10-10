package io.orphos.eip.admin.voice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Minimal Docker Engine client over the mounted socket, for the voice module's
 * automatic lifecycle.
 *
 * <p>Same transport as {@code AdminServicesController} ({@code curl --unix-socket},
 * no Docker SDK), but it reads the HTTP status: an unattended reconciler has to
 * tell "container does not exist" (404) and "already started" (304) apart from a
 * real failure, which curl's exit code alone cannot.</p>
 */
@Component
public class DockerSocketClient {

    private static final String DOCKER_SOCK = "/var/run/docker.sock";
    private static final String API = "http://localhost/v1.43";

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Docker's {@code State.Status} for a container — created, running, paused,
     * restarting, removing, exited or dead — or empty when it does not exist.
     *
     * @throws IllegalStateException when the Docker daemon cannot be asked
     */
    public Optional<String> containerState(String name) {
        Response r = call("GET", "/containers/" + name + "/json");
        if (r.status() == 404) return Optional.empty();
        if (r.status() != 200) {
            throw new IllegalStateException("Docker inspect " + name + " returned HTTP " + r.status());
        }
        try {
            JsonNode node = mapper.readTree(r.body());
            return Optional.of(node.path("State").path("Status").asText("unknown"));
        } catch (Exception e) {
            throw new IllegalStateException("Unreadable Docker inspect for " + name, e);
        }
    }

    /**
     * Runs a lifecycle action ({@code start}, {@code stop}, {@code unpause}).
     * Returns true when Docker accepted it or the container was already in the
     * requested state (204 / 304).
     */
    public boolean action(String name, String action) {
        int status = call("POST", "/containers/" + name + "/" + action).status();
        return status == 204 || status == 304;
    }

    private record Response(int status, String body) {}

    private Response call(String method, String path) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "curl", "--silent", "--unix-socket", DOCKER_SOCK,
                "--max-time", "60",
                "-X", method, "-w", "\n%{http_code}",
                API + path
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))
                .lines().collect(Collectors.joining("\n"));
            if (!p.waitFor(70, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("Docker " + method + " " + path + " timed out");
            }
            int nl = out.lastIndexOf('\n');
            String code = (nl >= 0 ? out.substring(nl + 1) : out).trim();
            String body = nl >= 0 ? out.substring(0, nl) : "";
            return new Response(Integer.parseInt(code), body);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Docker " + method + " " + path + " failed: " + e.getMessage(), e);
        }
    }
}
