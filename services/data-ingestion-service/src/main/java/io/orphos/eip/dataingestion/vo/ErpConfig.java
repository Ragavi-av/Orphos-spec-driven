package io.orphos.eip.dataingestion.vo;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Normalized configuration for an enterprise-system source ({@code REST_OPENAPI}
 * or {@code ZOHO_ERP}). Persisted as the JSON blob in
 * {@code IngestDataSource.configJson} — it holds everything the discovery and
 * extraction paths need <b>except secrets</b> (the OAuth {@code clientSecret} /
 * {@code refreshToken} bundle lives in the vault, referenced by
 * {@code credentialRef}).
 *
 * <p>Records are Jackson-friendly and tolerate unknown fields so the shape can
 * grow without breaking older stored rows. Every collection/field may be null;
 * read through the {@code *OrDefault} helpers.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ErpConfig(
        String erpVendor,          // "ZOHO" | "GENERIC"
        String dataCenter,         // Zoho DC: com | eu | in | au | jp | ca | sa (drives base/accounts URLs)
        String tokenUrl,           // OAuth2 token endpoint; derived for Zoho when blank
        String clientId,           // OAuth2 client id (non-secret)
        String scope,              // OAuth2 scope requested on refresh
        Pagination pagination,
        String incrementalParam,   // query param used for incremental pulls (Zoho: "modified_time")
        List<ErpObject> objects) {

    public static final String VENDOR_ZOHO = "ZOHO";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErpObject(
            String name,           // display name; used as the mapping "sourceTable"
            String path,           // path appended to the source baseUrl
            String method,         // HTTP method (only GET is read today)
            String dataPath,       // dotted path to the record array in the response ("data", "result.items", ...)
            List<ErpField> fields) {
        public String methodOrGet() { return method == null || method.isBlank() ? "GET" : method.trim().toUpperCase(); }
        public List<ErpField> fieldsOrEmpty() { return fields == null ? List.of() : fields; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErpField(
            String name,
            String type) {        // TargetType-compatible: STRING | INTEGER | NUMBER | BOOLEAN | DATE
        public String typeOrString() { return type == null || type.isBlank() ? "STRING" : type.trim().toUpperCase(); }
    }

    /** How the object endpoint paginates. {@code style=NONE} pulls a single page. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pagination(
            String style,          // PAGE | OFFSET | CURSOR | NONE
            String pageParam,      // PAGE/OFFSET: the param carrying the page/offset value
            String sizeParam,      // param carrying the page size
            Integer pageSize,      // records per page
            Integer startPage,     // first page/offset value (Zoho pages start at 1)
            String moreFlagPath,   // dotted path to a boolean "more records" flag (Zoho: "info.more_records")
            String cursorParam,    // CURSOR: request param carrying the next-page token
            String cursorPath) {   // CURSOR: dotted path to the next-page token in the response
        public String styleOrNone() { return style == null || style.isBlank() ? "NONE" : style.trim().toUpperCase(); }
        public int pageSizeOrDefault() { return pageSize == null || pageSize <= 0 ? 200 : pageSize; }
        public int startPageOrDefault() { return startPage == null ? 1 : startPage; }
    }

    public List<ErpObject> objectsOrEmpty() { return objects == null ? List.of() : objects; }

    public Pagination paginationOrNone() {
        return pagination == null
                ? new Pagination("NONE", null, null, null, null, null, null, null)
                : pagination;
    }

    /** Find a configured object by its display name (the mapping's sourceTable). */
    public ErpObject findObject(String name) {
        for (ErpObject o : objectsOrEmpty()) {
            if (o.name() != null && o.name().equalsIgnoreCase(name)) return o;
        }
        return null;
    }

    // ── (de)serialization helpers ────────────────────────────────────────────

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Parse a stored {@code configJson}; blank/invalid → an empty GENERIC config. */
    public static ErpConfig parse(String json) {
        if (json == null || json.isBlank()) {
            return new ErpConfig("GENERIC", null, null, null, null, null, null, List.of());
        }
        try {
            return MAPPER.readValue(json, ErpConfig.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid ERP configJson: " + e.getMessage(), e);
        }
    }

    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize ERP config", e);
        }
    }
}
