package io.orphos.eip.voice;

import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/** Tiny in-process HTTP server standing in for Whisper / platform-registry. */
public class FakeHttpServer implements AutoCloseable {

    public record Received(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
        public String bodyText() { return new String(body, StandardCharsets.UTF_8); }
        public String header(String name) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue().get(0);
            }
            return null;
        }
    }

    public record Reply(int status, String body) {}

    private final HttpServer server;
    private final List<Received> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger count = new AtomicInteger();
    private volatile Function<Received, Reply> handler = r -> new Reply(200, "");

    public FakeHttpServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(buf);
            Received r = new Received(ex.getRequestMethod(), ex.getRequestURI().getPath(),
                    ex.getRequestURI().getRawQuery(), Map.copyOf(ex.getRequestHeaders()), buf.toByteArray());
            requests.add(r);
            count.incrementAndGet();
            Reply reply = handler.apply(r);
            byte[] out = reply.body().getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(reply.status(), out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
    }

    public FakeHttpServer replyWith(int status, String body) {
        handler = r -> new Reply(status, body);
        return this;
    }

    public FakeHttpServer handle(Function<Received, Reply> h) {
        handler = h;
        return this;
    }

    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    public List<Received> requests() { return requests; }
    public int count() { return count.get(); }

    @Override public void close() { server.stop(0); }
}
