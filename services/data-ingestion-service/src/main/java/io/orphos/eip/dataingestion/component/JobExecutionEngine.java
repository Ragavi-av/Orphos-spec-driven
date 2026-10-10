package io.orphos.eip.dataingestion.component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.orphos.eip.common.events.EipEventPublisher;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.dataingestion.repository.DataSourceRepository;
import io.orphos.eip.dataingestion.repository.JobRepository;
import io.orphos.eip.dataingestion.repository.JobRunRepository;
import io.orphos.eip.dataingestion.repository.TargetWriter;
import io.orphos.eip.dataingestion.service.DataSourceService;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.SourceKind;
import io.orphos.eip.dataingestion.vo.ExtractRequest;
import io.orphos.eip.dataingestion.dao.RowCursor;
import io.orphos.eip.dataingestion.dao.SourceExtractor;
import io.orphos.eip.dataingestion.vo.JobDtos.LoadMode;
import io.orphos.eip.dataingestion.vo.JobDtos.MergeStrategy;
import io.orphos.eip.dataingestion.repository.MappingRepository;
import io.orphos.eip.dataingestion.util.TransformFunctions;
import io.orphos.eip.dataingestion.vo.MappingDtos.ColumnMapping;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;
import io.orphos.eip.dataingestion.vo.MappingDtos.TargetType;
import io.orphos.eip.dataingestion.vo.MappingDtos;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Executes extraction runs: streams rows out of the source JDBC connection,
 * applies the mapping's transforms (req 6.1) and loads them into the tenant's
 * ArcadeDB via {@link TargetWriter}.
 *
 * <p>Runs execute on a fixed thread pool ({@code ingestion.jobs.max-parallel},
 * req 9.1) so several jobs load concurrently without unbounded fan-out. The
 * source side streams — {@code setFetchSize} plus {@code autoCommit=false}
 * (PostgreSQL only opens a server-side cursor without an open transaction) —
 * so a million-row table never materialises in memory (req 9.6).</p>
 *
 * <p>Failure handling (req 4.6/4.7): a failed run enqueues a QUEUED retry row
 * with an exponential-backoff {@code nextRetryAt} (base * 2^attempt), which the
 * scheduler picks up; after the final retry fails a {@code DATA_INGEST_FAILED}
 * event is published to the platform event framework.</p>
 *
 * <p>Cancellation (req 5.5) is cooperative: an in-memory flag per active run,
 * checked at the top of the row loop.</p>
 */
@Component
public class JobExecutionEngine implements DisposableBean {

    private static final Logger log = LogManager.getLogger(JobExecutionEngine.class);

    private final JobRepository jobs;
    private final JobRunRepository runs;
    private final MappingRepository mappings;
    private final DataSourceRepository sources;
    private final List<SourceExtractor> extractors;
    private final TargetWriter writer;
    private final GraphTargetWriter graphWriter;
    private final EipEventPublisher events;
    private final MeterRegistry metrics;
    private final int defaultBatchSize;
    private final int defaultMaxRetries;
    private final long retryBackoffSeconds;

    private final ExecutorService pool;
    private final Map<String, AtomicBoolean> cancelFlags = new ConcurrentHashMap<>();

    public JobExecutionEngine(JobRepository jobs, JobRunRepository runs, MappingRepository mappings,
                              DataSourceRepository sources, List<SourceExtractor> extractors, TargetWriter writer,
                              GraphTargetWriter graphWriter, EipEventPublisher events, MeterRegistry metrics,
                              @Value("${ingestion.jobs.max-parallel:4}") int maxParallel,
                              @Value("${ingestion.jobs.default-batch-size:500}") int defaultBatchSize,
                              @Value("${ingestion.jobs.default-max-retries:3}") int defaultMaxRetries,
                              @Value("${ingestion.jobs.retry-backoff-seconds:30}") long retryBackoffSeconds) {
        this.jobs = jobs;
        this.runs = runs;
        this.mappings = mappings;
        this.sources = sources;
        this.extractors = extractors;
        this.writer = writer;
        this.graphWriter = graphWriter;
        this.events = events;
        this.metrics = metrics;
        this.defaultBatchSize = defaultBatchSize;
        this.defaultMaxRetries = defaultMaxRetries;
        this.retryBackoffSeconds = retryBackoffSeconds;
        this.pool = Executors.newFixedThreadPool(Math.max(1, maxParallel));
    }

