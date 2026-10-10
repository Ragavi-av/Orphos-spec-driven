package io.orphos.eip.dataingestion.dao;

import io.orphos.eip.dataingestion.component.ErpHttpClient;
import io.orphos.eip.dataingestion.component.SecretRefService;
import io.orphos.eip.dataingestion.component.ZohoCamelRoute;
import io.orphos.eip.dataingestion.repository.DataSourceRepository;
import io.orphos.eip.dataingestion.repository.MappingRepository;
import io.orphos.eip.dataingestion.vo.ErpConfig;
import io.orphos.eip.dataingestion.vo.ErpConnection;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;
import io.orphos.eip.dataingestion.vo.ZohoPageRequest;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads Zoho rows for one Spring Batch step execution, one page at a time — the Camel-backed
 * counterpart to {@link RestSourceExtractor}'s {@code RestCursor}, whose pagination/watermark
 * logic this class deliberately mirrors so the two pipelines behave identically for an admin
 * comparing them.
 *
 * <p>{@code @StepScope}: Spring Batch creates one instance per step execution (not a shared
 * singleton), late-binding the constructor's {@code @Value("#{jobParameters[...]}")} arguments —
 * the standard mechanism for per-run, per-tenant configuration on what would otherwise be a
 * container-managed singleton bean.</p>
 */
@Component
@StepScope
public class ZohoItemReader implements ItemReader<Map<String, String>> {

    private static final Logger log = LogManager.getLogger(ZohoItemReader.class);

    private final ProducerTemplate camel;
    private final DataSourceRepository dataSources;
    private final MappingRepository mappings;
    private final SecretRefService secrets;
    private final String mappingId;
    private final boolean incremental;
    private final String watermark;

    private boolean initialized;
    private ErpConnection conn;
    private ErpConfig.ErpObject obj;
    private ErpConfig.Pagination pg;
    private String style;
    private String incrementalColumn;
    private Map<String, String> extraQuery;

    private final Deque<Map<String, Object>> buffer = new ArrayDeque<>();
    private int pageValue;
    private String cursor;
    private boolean exhausted;
    private String maxWatermark;

    public ZohoItemReader(ProducerTemplate camel, DataSourceRepository dataSources,
                          MappingRepository mappings, SecretRefService secrets,
                          @Value("#{jobParameters['mappingId']}") String mappingId,
                          @Value("#{jobParameters['incremental']}") String incrementalFlag,
                          @Value("#{jobParameters['watermark']}") String watermark) {
        this.camel = camel;
        this.dataSources = dataSources;
        this.mappings = mappings;
        this.secrets = secrets;
        this.mappingId = mappingId;
        this.incremental = Boolean.parseBoolean(incrementalFlag);
        this.watermark = watermark == null ? "" : watermark;
    }

    /** The greatest value seen in the incremental column so far — read by {@code ZohoBatchController} after the step completes. */
    public String maxWatermark() {
        return maxWatermark;
    }

    @Override
    public Map<String, String> read() {
        if (!initialized) init();
        if (buffer.isEmpty()) fill();
        Map<String, Object> raw = buffer.poll();
        if (raw == null) return null;   // signals end-of-input to Spring Batch

        Map<String, String> row = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            row.put(e.getKey(), e.getValue() == null ? null : String.valueOf(e.getValue()));
        }
        if (incrementalColumn != null) {
            String w = row.get(incrementalColumn);
            if (w != null && isGreater(w, maxWatermark)) maxWatermark = w;
        }
        return row;
    }

    private void init() {
        Map<String, Object> mappingRow = mappings.findById(mappingId);
        if (mappingRow == null) {
            throw new IllegalStateException("Mapping " + mappingId + " no longer exists");
        }
        MappingView mapping = mappings.toView(mappingRow);
        Map<String, Object> sourceRow = dataSources.findById(mapping.dataSourceId());
        if (sourceRow == null) {
            throw new IllegalStateException("Data source " + mapping.dataSourceId() + " no longer exists");
        }
        String resolvedSecret = secrets.resolve(str(sourceRow, "credentialRef")).orElse(null);
        conn = ErpConnection.from(sourceRow, resolvedSecret);
        ErpConfig cfg = conn.config();
        obj = cfg.findObject(mapping.sourceTable());
        if (obj == null) {
            throw new IllegalStateException("Source has no configured object named '" + mapping.sourceTable() + "'");
        }
        pg = cfg.paginationOrNone();
        style = pg.styleOrNone();
        pageValue = pg.startPageOrDefault();
        incrementalColumn = incremental ? mapping.incrementalColumn() : null;

        extraQuery = new LinkedHashMap<>();
        if (incremental && !watermark.isBlank() && cfg.incrementalParam() != null && !cfg.incrementalParam().isBlank()) {
            extraQuery.put(cfg.incrementalParam(), watermark);
        }
        initialized = true;
    }

    /** Fetch the next page(s) via the Camel route until the buffer has rows or the source is exhausted. */
    private void fill() {
        while (buffer.isEmpty() && !exhausted) {
            ZohoPageRequest req = new ZohoPageRequest(conn, obj, pageValue, cursor, extraQuery);
            ErpHttpClient.Page page;
            try {
                page = camel.requestBody(ZohoCamelRoute.FETCH_PAGE_ENDPOINT, req, ErpHttpClient.Page.class);
            } catch (CamelExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new IllegalStateException("Zoho page fetch failed: " + cause.getMessage(), cause);
            }
            int got = page.records().size();
            buffer.addAll(page.records());

            boolean more = switch (style) {
                case "CURSOR" -> page.nextCursor() != null && !page.nextCursor().isBlank();
                case "PAGE", "OFFSET" -> (pg.moreFlagPath() != null && !pg.moreFlagPath().isBlank())
                        ? page.hasMore()
                        : got >= pg.pageSizeOrDefault();
                default -> false;   // NONE — single page
            };
            if (got == 0) more = false;   // never loop on an empty page

            if (more) {
                if ("OFFSET".equals(style)) pageValue += pg.pageSizeOrDefault();
                else pageValue += 1;
                cursor = page.nextCursor();
            } else {
                exhausted = true;
            }
        }
    }

    /** Keep the numerically- or lexicographically-greater watermark (REST isn't guaranteed ordered). */
    private static boolean isGreater(String candidate, String current) {
        if (current == null) return true;
        try {
            return Long.parseLong(candidate.trim()) > Long.parseLong(current.trim());
        } catch (NumberFormatException ignored) {
            return candidate.compareTo(current) > 0;
        }
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }
}
