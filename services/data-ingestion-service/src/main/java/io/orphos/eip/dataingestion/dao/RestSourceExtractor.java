package io.orphos.eip.dataingestion.dao;

import io.orphos.eip.dataingestion.vo.DataSourceDtos;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.SourceKind;
import io.orphos.eip.dataingestion.vo.ErpConfig;
import io.orphos.eip.dataingestion.vo.ErpConnection;
import io.orphos.eip.dataingestion.component.ErpHttpClient;
import io.orphos.eip.dataingestion.vo.ExtractRequest;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;
import io.orphos.eip.dataingestion.component.SecretRefService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streams records out of an enterprise-system (REST_OPENAPI / ZOHO_ERP) source by
 * paging its object endpoint through {@link ErpHttpClient}. The mapping's
 * {@code sourceTable} names the configured object; each JSON record becomes a
 * column → string map. Incremental runs pass the watermark as the configured
 * filter param and track the greatest value seen in the incremental field.
 */
@Component
public class RestSourceExtractor implements SourceExtractor {

    private static final Logger log = LogManager.getLogger(RestSourceExtractor.class);

    private final ErpHttpClient http;
    private final SecretRefService secrets;

    public RestSourceExtractor(ErpHttpClient http, SecretRefService secrets) {
        this.http = http;
        this.secrets = secrets;
    }

    @Override
    public boolean supports(SourceKind kind) {
        return DataSourceDtos.isHttpKind(kind);   // REST / REST_OPENAPI / ZOHO_ERP
    }

    @Override
    public RowCursor open(ExtractRequest req) {
        MappingView mapping = req.mapping();
        String resolvedSecret = secrets.resolve(str(req.source(), "credentialRef")).orElse(null);
        ErpConnection conn = ErpConnection.from(req.source(), resolvedSecret);
        ErpConfig cfg = conn.config();

        ErpConfig.ErpObject obj = cfg.findObject(mapping.sourceTable());
        if (obj == null) {
            throw new IllegalStateException("Source has no configured object named '" + mapping.sourceTable() + "'");
        }
        Map<String, String> extraQuery = new LinkedHashMap<>();
        if (req.incremental() && req.watermark() != null && !req.watermark().isBlank()
                && cfg.incrementalParam() != null && !cfg.incrementalParam().isBlank()) {
            extraQuery.put(cfg.incrementalParam(), req.watermark());
        }
        return new RestCursor(http, conn, cfg, obj,
                req.incremental() ? mapping.incrementalColumn() : null, extraQuery);
    }

    private static String str(Map<String, Object> row, String field) {
        Object v = row.get(field);
        return v == null ? "" : String.valueOf(v);
    }

    /** Lazily pages the endpoint, buffering one page at a time. */
    private static final class RestCursor implements RowCursor {
        private final ErpHttpClient http;
        private final ErpConnection conn;
        private final ErpConfig.Pagination pg;
        private final ErpConfig.ErpObject obj;
        private final String incrementalColumn;     // null when not incremental
        private final Map<String, String> extraQuery;

        private final Deque<Map<String, Object>> buffer = new ArrayDeque<>();
        private final String style;
        private int pageValue;
        private String cursor;
        private boolean exhausted;
        private boolean firstFetchDone;
        private String maxWatermark;

        RestCursor(ErpHttpClient http, ErpConnection conn, ErpConfig cfg, ErpConfig.ErpObject obj,
                   String incrementalColumn, Map<String, String> extraQuery) {
            this.http = http;
            this.conn = conn;
            this.pg = cfg.paginationOrNone();
            this.obj = obj;
            this.incrementalColumn = incrementalColumn;
            this.extraQuery = extraQuery;
            this.style = pg.styleOrNone();
            this.pageValue = pg.startPageOrDefault();
            this.cursor = null;
        }

        @Override public long total() { return -1; }        // ERP endpoints rarely give a cheap total
        @Override public String maxWatermark() { return maxWatermark; }

        @Override
        public Map<String, String> next() {
            if (buffer.isEmpty()) fill();
            Map<String, Object> raw = buffer.poll();
            if (raw == null) return null;
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

        /** Fetch the next page(s) until the buffer has rows or the source is exhausted. */
        private void fill() {
            while (buffer.isEmpty() && !exhausted) {
                ErpHttpClient.Page page = http.fetch(conn, obj, pageValue, cursor, extraQuery);
                int got = page.records().size();
                buffer.addAll(page.records());

                boolean more = switch (style) {
                    case "CURSOR" -> page.nextCursor() != null && !page.nextCursor().isBlank();
                    case "PAGE", "OFFSET" -> (pg.moreFlagPath() != null && !pg.moreFlagPath().isBlank())
                            ? page.hasMore()
                            : got >= pg.pageSizeOrDefault();
                    default -> false;   // NONE — single page
                };
                if (got == 0) more = false;     // never loop on an empty page

                if (more) {
                    if ("OFFSET".equals(style)) pageValue += pg.pageSizeOrDefault();
                    else pageValue += 1;
                    cursor = page.nextCursor();
                } else {
                    exhausted = true;
                }
                firstFetchDone = true;
            }
        }

        @Override
        public void close() { /* stateless HTTP — nothing to release */ }

        /** Keep the numerically- or lexicographically-greater watermark (REST isn't guaranteed ordered). */
        private static boolean isGreater(String candidate, String current) {
            if (current == null) return true;
            try {
                return Long.parseLong(candidate.trim()) > Long.parseLong(current.trim());
            } catch (NumberFormatException ignored) {
                return candidate.compareTo(current) > 0;
            }
        }
    }
}
