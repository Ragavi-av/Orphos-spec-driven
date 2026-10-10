package io.orphos.eip.dataingestion.service;

import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.dataingestion.component.SensitiveDataMasker;
import io.orphos.eip.dataingestion.dao.DataSourcePools;
import io.orphos.eip.dataingestion.service.DataSourceService;
import io.orphos.eip.dataingestion.service.MetadataDiscoveryService;
import io.orphos.eip.dataingestion.vo.MetadataDtos.SchemaSnapshot;
import io.orphos.eip.dataingestion.vo.MetadataDtos.TableInfo;
import io.orphos.eip.dataingestion.vo.PreviewDtos.PreviewColumn;
import io.orphos.eip.dataingestion.vo.PreviewDtos.PreviewResponse;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Requirement 8.5/8.6 acceptance checks against an in-memory H2 source — no
 * external infrastructure, no network listeners. Masking, RESTRICTED
 * enforcement, and the metadata-snapshot identifier validation (injection
 * guard) are exercised against a real JDBC read.
 */
class DataPreviewServiceTest {

    private static final String URL = "jdbc:h2:mem:previewtest;DB_CLOSE_DELAY=-1";

    private Connection keepAlive;
    private Map<String, Object> row;
    private DataPreviewService service;

    @BeforeEach
    void setUp() throws Exception {
        TenantContextHolder.set(new TenantContext(UUID.randomUUID(), null, false));

        keepAlive = DriverManager.getConnection(URL, "sa", "");
        try (Statement st = keepAlive.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE TABLE employees (id INT PRIMARY KEY, full_name VARCHAR(100), " +
                       "email VARCHAR(200), salary DECIMAL(10,2), city VARCHAR(100))");
            st.execute("INSERT INTO employees VALUES " +
                       "(1, 'Alice Adams', 'alice@example.com', 85000.00, 'Berlin'), " +
                       "(2, 'Bob Brown', 'bob@example.com', 72000.00, 'Munich'), " +
                       "(3, 'Cara Cole', NULL, NULL, NULL)");
        }

        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL(URL);
        h2.setUser("sa");

        row = new HashMap<>(Map.of(
                "id", "ds-test", "name", "H2 Test", "kind", "GENERIC_JDBC",
                "jdbcUrl", URL, "driverClass", "org.h2.Driver",
                "username", "sa", "credentialRef", ""));

        DataSourceService dataSources = mock(DataSourceService.class);
        when(dataSources.require(anyString())).thenReturn(row);
        DataSourcePools pools = mock(DataSourcePools.class);
        when(pools.forConnection(any())).thenReturn(h2);

        // Snapshot knows exactly one table — everything else must 404.
        MetadataDiscoveryService metadata = mock(MetadataDiscoveryService.class);
        when(metadata.getSnapshot(anyString(), anyBoolean())).thenReturn(new SchemaSnapshot(
                "ds-test", Instant.now().toString(), false, "H2",
                List.of(new TableInfo("PUBLIC", "EMPLOYEES", "TABLE", 3,
                        List.of(), List.of(), List.of())),
                List.of()));

        SensitiveDataMasker masker = new SensitiveDataMasker(List.of(
                "password", "secret", "token", "ssn", "credit", "card", "iban",
                "salary", "dob", "birth", "email", "phone"));
        service = new DataPreviewService(dataSources, pools, metadata, masker);
    }

    @AfterEach
    void tearDown() throws Exception {
        keepAlive.close();
        TenantContextHolder.clear();
    }

    @Test
    void masksSensitiveColumnsAndPassesOthersThrough() {
        PreviewResponse resp = service.preview("ds-test", null, "employees", 10);

        assertThat(resp.table()).isEqualTo("PUBLIC.EMPLOYEES");
        assertThat(resp.rowLimit()).isEqualTo(10);
        assertThat(resp.columns()).extracting(PreviewColumn::name)
                .containsExactly("ID", "FULL_NAME", "EMAIL", "SALARY", "CITY");
        assertThat(resp.columns()).extracting(PreviewColumn::masked)
                .containsExactly(false, false, true, true, false);

        List<String> alice = resp.rows().get(0);
        assertThat(alice.get(0)).isEqualTo("1");            // passthrough
        assertThat(alice.get(1)).isEqualTo("Alice Adams");  // passthrough
        assertThat(alice.get(2)).isEqualTo("a***@***");     // email mask
        assertThat(alice.get(3)).isEqualTo("8***");         // generic mask
        assertThat(alice.get(4)).isEqualTo("Berlin");       // passthrough

        // NULLs stay NULL — even in masked columns.
        List<String> cara = resp.rows().get(2);
        assertThat(cara.get(2)).isNull();
        assertThat(cara.get(3)).isNull();
    }

    @Test
    void restrictedClassificationMasksAllColumns() {
        row.put("classification", "RESTRICTED");
        row.put("classificationTags", List.of("pii", "hr"));

        PreviewResponse resp = service.preview("ds-test", "public", "EMPLOYEES", 10);

        assertThat(resp.classification()).isEqualTo("RESTRICTED");
        assertThat(resp.classificationTags()).containsExactly("pii", "hr");
        assertThat(resp.columns()).allSatisfy(c -> assertThat(c.masked()).isTrue());
        List<String> alice = resp.rows().get(0);
        assertThat(alice.get(0)).isEqualTo("1***");         // even the id
        assertThat(alice.get(1)).isEqualTo("A***");
        assertThat(alice.get(4)).isEqualTo("B***");
    }

    @Test
    void unknownTableIs404() {
        assertThatThrownBy(() -> service.preview("ds-test", null, "no_such_table", 10))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void sqlInjectionAttemptsNeverReachTheSource() {
        // Not in the metadata snapshot → rejected before any SQL is built.
        for (String evil : List.of(
                "EMPLOYEES; DROP TABLE EMPLOYEES",
                "EMPLOYEES\" UNION SELECT * FROM INFORMATION_SCHEMA.USERS --",
                "EMPLOYEES WHERE 1=1")) {
            assertThatThrownBy(() -> service.preview("ds-test", null, evil, 10))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
        // Schema is validated the same way.
        assertThatThrownBy(() -> service.preview("ds-test", "PUBLIC\"; --", "EMPLOYEES", 10))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void limitIsClampedToTheCeiling() {
        PreviewResponse resp = service.preview("ds-test", null, "employees", 5000);
        assertThat(resp.rowLimit()).isEqualTo(DataPreviewService.MAX_ROWS);
        assertThat(resp.rows()).hasSize(3); // all the data there is

        PreviewResponse small = service.preview("ds-test", null, "employees", 2);
        assertThat(small.rowLimit()).isEqualTo(2);
        assertThat(small.rows()).hasSize(2);

        PreviewResponse defaulted = service.preview("ds-test", null, "employees", 0);
        assertThat(defaulted.rowLimit()).isEqualTo(DataPreviewService.DEFAULT_ROWS);
    }

    @Test
    void restSourcesAreRejected() {
        row.put("kind", "REST");
        assertThatThrownBy(() -> service.preview("ds-test", null, "employees", 10))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
