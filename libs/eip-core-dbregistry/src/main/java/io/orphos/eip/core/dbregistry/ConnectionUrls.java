package io.orphos.eip.core.dbregistry;

/**
 * Derives a connection URL from a {@link ConnectionRegistry.Connection}. An
 * explicit {@code url} on the connection always wins; otherwise the URL is
 * built from {@code host}/{@code port} in the shape each database expects.
 * Shared so the bootstrap resolver and the admin-side validator agree on the
 * exact endpoint being used.
 */
public final class ConnectionUrls {

    private ConnectionUrls() {}

    public static final int DEFAULT_ARCADEDB_PORT = 2480;
    public static final int DEFAULT_POSTGRES_PORT = 5432;
    public static final int DEFAULT_NEO4J_PORT    = 7687;

    /** Endpoint for the given connection, or {@code null} if it cannot be derived. */
    public static String urlFor(ConnectionRegistry.Connection c) {
        if (c == null) return null;
        if (c.url() != null && !c.url().isBlank()) {
            return trimTrailingSlash(c.url().trim());
        }
        ConnectionRegistry.Type type = c.typeEnum();
        if (type == null || c.host() == null || c.host().isBlank()) {
            return null;
        }
        String host = c.host().trim();
        return switch (type) {
            case ARCADEDB -> scheme(c) + "://" + host + ":" + port(c, DEFAULT_ARCADEDB_PORT);
            case POSTGRES -> "jdbc:postgresql://" + host + ":" + port(c, DEFAULT_POSTGRES_PORT) + "/";
            case NEO4J    -> "bolt://" + host + ":" + port(c, DEFAULT_NEO4J_PORT);
        };
    }

    /** ArcadeDB scheme: {@code https} when the connection opts into TLS, else {@code http}. */
    private static String scheme(ConnectionRegistry.Connection c) {
        String explicit = c.options().get("scheme");
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        String tls = c.options().get("tls");
        return "true".equalsIgnoreCase(tls) ? "https" : "http";
    }

    private static int port(ConnectionRegistry.Connection c, int fallback) {
        return c.port() != null && c.port() > 0 ? c.port() : fallback;
    }

    private static String trimTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
