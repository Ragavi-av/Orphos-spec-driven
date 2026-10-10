package io.orphos.eip.dataingestion.service;

import io.orphos.eip.dataingestion.vo.ErpConfig;
import io.orphos.eip.dataingestion.vo.ErpDtos.OpenApiPreview;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses an uploaded OpenAPI/Swagger document (2.0 or 3.x, JSON or YAML) into a
 * list of <b>selectable objects</b>: each collection-returning {@code GET}
 * operation becomes an {@link ErpConfig.ErpObject} whose response item schema
 * yields the field ("column") list. This is what lets an arbitrary enterprise
 * system flow through the same mapping UI as a JDBC table.
 *
 * <p>Discovery is heuristic and best-effort: unparseable pieces are skipped with
 * a warning rather than failing the whole upload (mirrors the JDBC metadata
 * discovery's partial-results contract).</p>
 */
@Service
public class OpenApiSpecService {

    private static final Logger log = LogManager.getLogger(OpenApiSpecService.class);

    /** Parse a spec into a preview (objects + suggested base URL + warnings). */
    public OpenApiPreview preview(String specText) {
        if (specText == null || specText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No OpenAPI/Swagger document supplied");
        }
        ParseOptions opts = new ParseOptions();
        opts.setResolve(true);            // inline external/internal $refs
        opts.setResolveFully(true);       // flatten so item schemas carry their properties
        SwaggerParseResult result = new OpenAPIV3Parser().readContents(specText, null, opts);
        OpenAPI api = result.getOpenAPI();
        List<String> warnings = new ArrayList<>();
        if (result.getMessages() != null) warnings.addAll(result.getMessages());
        if (api == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Could not parse the document as OpenAPI/Swagger" +
                            (warnings.isEmpty() ? "" : ": " + String.join("; ", warnings)));
        }

        String suggestedBaseUrl = "";
        if (api.getServers() != null && !api.getServers().isEmpty()
                && api.getServers().get(0).getUrl() != null) {
            suggestedBaseUrl = api.getServers().get(0).getUrl();
        }

        List<ErpConfig.ErpObject> objects = new ArrayList<>();
        if (api.getPaths() != null) {
            for (Map.Entry<String, PathItem> e : api.getPaths().entrySet()) {
                String path = e.getKey();
                Operation get = e.getValue() == null ? null : e.getValue().getGet();
                if (get == null) continue;
                // Skip parameterized single-item lookups (…/{id}) — we ingest collections.
                if (path.contains("{")) continue;
                try {
                    objects.add(toObject(path, get));
                } catch (Exception ex) {
                    warnings.add("Skipped GET " + path + ": " + ex.getMessage());
                    log.debug("OpenAPI object discovery skipped GET {}: {}", path, ex.getMessage());
                }
            }
        }
        if (objects.isEmpty()) {
            warnings.add("No collection-returning GET operations were discovered — "
                    + "add object definitions manually if the spec uses non-standard shapes");
        }
        return new OpenApiPreview(suggestedBaseUrl, objects, warnings);
    }

    private ErpConfig.ErpObject toObject(String path, Operation get) {
        String name = objectName(path, get);
        Schema<?> responseSchema = successResponseSchema(get);
        DataAndItem di = resolveArray(responseSchema);
        List<ErpConfig.ErpField> fields = fieldsOf(di.itemSchema());
        return new ErpConfig.ErpObject(name, path, "GET", di.dataPath(), fields);
    }

    /** operationId, else the last non-templated path segment, TitleCased. */
    private static String objectName(String path, Operation get) {
        if (get.getOperationId() != null && !get.getOperationId().isBlank()) {
            return get.getOperationId().trim();
        }
        String[] segs = path.split("/");
        for (int i = segs.length - 1; i >= 0; i--) {
            if (!segs[i].isBlank() && !segs[i].contains("{")) return segs[i];
        }
        return path;
    }

    private static Schema<?> successResponseSchema(Operation get) {
        ApiResponses responses = get.getResponses();
        if (responses == null) return null;
        ApiResponse ok = responses.get("200");
        if (ok == null) ok = responses.get("201");
        if (ok == null) ok = responses.getDefault();
        if (ok == null || ok.getContent() == null) return null;
        Content content = ok.getContent();
        MediaType mt = content.get("application/json");
        if (mt == null && !content.isEmpty()) mt = content.values().iterator().next();
        return mt == null ? null : mt.getSchema();
    }

    /** The array of records within a response schema, and the dotted path to reach it. */
    private record DataAndItem(String dataPath, Schema<?> itemSchema) {}

    private static DataAndItem resolveArray(Schema<?> schema) {
        if (schema == null) return new DataAndItem("", null);
        // Response is itself an array.
        if ("array".equals(schema.getType()) && schema.getItems() != null) {
            return new DataAndItem("", schema.getItems());
        }
        // Response is an object wrapping an array property (Zoho: {"data": [...]}).
        if (schema.getProperties() != null) {
            for (Map.Entry<String, Schema> p : schema.getProperties().entrySet()) {
                Schema<?> ps = p.getValue();
                if (ps != null && "array".equals(ps.getType()) && ps.getItems() != null) {
                    return new DataAndItem(p.getKey(), ps.getItems());
                }
            }
            // No array property — treat the object itself as a single record.
            return new DataAndItem("", schema);
        }
        return new DataAndItem("", schema);
    }

    private static List<ErpConfig.ErpField> fieldsOf(Schema<?> itemSchema) {
        List<ErpConfig.ErpField> fields = new ArrayList<>();
        if (itemSchema == null || itemSchema.getProperties() == null) return fields;
        for (Map.Entry<String, Schema> p : itemSchema.getProperties().entrySet()) {
            fields.add(new ErpConfig.ErpField(p.getKey(), mapType(p.getValue())));
        }
        return fields;
    }

    /** OpenAPI type/format → the loader's TargetType names. */
    private static String mapType(Schema<?> s) {
        if (s == null || s.getType() == null) return "STRING";
        String fmt = s.getFormat() == null ? "" : s.getFormat();
        return switch (s.getType()) {
            case "integer" -> "INTEGER";
            case "number" -> "NUMBER";
            case "boolean" -> "BOOLEAN";
            case "string" -> ("date".equals(fmt) || "date-time".equals(fmt)) ? "DATE" : "STRING";
            default -> "STRING";
        };
    }
}
