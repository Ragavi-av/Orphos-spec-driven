package io.orphos.eip.core.arcadedb;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Connection settings for {@link ArcadeDbTemplate}. Property prefix matches
 * the {@code arcadedb.*} keys every service's {@code application.yml} already
 * used to bind directly into its own hand-rolled ArcadeDB client via
 * ad-hoc {@code @Value} fields.
 */
@ConfigurationProperties(prefix = "arcadedb")
public class ArcadeDbProperties {

    private String url;
    private String user;
    private String password;
    private String database;

    /**
     * HTTP connect timeout (ms). A bare RestTemplate defaults to NO timeout, so
     * a stalled/unreachable ArcadeDB would pin the request thread forever — and
     * this is the primary DB path for the thin services. 5s is ample on a
     * healthy LAN/loopback link.
     */
    private int connectTimeoutMillis = 5_000;

    /**
     * HTTP read (socket) timeout (ms). Caps how long a single query/command may
     * block waiting for ArcadeDB before the thread is freed and the failure
     * surfaces as a degraded-data outage (ArcadeFailureTranslator).
     */
    private int readTimeoutMillis = 30_000;

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getDatabase() { return database; }
    public void setDatabase(String database) { this.database = database; }

    public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
    public void setConnectTimeoutMillis(int v) { this.connectTimeoutMillis = v; }

    public int getReadTimeoutMillis() { return readTimeoutMillis; }
    public void setReadTimeoutMillis(int v) { this.readTimeoutMillis = v; }
}
