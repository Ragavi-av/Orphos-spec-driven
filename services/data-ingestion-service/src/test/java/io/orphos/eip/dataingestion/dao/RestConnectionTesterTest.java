package io.orphos.eip.dataingestion.dao;

import com.sun.net.httpserver.HttpServer;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.RestAuthType;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.SourceKind;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.TestResult;
import io.orphos.eip.dataingestion.component.ErpTokenProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 1 acceptance checks for REST sources, against a local JDK
 * HttpServer. Some Windows hosts cannot create NIO selector pipes (JDK
 * loopback/AF_UNIX quirk) — the whole class is skipped there via an
 * assumption; it always runs on Linux/CI (and the service itself ships in a
 * Linux container).
 */
class RestConnectionTesterTest {

    private static final ConnectionTester tester = new ConnectionTester(10, new ErpTokenProvider());
    private static HttpServer http;
    private static String httpBase;

    @BeforeAll
    static void startHttp() {
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (Exception e) {
            Assumptions.abort("Host cannot open NIO loopback listeners: " + e.getMessage());
        }
        http.createContext("/ok", ex -> {
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        http.createContext("/secured", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            ex.sendResponseHeaders("Bearer sesame".equals(auth) ? 200 : 401, -1);
            ex.close();
        });
        http.start();
        httpBase = "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @AfterAll
    static void stopHttp() {
        if (http != null) http.stop(0);
    }

    @Test
    void restTestSucceedsAgainstReachableEndpoint() {
        TestResult r = tester.test(SourceKind.REST,
                null, null, null, httpBase, RestAuthType.NONE, null, "/ok", null, null);
        assertThat(r.ok()).isTrue();
        assertThat(r.detail()).isEqualTo("HTTP 200");
    }

    @Test
    void restTestSendsBearerCredential() {
        TestResult ok = tester.test(SourceKind.REST,
                null, null, null, httpBase, RestAuthType.BEARER, null, "/secured", "sesame", null);
        assertThat(ok.ok()).isTrue();

        TestResult unauthorized = tester.test(SourceKind.REST,
                null, null, null, httpBase, RestAuthType.BEARER, null, "/secured", "wrong", null);
        assertThat(unauthorized.ok()).isFalse();
        assertThat(unauthorized.message()).contains("401").contains("check credentials");
    }

    @Test
    void restTestFailsForUnreachableHost() {
        // Reserved TEST-NET-1 address — connect fails fast or times out inside the cap.
        TestResult r = tester.test(SourceKind.REST,
                null, null, null, "http://192.0.2.1:9", RestAuthType.NONE, null, null, null, null);
        assertThat(r.ok()).isFalse();
    }

    @Test
    void restBaseUrlIsRequired() {
        TestResult r = tester.test(SourceKind.REST,
                null, null, null, "", RestAuthType.NONE, null, null, null, null);
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("baseUrl is required");
    }
}
