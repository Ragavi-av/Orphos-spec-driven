package io.orphos.eip.admin.testsupport;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * Reusable Testcontainers fixture for ArcadeDB. There's no first-party
 * Testcontainers module for ArcadeDB, so we wrap {@link GenericContainer}
 * directly. Pinning the image tag — never use {@code latest} in tests
 * since CI then runs against whichever version Docker Hub serves that day.
 *
 * <p>The root password is exposed via {@link #getRootPassword()} so
 * Spring tests can wire it into {@code arcadedb.password}; tests typically
 * spin one container up per test class and register the URL/password as
 * dynamic properties.</p>
 */
public class ArcadeDbContainer extends GenericContainer<ArcadeDbContainer> {

    private static final DockerImageName DEFAULT_IMAGE =
            DockerImageName.parse("arcadedata/arcadedb:25.4.1");

    public static final int HTTP_PORT = 2480;
    public static final int BINARY_PORT = 2424;

    private final String rootPassword;

    public ArcadeDbContainer() {
        this(DEFAULT_IMAGE, "test-root-pw");
    }

    public ArcadeDbContainer(DockerImageName image, String rootPassword) {
        super(image);
        this.rootPassword = rootPassword;
        withExposedPorts(HTTP_PORT, BINARY_PORT);
        withEnv(Map.of(
                "JAVA_OPTS",
                "-Xms256m -Xmx512m"
                        + " -Darcadedb.server.rootPassword=" + rootPassword
                        + " -Darcadedb.server.defaultDatabases="
        ));
        // Same probe as the prod compose definition — hits /api/v1/server
        // with a root Basic-auth header so the wait actually validates that
        // the server is serving requests, not just listening on the port.
        String basic = Base64.getEncoder()
                .encodeToString(("root:" + rootPassword).getBytes());
        waitingFor(
                Wait.forHttp("/api/v1/server")
                        .forPort(HTTP_PORT)
                        .withHeader("Authorization", "Basic " + basic)
                        .forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(90))
        );
    }

    public String getHttpUrl() {
        return "http://" + getHost() + ":" + getMappedPort(HTTP_PORT);
    }

    public String getRootPassword() {
        return rootPassword;
    }
}
