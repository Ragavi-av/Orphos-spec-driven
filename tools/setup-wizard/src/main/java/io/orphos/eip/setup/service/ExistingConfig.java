package io.orphos.eip.setup.service;

import io.orphos.eip.setup.dto.SetupDtos.Secrets;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Reads an existing {@code .env} in the target dir so the wizard can DETECT a
 * prior install and reuse its secrets by default. This is the drift guard: the
 * stateful backends (Keycloak, Postgres, ArcadeDB, Infisical) bake their
 * credentials into volumes on first boot, so silently generating fresh secrets
 * on a re-run would break auth (the tenant-creation 401). Reusing the existing
 * values keeps everything consistent unless the operator deliberately resets.
 */
@Service
public class ExistingConfig {

    private final Path targetDir;

    public ExistingConfig(SetupPaths paths) {
        this.targetDir = paths.targetDir();
    }

    /** True when a prior install is detected: a {@code .env} with real backend secrets. */
    public boolean hasPriorInstall() {
        Map<String, String> env = readEnv();
        return notBlank(env.get("KEYCLOAK_ADMIN_PASSWORD")) || notBlank(env.get("ARCADEDB_PASSWORD"));
    }

    /** The secrets recorded in the existing {@code .env}, or empty if there is none. */
    public Optional<Secrets> readExistingSecrets() {
        if (!hasPriorInstall()) return Optional.empty();
        Map<String, String> e = readEnv();
        // Pre-separation installs have no POSTGRES_PASSWORD — their Postgres volume
        // was initialized with ARCADEDB_PASSWORD, so fall back to it to stay matched.
        String pg = notBlank(e.get("POSTGRES_PASSWORD")) ? e.get("POSTGRES_PASSWORD") : e.get("ARCADEDB_PASSWORD");
        // Pre-existing installs with no INFISICAL_DB_PASSWORD had the infisical-db
        // volume initialized with the compose default "infisical" — reuse it.
        String infPg = notBlank(e.get("INFISICAL_DB_PASSWORD")) ? e.get("INFISICAL_DB_PASSWORD") : "infisical";
        return Optional.of(new Secrets(
                e.get("KEYCLOAK_ADMIN_PASSWORD"),
                e.get("ARCADEDB_PASSWORD"),
                pg,
                infPg,
                e.get("FUSEKI_ADMIN_PASSWORD"),
                e.get("EIP_INTERNAL_SERVICE_TOKEN"),
                e.get("INFISICAL_ENCRYPTION_KEY"),
                e.get("INFISICAL_AUTH_SECRET"),
                e.get("INFISICAL_ADMIN_PASSWORD")));
    }

    public String envPath() {
        return targetDir.resolve(".env").toString();
    }

    private Map<String, String> readEnv() {
        Map<String, String> out = new HashMap<>();
        Path env = targetDir.resolve(".env");
        if (!Files.isReadable(env)) return out;
        try {
            for (String raw : Files.readAllLines(env, StandardCharsets.UTF_8)) {
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).strip();
                String val = line.substring(eq + 1).strip();
                out.put(key, val);
            }
        } catch (Exception ignored) {
            // Unreadable/partial .env → treat as no prior install.
        }
        return out;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
