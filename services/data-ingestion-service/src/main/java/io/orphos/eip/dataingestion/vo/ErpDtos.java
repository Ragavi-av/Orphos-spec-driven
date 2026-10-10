package io.orphos.eip.dataingestion.vo;

import io.orphos.eip.dataingestion.vo.ErpConfig;

import java.util.List;

/** Request/response shapes for enterprise-system (ERP / OpenAPI) endpoints. */
public final class ErpDtos {

    private ErpDtos() {}

    /** Upload payload for the parse-and-preview endpoint (nothing is persisted). */
    public record ParseOpenApiRequest(
            String specJson,       // the raw OpenAPI/Swagger document (JSON or YAML text)
            String vendor) {}      // optional: "ZOHO" to merge the Zoho preset defaults

    /**
     * Preview result: the discovered objects the admin can choose to ingest, plus
     * a suggested base URL (from the spec's {@code servers}) and any parser
     * warnings. The UI persists the confirmed subset into {@code configJson}.
     */
    public record OpenApiPreview(
            String suggestedBaseUrl,
            List<ErpConfig.ErpObject> objects,
            List<String> warnings) {}
}
