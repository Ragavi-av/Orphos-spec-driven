package io.orphos.eip.setup.web;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.setup.dto.SetupDtos.CreateDbRequest;
import io.orphos.eip.setup.dto.SetupDtos.ExternalDb;
import io.orphos.eip.setup.dto.SetupDtos.RunRequest;
import io.orphos.eip.setup.dto.SetupDtos.SetupConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * A deliberately small HTTP/1.1 server built on <b>blocking</b> {@link ServerSocket}
 * sockets, used only when the JVM cannot create an NIO {@link java.nio.channels.Selector}.
 *
 * <p><b>Why this exists.</b> Some Windows hosts refuse the internal socket pair
 * that a selector needs — {@code Selector.open()} throws
 * {@code IOException: Unable to establish loopback connection} — while ordinary
 * blocking sockets keep working. Embedded Tomcat (and Jetty, Undertow, and the
 * JDK's own {@code com.sun.net.httpserver}) are all selector-based, so on such a
 * host every one of them fails to start. The packaged launcher is a windowed app
 * with no console, so the operator's only symptom was a bare
 * <i>"Failed to launch JVM"</i> dialog with nothing to act on. Neither selector
 * provider helps: {@code WEPollSelectorProvider} and the legacy
 * {@code WindowsSelectorProvider} both need the pair.</p>
 *
 * <p>So the wizard degrades instead of dying. {@code SetupWizardApplication}
 * probes for a selector before choosing a web stack; when there isn't one it runs
 * Spring with no web server and serves the same UI and the same
 * {@link SetupController} methods through this class. One thread per connection
 * and {@code Connection: close} throughout — this serves exactly one operator on
 * loopback, so simplicity is worth more than concurrency here.</p>
 *
 * <p>This is a fallback, not a replacement: when a selector is available Tomcat is
 * used exactly as before.</p>
 */
@Component
public class FallbackHttpServer {

    private static final Logger log = LogManager.getLogger(FallbackHttpServer.class);

    /** Cap on a request body — the largest real payload is the install config. */
    private static final int MAX_BODY = 2 * 1024 * 1024;

    private final SetupController api;
    private final ObjectMapper json;

    public FallbackHttpServer(SetupController api, ObjectMapper json) {
        this.api = api;
        this.json = json;
    }

    /**
     * Binds the port and serves until the process exits. Blocks the calling
     * thread, which is the point: it keeps {@code main} alive the way a Spring
     * web server would.
     */
    public void serve(int port) throws IOException {
        try (ServerSocket server = openOrExplain(port)) {
            String url = "http://localhost:" + server.getLocalPort();
            log.info("Setup wizard ready at {}  (fallback server — no NIO selector on this host)", url);
            BrowserLauncher.openIfEnabled(url);
            while (!server.isClosed()) {
                Socket socket = server.accept();
                Thread worker = new Thread(() -> handleQuietly(socket), "wizard-http");
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    /**
     * Binds the port, or fails with something an operator can act on. A bare
     * {@link java.net.BindException} here reaches the packaged launcher as
     * "Failed to launch JVM" and tells nobody anything; the usual cause is simply
     * that the wizard is already open.
     */
    private ServerSocket openOrExplain(int port) throws IOException {
        try {
            return new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        } catch (java.net.BindException e) {
            String url = "http://localhost:" + port;
            log.error("Port {} is already in use, so the wizard cannot start a second copy.", port);
            log.error("If the wizard is already open, use it at {}. Otherwise close whatever "
                    + "is using port {} (or set ORPHOS_SETUP_PORT to a free port) and try again.",
                    url, port);
            throw e;
        }
    }

    private void handleQuietly(Socket socket) {
        try (Socket s = socket) {
            s.setTcpNoDelay(true);
            handle(s);
        } catch (Exception e) {
            log.debug("Connection ended: {}", e.getMessage());
        }
    }

    // ── request handling ─────────────────────────────────────────────────────

    private void handle(Socket socket) throws Exception {
        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        String requestLine = readLine(in);
        if (requestLine == null || requestLine.isBlank()) return;
        String[] parts = requestLine.split(" ");
        if (parts.length < 2) { send(out, 400, "text/plain", "Bad request".getBytes(StandardCharsets.UTF_8)); return; }
        String method = parts[0];
        String path = parts[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);

        int contentLength = 0;
        for (String header; (header = readLine(in)) != null && !header.isEmpty(); ) {
            int colon = header.indexOf(':');
            if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                try { contentLength = Integer.parseInt(header.substring(colon + 1).trim()); } catch (NumberFormatException ignored) { }
            }
        }
        if (contentLength < 0 || contentLength > MAX_BODY) {
            send(out, 413, "text/plain", "Body too large".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] body = in.readNBytes(contentLength);

        try {
            route(method, path, body, out);
        } catch (Exception e) {
            log.warn("{} {} failed: {}", method, path, e.toString());
            send(out, 500, "text/plain", ("Setup error: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private void route(String method, String path, byte[] body, OutputStream out) throws Exception {
        if ("GET".equals(method)) {
            switch (path) {
                case "/", "/index.html" -> { sendStatic(out); return; }
                case "/api/target-dir" -> { sendText(out, api.targetDir()); return; }
                case "/api/functionalities" -> { sendJson(out, api.functionalities()); return; }
                case "/api/ai/local-models" -> { sendJson(out, api.localModels()); return; }
                case "/api/ai/providers" -> { sendJson(out, api.aiProviders()); return; }
                case "/api/secrets/generate" -> { sendJson(out, api.generateSecrets()); return; }
                case "/api/existing-install" -> { sendJson(out, api.existingInstall()); return; }
                default -> { send(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8)); return; }
            }
        }
        if ("POST".equals(method)) {
            switch (path) {
                case "/api/plan" -> {
                    sendJson(out, api.plan(json.readValue(body, new TypeReference<List<String>>() {})));
                    return;
                }
                case "/api/validate/arcade" -> { sendJson(out, api.validateArcade(json.readValue(body, ExternalDb.class))); return; }
                case "/api/validate/postgres" -> { sendJson(out, api.validatePostgres(json.readValue(body, ExternalDb.class))); return; }
                case "/api/db/arcade/create" -> { sendJson(out, api.createArcadeDb(json.readValue(body, CreateDbRequest.class))); return; }
                case "/api/install" -> { sendJson(out, api.install(json.readValue(body, SetupConfig.class))); return; }
                case "/api/run" -> { streamRun(out, json.readValue(body, RunRequest.class)); return; }
                default -> { send(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8)); return; }
            }
        }
        send(out, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8));
    }

    /**
     * The bring-up log, streamed. No {@code Content-Length}: the response is
     * delimited by the connection close, which is how the browser's fetch reader
     * receives it progressively rather than in one lump at the end.
     */
    private void streamRun(OutputStream out, RunRequest req) throws Exception {
        writeHeaders(out, 200, "text/plain; charset=utf-8", -1);
        api.run(req).writeTo(out);   // StreamingResponseBody — writes and flushes as it goes
        out.flush();
    }

    // ── responses ────────────────────────────────────────────────────────────

    private void sendStatic(OutputStream out) throws IOException {
        try (InputStream res = getClass().getClassLoader().getResourceAsStream("static/index.html")) {
            if (res == null) {
                send(out, 500, "text/plain", "index.html missing from the jar".getBytes(StandardCharsets.UTF_8));
                return;
            }
            send(out, 200, "text/html; charset=utf-8", res.readAllBytes());
        }
    }

    private void sendText(OutputStream out, String value) throws IOException {
        send(out, 200, "text/plain; charset=utf-8", (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private void sendJson(OutputStream out, Object value) throws IOException {
        send(out, 200, "application/json; charset=utf-8", json.writeValueAsBytes(value));
    }

    private void send(OutputStream out, int status, String contentType, byte[] body) throws IOException {
        writeHeaders(out, status, contentType, body.length);
        out.write(body);
        out.flush();
    }

    private void writeHeaders(OutputStream out, int status, String contentType, int length) throws IOException {
        StringBuilder h = new StringBuilder(160);
        h.append("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n")
         .append("Content-Type: ").append(contentType).append("\r\n");
        if (length >= 0) h.append("Content-Length: ").append(length).append("\r\n");
        h.append("Cache-Control: no-store\r\n")
         .append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
    }

    private static String reason(int status) {
        return switch (status) {
            case 200 -> "OK";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 413 -> "Payload Too Large";
            default -> "Internal Server Error";
        };
    }

    /** Reads one CRLF-terminated line without buffering past it. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') break;
            if (c != '\r') buf.write(c);
        }
        if (c == -1 && buf.size() == 0) return null;
        return buf.toString(StandardCharsets.UTF_8);
    }
}