    /**
     * Flush a batch of vertices to okg-ingest. Returns {@code [written, failed]};
     * a whole-batch failure counts every row failed and is logged (the run then
     * follows the normal partial-failure / all-failed handling).
     */
    private long[] flushVertices(String runId, String vertexType, boolean ensureType,
                                 List<GraphTargetWriter.Vertex> batch) {
        try {
            int w = graphWriter.upsertVertices(vertexType, ensureType, batch);
            return new long[]{w, batch.size() - w};
        } catch (RuntimeException e) {
            log.warn("Run {}: graph vertex batch of {} failed and was skipped: {}",
                    runId, batch.size(), e.getMessage());
            return new long[]{0, batch.size()};
        }
    }

    /** Mapped values minus the synthetic {@code tenantId} — okg-ingest sets that itself. */
    private static Map<String, Object> withoutTenant(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.remove("tenantId");
        return copy;
    }

    /** Groups edge links by (relationship type, from-vertex type, to-vertex type). */
    private record EdgeGroupKey(String edgeType, String fromType, String toType) {}

    /**
     * For this row's vertex ({@code thisExternalId}), emit one link per edge
     * definition whose key column has a value. OUT = this → other, IN = other →
     * this; the related externalId is read straight from the source row.
     */
    private static void accumulateEdges(
            List<io.orphos.eip.dataingestion.vo.MappingDtos.EdgeMapping> edgeDefs,
            String thisType, String thisExternalId, Map<String, String> sourceRow,
            Map<EdgeGroupKey, List<GraphTargetWriter.EdgeLink>> buffers) {
        for (var ed : edgeDefs) {
            String other = sourceRow.get(ed.sourceKeyColumn());
            if (other == null || other.isBlank()) continue;   // no related key on this row → no edge
            boolean out = !"IN".equalsIgnoreCase(ed.direction());   // default OUT
            String fromType = out ? thisType : ed.targetVertexType();
            String toType   = out ? ed.targetVertexType() : thisType;
            String fromId   = out ? thisExternalId : other;
            String toId     = out ? other : thisExternalId;
            buffers.computeIfAbsent(new EdgeGroupKey(ed.edgeType(), fromType, toType), k -> new ArrayList<>())
                    .add(new GraphTargetWriter.EdgeLink(fromId, toId));
        }
    }

    /** Flush accumulated edges to okg-ingest in batches; logs the total created. */
    private void flushEdges(String runId, int batchSize,
                            Map<EdgeGroupKey, List<GraphTargetWriter.EdgeLink>> buffers) {
        long created = 0;
        for (Map.Entry<EdgeGroupKey, List<GraphTargetWriter.EdgeLink>> e : buffers.entrySet()) {
            EdgeGroupKey k = e.getKey();
            List<GraphTargetWriter.EdgeLink> links = e.getValue();
            for (int i = 0; i < links.size(); i += batchSize) {
                List<GraphTargetWriter.EdgeLink> chunk = links.subList(i, Math.min(i + batchSize, links.size()));
                try {
                    created += graphWriter.upsertEdges(k.edgeType(), k.fromType(), k.toType(), true, chunk);
                } catch (RuntimeException ex) {
                    log.warn("Run {}: edge batch {} ({}→{}) of {} failed: {}",
                            runId, k.edgeType(), k.fromType(), k.toType(), chunk.size(), ex.getMessage());
                }
            }
        }
        if (created > 0 || !buffers.isEmpty()) {
            log.info("Run {}: created {} relationship edge(s)", runId, created);
        }
    }

