package io.orphos.eip.dataingestion.vo;

import java.util.List;

/** Request/response shapes for masked data preview (req 8.5) and classification tags (req 8.6). */
public final class PreviewDtos {

    private PreviewDtos() {}

    /** Data-classification levels, least to most sensitive (req 8.6). */
    public enum ClassificationLevel { PUBLIC, INTERNAL, CONFIDENTIAL, RESTRICTED }

    public record PreviewColumn(
            String name,
            boolean masked) {}    // true when values in this column are masked in `rows`

    public record PreviewResponse(
            String table,                    // resolved schema-qualified table name
            List<PreviewColumn> columns,
            List<List<String>> rows,         // values as strings; masked where the column says so
            int rowLimit,                    // effective (clamped) row limit applied
            String classification,           // data source's classification level ("" when unset)
            List<String> classificationTags) {}

    public record ClassificationRequest(
            String level,                    // PUBLIC | INTERNAL | CONFIDENTIAL | RESTRICTED
            List<String> tags) {}            // free-form labels (pii, gdpr, finance, ...)

    public record ClassificationView(
            String id,
            String level,
            List<String> tags) {}
}
