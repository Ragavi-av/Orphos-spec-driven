package io.orphos.eip.core.dbregistry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionRegistryEnvironmentPostProcessorTest {

    private final ConnectionRegistryEnvironmentPostProcessor epp =
            new ConnectionRegistryEnvironmentPostProcessor();

    private StandardEnvironment baseEnv(String serviceName, String connectionsFile) {
        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("spring.application.name", serviceName);
        // The binding the service ships with today.
        base.put("secrets.bindings[0]", "ARCADEDB_PASSWORD=eip/arcadedb#password");
        base.put("secrets.bindings[1]", "EIP_SECURITY_INTERNAL_SERVICE_TOKEN=eip/internal-service#token");
        // Mirrors the yaml default so we can prove the registry overrides it.
        base.put("arcadedb.url", "http://eip-arcadedb:2480");
        base.put("arcadedb.database", "orphos");
        if (connectionsFile != null) {
            base.put("eip.connections.file", connectionsFile);
        }
        env.getPropertySources().addLast(new MapPropertySource("test-base", base));
        return env;
    }

    @Test
    void repointsAssignedServiceAndRewritesPasswordBinding(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("connections.json");
        Files.writeString(file, """
                {
                  "connections": [
                    {
                      "id": "arcadedb-external",
                      "name": "External ArcadeDB",
                      "type": "ARCADEDB",
                      "host": "db.example.com",
                      "port": 2480,
                      "user": "eipuser",
                      "credentialRef": "eip/db-connections/arcadedb-external#password"
                    }
                  ],
                  "assignments": {
                    "platform-core-service": {
                      "connectionId": "arcadedb-external",
                      "database": "eip_external",
                      "schemaMode": "EXISTING"
                    }
                  }
                }
                """);

        StandardEnvironment env = baseEnv("platform-core-service", file.toString());
        epp.postProcessEnvironment(env, null);

        assertThat(env.getProperty("arcadedb.url")).isEqualTo("http://db.example.com:2480");
        assertThat(env.getProperty("arcadedb.user")).isEqualTo("eipuser");
        assertThat(env.getProperty("arcadedb.database")).isEqualTo("eip_external");

        List<String> bindings = Binder.get(env)
                .bind("secrets.bindings", Bindable.listOf(String.class)).orElse(List.of());
        assertThat(bindings)
                .contains("ARCADEDB_PASSWORD=eip/db-connections/arcadedb-external#password")
                .contains("EIP_SECURITY_INTERNAL_SERVICE_TOKEN=eip/internal-service#token")
                .doesNotContain("ARCADEDB_PASSWORD=eip/arcadedb#password");
    }

    @Test
    void noFileLeavesEnvironmentUntouched() {
        StandardEnvironment env = baseEnv("platform-core-service", "/no/such/connections.json");
        epp.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(
                ConnectionRegistryEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
        assertThat(env.getProperty("arcadedb.url")).isEqualTo("http://eip-arcadedb:2480");
    }

    @Test
    void serviceWithNoAssignmentIsUntouched(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("connections.json");
        Files.writeString(file, """
                {
                  "connections": [
                    {"id": "c1", "type": "ARCADEDB", "host": "db.example.com", "credentialRef": "eip/x#password"}
                  ],
                  "assignments": {
                    "some-other-service": {"connectionId": "c1", "database": "d", "schemaMode": "DEFAULT"}
                  }
                }
                """);

        StandardEnvironment env = baseEnv("platform-core-service", file.toString());
        epp.postProcessEnvironment(env, null);

        assertThat(env.getPropertySources().contains(
                ConnectionRegistryEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)).isFalse();
        assertThat(env.getProperty("arcadedb.url")).isEqualTo("http://eip-arcadedb:2480");
    }
}