    private SourceExtractor extractorFor(SourceKind kind) {
        return extractors.stream().filter(e -> e.supports(kind)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No extractor supports source kind " + kind));
    }

    /**
     * Create a QUEUED run row and submit it for immediate async execution
     * (req 5.1/5.3 — no scheduler wait). Returns the run id to poll.
     */
    public String trigger(Map<String, Object> job, String triggeredBy) {
        String runId = "run-" + UUID.randomUUID();
        runs.insert(runId, str(job, "id"), str(job, "name"), 0, triggeredBy, "");
        cancelFlags.putIfAbsent(runId, new AtomicBoolean());
        submit(runId, job, 0);
        return runId;
    }

    /** Claim and execute a persisted retry run picked up by the scheduler (req 4.6). */
    public void submitRetry(Map<String, Object> run, Map<String, Object> job) {
        String runId = str(run, "id");
        runs.claim(runId);
        cancelFlags.putIfAbsent(runId, new AtomicBoolean());
        submit(runId, job, intOf(run, "attempt", 0));
    }

    /**
     * Requirement 5.5 — request cooperative cancellation of an active run.
     * Returns {@code false} when the run is not executing in this process
     * (e.g. an unclaimed retry row — the caller cancels it in the DB instead).
     */
    public boolean cancel(String runId) {
        AtomicBoolean flag = cancelFlags.get(runId);
        if (flag == null) return false;
        flag.set(true);
        return true;
    }

    private void submit(String runId, Map<String, Object> job, int attempt) {
        // ThreadLocal tenant context does not cross executor threads — capture
        // here, rebind inside the worker (see TenantContextHolder javadoc).
        TenantContext ctx = TenantContextHolder.currentOrNull();
        pool.submit(() -> {
            try {
                TenantContextHolder.runWith(ctx, () -> execute(runId, job, attempt));
            } catch (Exception e) {
                log.error("Run {} crashed outside the run loop: {}", runId, e.getMessage(), e);
            }
        });
    }

    /**
     * The run loop. Package-private so unit tests can drive it synchronously
     * on the test thread (with tenant context bound).
     */
    void execute(String runId, Map<String, Object> job, int attempt) {
        AtomicBoolean cancelled = cancelFlags.computeIfAbsent(runId, k -> new AtomicBoolean());
        String jobId = str(job, "id");
        String jobName = str(job, "name");
        Timer.Sample sample = Timer.start(metrics);
        long read = 0, written = 0, failed = 0, total = -1;
        String status = "FAILED";
        String error = "";
        try {
            if (cancelled.get()) {          // cancelled while still queued
                status = "CANCELLED";
                return;                     // finally-block records the terminal row
            }
            runs.markRunning(runId);
            log.info("Run {} of job '{}' started (attempt {})", runId, jobName, attempt);

            Map<String, Object> mappingRow = mappings.findById(str(job, "mappingId"));
            if (mappingRow == null) {
                throw new IllegalStateException("Mapping " + str(job, "mappingId") + " no longer exists");
            }
            MappingView mapping = mappings.toView(mappingRow);
            if (!mapping.enabled()) {
                throw new IllegalStateException("Mapping '" + mapping.name() + "' is disabled");
            }

            LoadMode mode = enumOf(LoadMode.class, str(job, "loadMode"), LoadMode.FULL);
            MergeStrategy merge = enumOf(MergeStrategy.class, str(job, "mergeStrategy"), MergeStrategy.INSERT);
            boolean upsert = merge == MergeStrategy.UPSERT;
            boolean incremental = mode == LoadMode.INCREMENTAL;
            String keyTarget = mapping.keyTarget();
            String incrementalColumn = mapping.incrementalColumn();
            boolean graphTarget = "VERTEX".equalsIgnoreCase(mapping.targetKind());
            boolean keyed = upsert || graphTarget;   // graph vertices are always keyed by externalId
            if (keyed && keyTarget.isBlank()) {
                throw new IllegalStateException(graphTarget
                        ? "Graph vertex target requires the mapping to define a keyTarget (externalId)"
                        : "UPSERT merge strategy requires the mapping to define a keyTarget");
            }
            if (incremental && incrementalColumn.isBlank()) {
                throw new IllegalStateException("INCREMENTAL load mode requires the mapping to define an incrementalColumn");
            }

            Map<String, Object> source = sources.findById(mapping.dataSourceId());
            if (source == null) {
                throw new IllegalStateException("Data source " + mapping.dataSourceId() + " no longer exists");
            }
            SourceKind sourceKind = DataSourceService.parseKind(str(source, "kind"));
            SourceExtractor extractor = extractorFor(sourceKind);

            int jobBatch = intOf(job, "batchSize", 0);
            int batchSize = jobBatch > 0 ? jobBatch : defaultBatchSize;           // req 6.7
            String tenantId = TenantContextHolder.requireTenantId().toString();
            String watermark = str(job, "watermark");
            String maxWatermarkSeen = null;

            if (graphTarget) {
                if (mode == LoadMode.FULL) {
                    // The graph target upserts by (tenantId, externalId); there is no
                    // per-tenant bulk wipe endpoint, so a FULL reload behaves as an
                    // upsert-refresh rather than delete-then-insert.
                    log.info("Run {}: FULL load on a graph vertex target performs an upsert refresh "
                            + "(no pre-delete) for type '{}'", runId, mapping.targetClass());
                }
            } else {
                writer.ensureType(mapping.targetClass(), upsert ? keyTarget : "");
                if (mode == LoadMode.FULL) {
                    writer.deleteAll(mapping.targetClass());                      // req 6.3
                }
            }

            // The source read is delegated to the kind-specific extractor (JDBC
            // streams via a cursor; ERP pages over HTTP). Document targets write
            // per row via TargetWriter; graph targets buffer and flush batches to
            // okg-ingest via GraphTargetWriter.
            List<GraphTargetWriter.Vertex> vertexBuffer = new ArrayList<>();
            boolean ensureType = true;   // create the vertex type on the first graph batch
            // Edge links accumulate across the whole run (a related vertex may be a
            // forward reference), keyed by relationship + endpoint types, then flush
            // once every vertex is written.
            List<io.orphos.eip.dataingestion.vo.MappingDtos.EdgeMapping> edgeDefs =
                    (graphTarget && mapping.edges() != null) ? mapping.edges() : List.of();
            Map<EdgeGroupKey, List<GraphTargetWriter.EdgeLink>> edgeBuffers = new LinkedHashMap<>();
            try (RowCursor cursor = extractor.open(new ExtractRequest(source, mapping, incremental, watermark))) {
                total = cursor.total();
                Map<String, String> sourceRow;
                while ((sourceRow = cursor.next()) != null) {
                    if (cancelled.get()) {                                        // req 5.5
                        status = "CANCELLED";
                        break;
                    }
                    read++;
                    MappedRow row = mapRow(sourceRow, mapping, keyed, keyTarget, tenantId);
                    if (row == null) {
                        failed++;                                                 // req 6.5 — bad key, row skipped
                    } else if (graphTarget) {
                        String externalId = String.valueOf(row.keyValue());
                        vertexBuffer.add(new GraphTargetWriter.Vertex(externalId, withoutTenant(row.values())));
                        accumulateEdges(edgeDefs, mapping.targetClass(), externalId, sourceRow, edgeBuffers);
                        if (vertexBuffer.size() >= batchSize) {
                            long[] wf = flushVertices(runId, mapping.targetClass(), ensureType, vertexBuffer);
                            written += wf[0]; failed += wf[1]; ensureType = false; vertexBuffer.clear();
                        }
                    } else {
                        try {
                            if (upsert) {
                                writer.upsertRow(mapping.targetClass(), keyTarget, row.keyValue(), row.values());
                            } else {
                                writer.insertRow(mapping.targetClass(), row.values());
                            }
                            written++;
                        } catch (RuntimeException we) {
                            failed++;                                             // target rejected the row; keep going
                            if (failed <= 3) {                                    // first few at WARN — enough to diagnose
                                log.warn("Run {}: row write failed and was skipped: {}", runId, we.getMessage());
                            } else {
                                log.debug("Run {}: row write failed and was skipped: {}", runId, we.getMessage());
                            }
                        }
                    }
                    if (read % batchSize == 0) {                                  // req 5.4/6.7 — per-batch progress
                        runs.updateProgress(runId, read, written, failed, total, percent(read, total));
                    }
                }
                if (graphTarget && !vertexBuffer.isEmpty() && !"CANCELLED".equals(status)) {
                    long[] wf = flushVertices(runId, mapping.targetClass(), ensureType, vertexBuffer);
                    written += wf[0]; failed += wf[1]; vertexBuffer.clear();
                }
                // Relationships are created after all vertices are written, so
                // same-run forward references resolve. Edge failures are reported
                // but do not fail the run — the vertex load is the primary outcome.
                if (graphTarget && !edgeBuffers.isEmpty() && !"CANCELLED".equals(status)) {
                    flushEdges(runId, batchSize, edgeBuffers);
                }
                if (incremental) maxWatermarkSeen = cursor.maxWatermark();        // req 6.2
            }
            if (!"CANCELLED".equals(status)) {
                // Req 6.6 tolerates partial failures, but a run where EVERY row
                // failed to load is a failure — it should retry and alert, not
                // report success with zero effect.
                if (read > 0 && written == 0 && failed == read) {
                    status = "FAILED";
                    error = "All " + read + " extracted rows failed to load — see service logs";
                } else {
                    status = "SUCCEEDED";
                    if (incremental && maxWatermarkSeen != null) {
                        jobs.updateWatermark(jobId, maxWatermarkSeen);            // req 6.2
                    }
                }
            }
        } catch (Exception e) {
            status = "FAILED";
            error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("Run {} of job '{}' failed: {}", runId, jobName, error, e);
        } finally {
            cancelFlags.remove(runId);
            int pct = "SUCCEEDED".equals(status) && total >= 0 ? 100 : percent(read, total);
            runs.finish(runId, status, read, written, failed, total, pct, error);
            sample.stop(metrics.timer("ingest.run.duration"));                    // req 7.3
            metrics.counter("ingest.runs.total", "status", status).increment();
            if (written > 0) metrics.counter("ingest.records.written").increment(written);
            log.info("Run {} of job '{}' finished: {} (read={}, written={}, failed={})",  // req 6.8
                    runId, jobName, status, read, written, failed);
        }
        if ("SUCCEEDED".equals(status)) {
            triggerDependents(jobId);
        } else if ("FAILED".equals(status)) {
            handleFailure(job, runId, attempt, error);
        }
    }

    /**
     * Requirement 6.1 — per row: gather source values, combine (many-to-one),
     * apply the transform, convert to the target type. Returns {@code null}
     * when the upsert key is null/blank or fails conversion (req 6.5 — counted
     * failed, row skipped). Conversion failures on NON-key columns drop the
     * value to null without failing the row (req 6.6 — the behaviour
     * MappingValidator's warning promises: "values that fail to convert will
     * be dropped to null at load time").
     */
    private MappedRow mapRow(Map<String, String> sourceRow, MappingView mapping, boolean upsert,
                             String keyTarget, String tenantId) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object keyValue = null;
        for (ColumnMapping m : mapping.mappings()) {
            List<String> sourceValues = new ArrayList<>(m.sources().size());
            for (String src : m.sources()) sourceValues.add(sourceRow.get(src));
            String combined = TransformFunctions.combine(sourceValues, m.transform());
            String transformed = TransformFunctions.apply(combined, m.transform());
            boolean isKey = upsert && m.target().equalsIgnoreCase(keyTarget);
            Object converted;
            try {
                converted = TransformFunctions.convert(transformed, targetTypeOf(m));
            } catch (Exception e) {
                if (isKey) return null;                                           // req 6.5
                log.debug("Value for target '{}' failed to convert and was dropped to null: {}",
                        m.target(), e.getMessage());                              // req 6.6
                converted = null;
            }
            if (isKey) {
                if (converted == null || String.valueOf(converted).isBlank()) return null;
                keyValue = converted;
            }
            if (converted != null) values.put(m.target(), converted);
        }
        values.put("tenantId", tenantId);
        return new MappedRow(keyValue, values);
    }

