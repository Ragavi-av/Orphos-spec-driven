package io.orphos.eip.dataingestion.service;

import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.dataingestion.component.RestMetadataDiscoverer;
import io.orphos.eip.dataingestion.dao.DataSourcePools;
import io.orphos.eip.dataingestion.service.DataSourceService;
import io.orphos.eip.dataingestion.vo.MetadataDtos.ColumnInfo;
import io.orphos.eip.dataingestion.vo.MetadataDtos.SchemaSnapshot;
import io.orphos.eip.dataingestion.vo.MetadataDtos.TableInfo;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Requirement 2 acceptance checks against an in-memory H2 schema with primary
 * keys, foreign keys, indexes, and data — no external infrastructure.
 */
class MetadataDiscoveryServiceTest {

    private static final String URL = "jdbc:h2:mem:mdtest;DB_CLOSE_DELAY=-1";
    private Connection keepAlive;
    private MetadataDiscoveryService service;

    @BeforeEach
    void setUp() throws Exception {
        TenantContextHolder.set(new TenantContext(UUID.randomUUID(), null, false));

        keepAlive = DriverManager.getConnection(URL, "sa", "");
        try (Statement st = keepAlive.createStatement()) {
            st.execute("DROP ALL OBJECTS");
            st.execute("CREATE TABLE customers (id INT PRIMARY KEY, email VARCHAR(200) NOT NULL)");
            st.execute("CREATE TABLE orders (id INT PRIMARY KEY, customer_id INT NOT NULL, " +
                       "total DECIMAL(10,2), FOREIGN KEY (customer_id) REFERENCES customers(id))");
            st.execute("CREATE UNIQUE INDEX idx_customers_email ON customers(email)");
            st.execute("INSERT INTO customers VALUES (1,'a@x.io'), (2,'b@x.io'), (3,'c@x.io')");
            st.execute("INSERT INTO orders VALUES (10, 1, 12.50), (11, 2, 99.00)");
        }

        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL(URL);
        h2.setUser("sa");

        Map<String, Object> row = Map.of(
                "id", "ds-test", "name", "H2 Test", "kind", "GENERIC_JDBC",
                "jdbcUrl", URL, "driverClass", "org.h2.Driver",
                "username", "sa", "credentialRef", "");
        DataSourceService dataSources = mock(DataSourceService.class);
        when(dataSources.require(anyString())).thenReturn(row);
        DataSourcePools pools = mock(DataSourcePools.class);
        when(pools.forConnection(any())).thenReturn(h2);

        service = new MetadataDiscoveryService(dataSources, pools, mock(RestMetadataDiscoverer.class), 24, 200);
    }

    @AfterEach
    void tearDown() throws Exception {
        keepAlive.close();
        TenantContextHolder.clear();
    }

    @Test
    void discoversTablesColumnsKeysIndexesAndRowCounts() {
        SchemaSnapshot snap = service.getSnapshot("ds-test", false);

        assertThat(snap.fromCache()).isFalse();
        assertThat(snap.databaseProduct()).contains("H2");
        assertThat(snap.tables()).extracting(TableInfo::name)
                .contains("CUSTOMERS", "ORDERS");

        TableInfo customers = byName(snap, "CUSTOMERS");
        // Columns + types (req 2.2)
        assertThat(customers.columns()).extracting(ColumnInfo::name)
                .containsExactlyInAnyOrder("ID", "EMAIL");
        // Primary key flag (req 2.3)
        assertThat(customers.columns()).filteredOn(ColumnInfo::primaryKey)
                .extracting(ColumnInfo::name).containsExactly("ID");
        // Index discovery (req 2.3)
        assertThat(customers.indexes())
                .anySatisfy(idx -> {
                    assertThat(idx.unique()).isTrue();
                    assertThat(idx.columns()).contains("EMAIL");
                });
        // Row counts (req 2.4)
        assertThat(customers.rowCount()).isEqualTo(3);
        assertThat(byName(snap, "ORDERS").rowCount()).isEqualTo(2);

        // Foreign keys (req 2.3)
        TableInfo orders = byName(snap, "ORDERS");
        assertThat(orders.foreignKeys())
                .anySatisfy(fk -> {
                    assertThat(fk.column()).isEqualTo("CUSTOMER_ID");
                    assertThat(fk.refTable()).isEqualTo("CUSTOMERS");
                    assertThat(fk.refColumn()).isEqualTo("ID");
                });
    }

    @Test
    void secondCallServesFromCache() {
        SchemaSnapshot first = service.getSnapshot("ds-test", false);
        assertThat(first.fromCache()).isFalse();

        SchemaSnapshot second = service.getSnapshot("ds-test", false);
        assertThat(second.fromCache()).isTrue();                      // req 2.5
        assertThat(second.capturedAt()).isEqualTo(first.capturedAt());

        SchemaSnapshot forced = service.getSnapshot("ds-test", true); // ?refresh=true bypass
        assertThat(forced.fromCache()).isFalse();
    }

    @Test
    void incrementalRefreshUpdatesOneTableInPlace() throws Exception {
        SchemaSnapshot before = service.getSnapshot("ds-test", false);
        assertThat(byName(before, "CUSTOMERS").rowCount()).isEqualTo(3);

        try (Statement st = keepAlive.createStatement()) {
            st.execute("INSERT INTO customers VALUES (4,'d@x.io')");
        }

        SchemaSnapshot after = service.refreshTable("ds-test", "CUSTOMERS"); // req 2.7
        assertThat(byName(after, "CUSTOMERS").rowCount()).isEqualTo(4);
        // The other table's metadata was spliced from cache, not re-read.
        assertThat(byName(after, "ORDERS").rowCount()).isEqualTo(2);
    }

    private static TableInfo byName(SchemaSnapshot snap, String name) {
        return snap.tables().stream().filter(t -> t.name().equals(name)).findFirst().orElseThrow();
    }
}
