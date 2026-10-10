package io.orphos.eip.registry.component;

import io.orphos.eip.core.arcadedb.ArcadeDbProperties;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.secrets.SecretsClient;
import jakarta.annotation.PostConstruct;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Thin facade over the shared {@link ArcadeDbTemplate} that adds the registry's
 * OpenBao password fallback — everything else (HTTP transport, basic auth,
 * Jackson row extraction) lives in {@code eip-core-arcadedb}. Mirrors
 * doc-ingest-service's {@code DocArcadeClient}.
 *
 * <p>Password resolution: prefers the bootstrap-injected {@code arcadedb.password}
 * (set by {@code SecretsEnvironmentPostProcessor} from {@code eip/arcadedb#password},
 * or via an explicit {@code ARCADEDB_PASSWORD} env var). If that is blank — which
 * happens when the early bootstrap secrets read cannot reach the backend in time —
 * it falls back to reading the same secret from the runtime {@link SecretsClient}
 * bean at {@code @PostConstruct} and writes it into the shared {@link ArcadeDbProperties}
 * bean before any query runs. Either way the credential comes from the secrets store.</p>
 */
@Component
public class RegistryArcadeClient {

    private static final Logger log = LogManager.getLogger(RegistryArcadeClient.class);

    private final ArcadeDbTemplate arcade;
    private final ArcadeDbProperties props;
    private final SecretsClient openBao;

    /** OpenBao KV v2 path + field holding the ArcadeDB root password. */
    @Value("${arcadedb.openbao-secret-path:eip/arcadedb}")
    private String openBaoSecretPath;

    @Value("${arcadedb.openbao-secret-field:password}")
    private String openBaoSecretField;

    public RegistryArcadeClient(ArcadeDbTemplate arcade, ArcadeDbProperties props, SecretsClient openBao) {
        this.arcade = arcade;
        this.props = props;
        this.openBao = openBao;
    }

    /**
     * Resolve the ArcadeDB password from OpenBao at runtime when the bootstrap
     * injection left it blank. The runtime read happens after the network is
     * fully up, so it succeeds where the very-early EnvironmentPostProcessor read
     * may not have.
     */
    @PostConstruct
    void resolvePassword() {
        if (props.getPassword() != null && !props.getPassword().isBlank()) {
            log.info("ArcadeDB password resolved from bootstrap injection.");
            return;
        }
        try {
            openBao.readField(openBaoSecretPath, openBaoSecretField).ifPresentOrElse(
                pw -> {
                    props.setPassword(pw);
                    log.info("ArcadeDB password resolved from OpenBao at runtime ({}#{}).",
                        openBaoSecretPath, openBaoSecretField);
                },
                () -> log.error("ArcadeDB password is blank and OpenBao returned no value at {}#{} — "
                    + "ArcadeDB calls will fail with 403.", openBaoSecretPath, openBaoSecretField));
        } catch (Exception e) {
            log.error("Failed to read ArcadeDB password from OpenBao at {}#{}: {}",
                openBaoSecretPath, openBaoSecretField, e.getMessage());
        }
    }

    public List<Map<String, Object>> query(String sql, Map<String, Object> params) {
        return arcade.query(sql, params);
    }

    public List<Map<String, Object>> command(String sql, Map<String, Object> params) {
        return arcade.command(sql, params);
    }

    public void commandNoResult(String sql, Map<String, Object> params) {
        arcade.commandNoResult(sql, params);
    }
}