    /** Requirement 4.5 — auto-trigger schedule-less dependents after a successful run. */
    private void triggerDependents(String jobId) {
        for (Map<String, Object> dependent : jobs.findDependents(jobId)) {
            log.info("Triggering dependent job '{}' after job {} succeeded", str(dependent, "name"), jobId);
            trigger(dependent, "DEPENDENCY");
        }
    }

    /**
     * Requirement 4.6/4.7 — queue an exponential-backoff retry
     * (base * 2^attempt) as a persisted run row, or — once retries are
     * exhausted — publish a DATA_INGEST_FAILED event so the notification
     * rules configured in Admin → Communications can alert someone.
     */
    private void handleFailure(Map<String, Object> job, String runId, int attempt, String error) {
        String jobId = str(job, "id");
        String jobName = str(job, "name");
        int jobRetries = intOf(job, "maxRetries", -1);
        int maxRetries = jobRetries >= 0 ? jobRetries : defaultMaxRetries;
        if (attempt < maxRetries) {
            long delaySeconds = retryBackoffSeconds * (1L << attempt);
            String nextRetryAt = Instant.now().plusSeconds(delaySeconds).toString();
            String retryRunId = "run-" + UUID.randomUUID();
            runs.insert(retryRunId, jobId, jobName, attempt + 1, "RETRY", nextRetryAt);
            log.info("Run {} failed — retry {}/{} queued for {}", runId, attempt + 1, maxRetries, nextRetryAt);
        } else {
            String tenantId = TenantContextHolder.requireTenantId().toString();
            events.publish("DATA_INGEST_FAILED", "ERROR", jobName,
                    "Ingestion job '" + jobName + "' failed after " + attempt + " retries: " + error,
                    tenantId,
                    Map.of("jobId", jobId, "runId", runId, "attempt", attempt));
            log.error("Run {} of job '{}' failed permanently after {} retries: {}",
                    runId, jobName, attempt, error);
        }
    }

    private static TargetType targetTypeOf(ColumnMapping m) {
        String raw = m.targetType();
        if (raw == null || raw.isBlank()) return TargetType.STRING;
        try {
            return TargetType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return TargetType.STRING; // save-time validation rejects unknown types; be lenient here
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String raw, E fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int percent(long read, long total) {
        return total > 0 ? (int) Math.min(100, read * 100 / total) : -1;
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }

    private static int intOf(Map<String, Object> row, String field, int def) {
        return row.get(field) instanceof Number n ? n.intValue() : def;
    }

    /** One transformed source row ready for the target writer. */
    private record MappedRow(Object keyValue, Map<String, Object> values) {}

    @Override
    public void destroy() {
        pool.shutdownNow();
    }
}
