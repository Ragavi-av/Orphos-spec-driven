package io.orphos.eip.setup.service;

import io.orphos.eip.setup.dto.SetupDtos.Secrets;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates strong random credentials for the one-time secrets. The wizard
 * offers these as defaults so an operator can accept them wholesale; each is
 * URL/JSON-safe so it survives {@code .env}, Infisical, and the JDBC/Keycloak
 * consumers without escaping surprises.
 */
@Service
public class SecretGenerator {

    private final SecureRandom random = new SecureRandom();

    /** A url-safe token with no padding, roughly {@code bytes * 1.33} chars long. */
    public String token(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }

    /** Lowercase hex of {@code bytes} bytes (used where a hex key is expected). */
    public String hex(int bytes) {
        byte[] buf = new byte[bytes];
        random.nextBytes(buf);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /** A fresh, fully-populated secret set (used for the "regenerate" action). */
    public Secrets freshSecrets() {
        return new Secrets(
                token(18),   // keycloak admin password
                token(18),   // arcadedb password
                token(18),   // postgres password (Keycloak DB) — separate from ArcadeDB
                token(18),   // infisical DB password (its own bundled Postgres)
                token(18),   // fuseki admin password
                token(24),   // internal service token
                hex(16),     // infisical encryption key (32 hex chars, per infra default shape)
                token(32),   // infisical auth secret (base64)
                token(18));  // infisical admin password
    }

    /**
     * Returns {@code secrets} with any blank field filled by a freshly-generated
     * value — so the caller can accept operator-supplied values while still
     * guaranteeing nothing is left empty.
     */
    public Secrets fillBlanks(Secrets s) {
        Secrets gen = freshSecrets();
        if (s == null) return gen;
        return new Secrets(
                orGen(s.keycloakAdminPassword(), gen.keycloakAdminPassword()),
                orGen(s.arcadedbPassword(), gen.arcadedbPassword()),
                orGen(s.postgresPassword(), gen.postgresPassword()),
                orGen(s.infisicalDbPassword(), gen.infisicalDbPassword()),
                orGen(s.fusekiAdminPassword(), gen.fusekiAdminPassword()),
                orGen(s.internalServiceToken(), gen.internalServiceToken()),
                orGen(s.infisicalEncryptionKey(), gen.infisicalEncryptionKey()),
                orGen(s.infisicalAuthSecret(), gen.infisicalAuthSecret()),
                orGen(s.infisicalAdminPassword(), gen.infisicalAdminPassword()));
    }

    private static String orGen(String supplied, String generated) {
        return (supplied == null || supplied.isBlank()) ? generated : supplied.trim();
    }
}
