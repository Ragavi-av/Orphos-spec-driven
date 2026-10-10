package io.orphos.eip.dataingestion.component;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.orphos.eip.common.events.EipEventPublisher;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.dataingestion.dao.DataSourcePools;
import io.orphos.eip.dataingestion.repository.DataSourceRepository;
import io.orphos.eip.dataingestion.dao.JdbcSourceExtractor;
import io.orphos.eip.dataingestion.repository.JobRepository;
import io.orphos.eip.dataingestion.repository.JobRunRepository;
import io.orphos.eip.dataingestion.repository.MappingRepository;
import io.orphos.eip.dataingestion.repository.TargetWriter;
import io.orphos.eip.dataingestion.vo.MappingDtos.ColumnMapping;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Requirement 4/5/6 acceptance checks for the run loop, driven synchronously
 * against an in-memory H2 source (no network listeners) with the target side
 * mocked — the engine's contract with {@link TargetWriter} is what's asserted.
 */
class JobExecutionEngineTest {

    private static final String JDBC_URL = "jdbc:h2:mem:engineTest;DB_CLOSE_DELAY=-1";

    private final JobRepository jobs = mock(JobRepository.class);
    private final JobRunRepository runs = mock(JobRunRepository.class);
    private final MappingRepository mappings = mock(MappingRepository.class);
    private final DataSourceRepository sources = mock(DataSourceRepository.class);
    private final DataSourcePools pools = mock(DataSourcePools.class);
    private final TargetWriter writer = mock(TargetWriter.class);
    private final EipEventPublisher events = mock(EipEventPublisher.class);

    private final UUID tenantId = UUID.randomUUID();
    private JobExecutionEngine engine;

