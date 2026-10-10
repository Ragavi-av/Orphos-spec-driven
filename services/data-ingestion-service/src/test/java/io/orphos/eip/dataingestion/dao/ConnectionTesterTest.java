package io.orphos.eip.dataingestion.dao;

import io.orphos.eip.dataingestion.vo.DataSourceDtos.SourceKind;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.TestResult;
import io.orphos.eip.dataingestion.component.ErpTokenProvider;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 1 acceptance checks for JDBC sources — run against in-memory H2,
 * no external infrastructure or network listeners needed.
 * REST-source checks live in {@link RestConnectionTesterTest}.
 */
class ConnectionTesterTest {

    private final ConnectionTester tester = new ConnectionTester(10, new ErpTokenProvider());

    @Test
    void jdbcTestSucceedsAndReportsProduct() {
        TestResult r = tester.test(SourceKind.GENERIC_JDBC,
                "jdbc:h2:mem:conntest", "org.h2.Driver", "sa", null, null, null, null, "", null);
        assertThat(r.ok()).isTrue();
        assertThat(r.detail()).contains("H2");
        assertThat(r.latencyMs()).isLessThan(10_000);   // requirement 1.5: bounded answer
    }

    @Test
    void jdbcTestFailsForUnknownDriverUrl() {
        TestResult r = tester.test(SourceKind.POSTGRESQL,
                "jdbc:nosuchdb://nowhere/none", null, "u", null, null, null, null, "p", null);
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).isNotBlank();
    }

    @Test
    void genericJdbcReportsMissingDriverClassClearly() {
        TestResult r = tester.test(SourceKind.GENERIC_JDBC,
                "jdbc:foo:bar", "com.example.MissingDriver", null, null, null, null, null, null, null);
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("driver class not found");
        assertThat(r.message()).contains("/app/drivers");
    }

    @Test
    void jdbcUrlIsRequired() {
        TestResult r = tester.test(SourceKind.POSTGRESQL,
                " ", null, null, null, null, null, null, null, null);
        assertThat(r.ok()).isFalse();
        assertThat(r.message()).contains("jdbcUrl is required");
    }
}
