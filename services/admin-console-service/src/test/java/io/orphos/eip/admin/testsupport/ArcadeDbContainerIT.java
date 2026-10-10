package io.orphos.eip.admin.testsupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test that proves the {@link ArcadeDbContainer} fixture actually
 * boots a real ArcadeDB and serves HTTP. Other integration tests should
 * extend or copy the {@code @Container} pattern; this one is the canary.
 *
 * <p>Gated on the {@code RUN_INTEGRATION_TESTS} env var so a default
 * {@code mvn test} on a laptop without Docker doesn't start downloading
 * 150 MB of image layers. CI sets the var.</p>
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class ArcadeDbContainerIT {

    @Container
    private final ArcadeDbContainer arcade = new ArcadeDbContainer();

    @Test
    void serverEndpointRespondsToAuthenticatedRequest() throws Exception {
        String creds = Base64.getEncoder()
                .encodeToString(("root:" + arcade.getRootPassword()).getBytes());

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(arcade.getHttpUrl() + "/api/v1/server"))
                        .header("Authorization", "Basic " + creds)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"version\""),
                "expected server payload to include version, got: " + resp.body());
    }
}