    @BeforeEach
    void setUp() throws SQLException {
        TenantContextHolder.set(new TenantContext(tenantId, null, false));
        JdbcSourceExtractor jdbcExtractor = new JdbcSourceExtractor(pools, /*fetch*/ 100);
        GraphTargetWriter graphWriter = mock(GraphTargetWriter.class);
        engine = new JobExecutionEngine(jobs, runs, mappings, sources, List.of(jdbcExtractor), writer,
                graphWriter, events, new SimpleMeterRegistry(), 1, /*batch*/ 2, /*retries*/ 3, /*backoff*/ 30);

        JdbcDataSource h2 = new JdbcDataSource();
        h2.setURL(JDBC_URL);
        try (Connection c = h2.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS people");
            s.execute("CREATE TABLE people (id INT, first_name VARCHAR(50), last_name VARCHAR(50), " +
                      "code VARCHAR(20), seq INT)");
            s.execute("INSERT INTO people VALUES " +
                      "(1, 'Ada',    'Lovelace', '1',    1), " +
                      "(2, 'Alan',   'Turing',   'oops', 2), " +
                      "(3, 'Grace',  'Hopper',   '3',    5), " +
                      "(4, 'Edsger', 'Dijkstra', NULL,   6)");
        }
        when(pools.forConnection(any())).thenReturn(h2);
        when(sources.findById("ds-1")).thenReturn(Map.of("id", "ds-1", "kind", "GENERIC_JDBC"));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    // --- req 6.1/6.3/6.4 — happy path: transforms applied, rows land in the writer

    @Test
    void happyPathAppliesTransformsAndUpserts() {
        stubMapping(mappingView("custId", "", List.of(
                new ColumnMapping("custId", List.of("id"), "INTEGER", null),
                new ColumnMapping("fullName", List.of("first_name", "last_name"), "STRING", "CONCAT: "))));

        engine.execute("run-1", jobRow("FULL", "UPSERT"), 0);

        verify(writer).ensureType("Person", "custId");
        verify(writer).deleteAll("Person");                                      // req 6.3

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(writer, org.mockito.Mockito.times(4))
                .upsertRow(eq("Person"), eq("custId"), any(), values.capture());
        Map<String, Object> first = values.getAllValues().get(0);
        assertThat(first.get("custId")).isEqualTo(1L);                           // INTEGER conversion (req 6.1)
        assertThat(first.get("fullName")).isEqualTo("Ada Lovelace");             // many-to-one CONCAT (req 6.1)
        assertThat(first.get("tenantId")).isEqualTo(tenantId.toString());

        verify(runs).finish(eq("run-1"), eq("SUCCEEDED"), eq(4L), eq(4L), eq(0L),
                eq(4L), eq(100), eq(""));
        verify(runs).updateProgress(eq("run-1"), eq(2L), anyLong(), anyLong(), eq(4L), anyInt()); // req 5.4/6.7
    }

    // --- req 6.2 — incremental extraction filters on the watermark and persists the new max

    @Test
    void incrementalRunFiltersOnWatermarkAndPersistsMax() {
        stubMapping(mappingView("custId", "seq", List.of(
                new ColumnMapping("custId", List.of("id"), "INTEGER", null))));

        Map<String, Object> job = jobRow("INCREMENTAL", "UPSERT");
        job.put("watermark", "2");

        engine.execute("run-2", job, 0);

        // Only seq > 2 (rows 3 and 4) pass the source-side comparison.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(writer, org.mockito.Mockito.times(2))
                .upsertRow(eq("Person"), eq("custId"), any(), values.capture());
        assertThat(values.getAllValues()).extracting(v -> v.get("custId")).containsExactly(3L, 4L);

        verify(writer, never()).deleteAll(anyString());          // INCREMENTAL never wipes the target
        verify(jobs).updateWatermark("job-1", "6");               // max seq seen, kept raw as string
        verify(runs).finish(eq("run-2"), eq("SUCCEEDED"), eq(2L), eq(2L), eq(0L),
                eq(2L), eq(100), eq(""));
    }

    // --- req 5.5 — cancellation flag is honoured mid-loop

    @Test
    void cancellationMidRunStopsTheLoopAndMarksCancelled() {
        stubMapping(mappingView("custId", "", List.of(
                new ColumnMapping("custId", List.of("id"), "INTEGER", null))));
        doAnswer(inv -> {
            engine.cancel("run-3"); // user cancels after the first row is written
            return null;
        }).when(writer).upsertRow(anyString(), anyString(), any(), any());

        engine.execute("run-3", jobRow("FULL", "UPSERT"), 0);

        verify(writer, org.mockito.Mockito.times(1)).upsertRow(anyString(), anyString(), any(), any());
        verify(runs).finish(eq("run-3"), eq("CANCELLED"), eq(1L), eq(1L), eq(0L),
                eq(4L), anyInt(), eq(""));
        verify(jobs, never()).findDependents(anyString());        // cancelled runs trigger nothing
    }

    // --- req 6.5/6.6 — bad upsert keys are counted failed and skipped; the run continues

    @Test
    void badUpsertKeysAreCountedFailedAndSkipped() {
        // Key comes from the VARCHAR 'code' column typed INTEGER: row 2 ('oops')
        // fails conversion, row 4 (NULL) is a blank key — both skipped (req 6.5).
        stubMapping(mappingView("custId", "", List.of(
                new ColumnMapping("custId", List.of("code"), "INTEGER", null),
                new ColumnMapping("fullName", List.of("first_name"), "STRING", null))));

        engine.execute("run-4", jobRow("FULL", "UPSERT"), 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> values = ArgumentCaptor.forClass(Map.class);
        verify(writer, org.mockito.Mockito.times(2))
                .upsertRow(eq("Person"), eq("custId"), any(), values.capture());
        assertThat(values.getAllValues()).extracting(v -> v.get("custId")).containsExactly(1L, 3L);

        // The run still SUCCEEDS — data-quality failures don't abort processing.
        verify(runs).finish(eq("run-4"), eq("SUCCEEDED"), eq(4L), eq(2L), eq(2L),
                eq(4L), eq(100), eq(""));
        verify(runs, never()).insert(anyString(), anyString(), anyString(), anyInt(), anyString(), anyString());
    }

    // --- req 4.6 — a failed attempt below the ceiling queues an exponential-backoff retry

    @Test
    void failureBelowRetryCeilingQueuesBackoffRetry() {
        when(mappings.findById("map-1")).thenReturn(null); // mapping vanished → run fails

        Instant before = Instant.now();
        engine.execute("run-5", jobRow("FULL", "UPSERT"), 0);

        ArgumentCaptor<String> nextRetryAt = ArgumentCaptor.forClass(String.class);
        verify(runs).insert(anyString(), eq("job-1"), eq("People sync"), eq(1),
                eq("RETRY"), nextRetryAt.capture());
        Instant retryAt = Instant.parse(nextRetryAt.getValue());
        // 30s * 2^0 backoff, with slack for test execution time.
        assertThat(retryAt).isAfterOrEqualTo(before.plusSeconds(29));
        assertThat(retryAt).isBefore(before.plusSeconds(45));

        verify(runs).finish(eq("run-5"), eq("FAILED"), anyLong(), anyLong(), anyLong(),
                anyLong(), anyInt(), anyString());
        verify(events, never()).publish(anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    // --- req 4.7 — after the final retry fails, the platform event is published

    @Test
    void finalRetryFailurePublishesEvent() {
        when(mappings.findById("map-1")).thenReturn(null);

        engine.execute("run-6", jobRow("FULL", "UPSERT"), 3); // attempt == maxRetries

        verify(runs, never()).insert(anyString(), anyString(), anyString(), anyInt(),
                anyString(), anyString());
        verify(events).publish(eq("DATA_INGEST_FAILED"), eq("ERROR"), eq("People sync"),
                anyString(), eq(tenantId.toString()), any());
    }

    // --- helpers -------------------------------------------------------------

    private void stubMapping(MappingView view) {
        Map<String, Object> row = Map.of("id", "map-1");
        when(mappings.findById("map-1")).thenReturn(row);
        when(mappings.toView(row)).thenReturn(view);
    }

    private static MappingView mappingView(String keyTarget, String incrementalColumn,
                                           List<ColumnMapping> columnMappings) {
        return new MappingView("map-1", "People", "ds-1", "", "people", "Person",
                "DOCUMENT", keyTarget, incrementalColumn, columnMappings, List.of(), true, List.of(), "", "");
    }

    private static Map<String, Object> jobRow(String loadMode, String mergeStrategy) {
        Map<String, Object> job = new HashMap<>();
        job.put("id", "job-1");
        job.put("name", "People sync");
        job.put("mappingId", "map-1");
        job.put("loadMode", loadMode);
        job.put("mergeStrategy", mergeStrategy);
        job.put("batchSize", 2);
        job.put("maxRetries", 3);
        job.put("watermark", "");
        return job;
    }
}
