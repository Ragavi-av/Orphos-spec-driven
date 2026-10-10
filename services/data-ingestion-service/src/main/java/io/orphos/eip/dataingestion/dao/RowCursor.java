package io.orphos.eip.dataingestion.dao;

import java.util.Map;

/**
 * A forward-only cursor over a source's rows for one extraction run. Each row is
 * a column-name → string-value map (only the columns the mapping references are
 * guaranteed present). Implementations stream (JDBC server-side cursor, REST
 * paging) so a large object never fully materialises in memory.
 *
 * <p>Closing releases the underlying resource (JDBC connection, HTTP state).</p>
 */
public interface RowCursor extends AutoCloseable {

    /** Total rows the run will process, or {@code -1} when the source cannot say cheaply. */
    long total();

    /** The next row, or {@code null} when the source is exhausted. */
    Map<String, String> next();

    /**
     * The greatest value seen so far in the incremental (watermark) column, or
     * {@code null} when the run is not incremental / nothing was seen. Valid to
     * read after iteration completes.
     */
    String maxWatermark();

    @Override
    void close();
}
