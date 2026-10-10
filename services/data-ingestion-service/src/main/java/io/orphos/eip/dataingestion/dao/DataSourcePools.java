package io.orphos.eip.dataingestion.dao;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.dataingestion.component.SecretRefService;
import io.orphos.eip.dataingestion.repository.DataSourceRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One HikariCP pool per configured JDBC data source, created lazily and cached
 * by (tenant, dataSourceId). Gives metadata discovery and extraction jobs
 * concurrent, reusable connections instead of a fresh TCP+auth handshake per
 * operation (requirement 1.7).
 *
 * <p>Pools are evicted (closed) whenever the connection definition changes or
 * is deleted, so credential rotations take effect immediately.</p>
 */
@Component
public class DataSourcePools implements DisposableBean {

    private static final Logger log = LogManager.getLogger(DataSourcePools.class);

    private final SecretRefService secrets;
    private final int defaultMaxPoolSize;
    private final int connectionTimeoutMs;

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public DataSourcePools(SecretRefService secrets,
                           @Value("${ingestion.connection.default-max-pool-size:4}") int defaultMaxPoolSize,
                           @Value("${ingestion.connection.test-timeout-seconds:30}") int timeoutSeconds) {
        this.secrets = secrets;
        this.defaultMaxPoolSize = defaultMaxPoolSize;
        this.connectionTimeoutMs = timeoutSeconds * 1000;
    }

    /**
     * The pooled {@link DataSource} for a stored JDBC connection row (from
     * {@link DataSourceRepository}). Builds the pool on first use.
     */
    public DataSource forConnection(Map<String, Object> row) {
        String key = TenantContextHolder.requireTenantId() + ":" + row.get("id");
        return pools.computeIfAbsent(key, k -> build(row));
    }

    /** Close and forget the pool for a data source — call on update/delete. */
    public void evict(String dataSourceId) {
        String key = TenantContextHolder.requireTenantId() + ":" + dataSourceId;
        HikariDataSource pool = pools.remove(key);
        if (pool != null) {
            pool.close();
            log.info("Closed connection pool {}", key);
        }
    }

    private HikariDataSource build(Map<String, Object> row) {
        String id = String.valueOf(row.get("id"));
        String driverClass = str(row, "driverClass");
        String credentialRef = str(row, "credentialRef");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(str(row, "jdbcUrl"));
        if (!driverClass.isBlank()) cfg.setDriverClassName(driverClass);
        String username = str(row, "username");
        if (!username.isBlank()) cfg.setUsername(username);
        if (!credentialRef.isBlank()) {
            secrets.resolve(credentialRef).ifPresent(cfg::setPassword);
        }
        Object maxPool = row.get("maxPoolSize");
        int size = (maxPool instanceof Number n && n.intValue() > 0) ? n.intValue() : defaultMaxPoolSize;
        cfg.setMaximumPoolSize(size);
        cfg.setMinimumIdle(0);                       // idle enterprise-DB sessions are rude — release them
        cfg.setConnectionTimeout(connectionTimeoutMs);
        cfg.setInitializationFailTimeout(-1);        // build lazily; first borrow surfaces errors
        cfg.setPoolName("ingest-" + id);
        log.info("Creating connection pool ingest-{} (maxPoolSize={})", id, size);
        return new HikariDataSource(cfg);
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }

    @Override
    public void destroy() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }
}
