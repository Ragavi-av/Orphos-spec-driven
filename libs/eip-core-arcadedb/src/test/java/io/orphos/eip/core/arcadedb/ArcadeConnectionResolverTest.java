package io.orphos.eip.core.arcadedb;

import io.orphos.eip.core.dbregistry.ConnectionRegistryLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArcadeConnectionResolverTest {

    private final ArcadeDbTemplate defaultTemplate = new ArcadeDbTemplate(new ArcadeDbProperties());

    private ArcadeConnectionResolver resolver(String connectionsFile) {
        return new ArcadeConnectionResolver(defaultTemplate, new ConnectionRegistryLoader(),
                null /* no secrets */, connectionsFile, Duration.ofMinutes(10));
    }

    @Test
    void bundledOrBlankReturnsDefaultTemplate() {
        ArcadeConnectionResolver r = resolver("/no/such/file.json");
        assertThat(r.templateFor(null)).isSameAs(defaultTemplate);
        assertThat(r.templateFor("")).isSameAs(defaultTemplate);
        assertThat(r.templateFor("arcadedb-bundled")).isSameAs(defaultTemplate);
    }

    @Test
    void missingRegistryFileFallsBackToDefault() {
        ArcadeConnectionResolver r = resolver("/no/such/file.json");
        assertThat(r.templateFor("some-external")).isSameAs(defaultTemplate);
    }

    @Test
    void buildsTemplateForCredentialFreeArcadeConnection(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("connections.json");
        Files.writeString(file, """
                { "connections": [
                    { "id": "ext", "name": "Ext", "type": "ARCADEDB",
                      "host": "db.example.com", "port": 2480, "user": "root" }
                  ], "assignments": {} }
                """);
        ArcadeConnectionResolver r = resolver(file.toString());

        ArcadeDbTemplate t = r.templateFor("ext");
        assertThat(t).isNotSameAs(defaultTemplate);
        // Second call is served from cache (same instance).
        assertThat(r.templateFor("ext")).isSameAs(t);
    }

    @Test
    void unknownOrNonArcadeConnectionFailsClosed(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("connections.json");
        Files.writeString(file, """
                { "connections": [
                    { "id": "pg", "name": "PG", "type": "POSTGRES", "host": "h", "port": 5432 }
                  ], "assignments": {} }
                """);
        ArcadeConnectionResolver r = resolver(file.toString());

        assertThatThrownBy(() -> r.templateFor("does-not-exist"))
                .isInstanceOf(ArcadeDbTemplate.ArcadeDbException.class);
        assertThatThrownBy(() -> r.templateFor("pg"))
                .isInstanceOf(ArcadeDbTemplate.ArcadeDbException.class);
    }

    @Test
    void missingCredentialForRefFailsClosedWhenNoSecrets(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("connections.json");
        Files.writeString(file, """
                { "connections": [
                    { "id": "ext", "type": "ARCADEDB", "host": "h", "port": 2480,
                      "user": "root", "credentialRef": "eip/x#password" }
                  ], "assignments": {} }
                """);
        ArcadeConnectionResolver r = resolver(file.toString());

        // credentialRef present but no SecretsClient wired → fail closed.
        assertThatThrownBy(() -> r.templateFor("ext"))
                .isInstanceOf(ArcadeDbTemplate.ArcadeDbException.class);
    }
}
