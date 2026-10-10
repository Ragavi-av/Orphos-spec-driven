package io.orphos.eip.reasoning.service;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.reasoning.component.OntologyService;
import io.orphos.eip.reasoning.service.TenantInfoCache;
import io.orphos.eip.reasoning.vo.TenantRoutingInfo;
import org.apache.jena.query.*;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads back the current tenant's reasoning output from Fuseki — the
 * {@code OverdueIssue}/{@code BlockedIssue}/{@code OverloadedPerson} facts
 * {@link ReasoningService} last wrote — scoped to the tenant's graph exactly
 * the way graph-proxy-service's {@code SparqlTenantRewriter} already scopes
 * every other SPARQL query in this platform.
 */
@Service
public class InsightsQueryService {

    private static final String NS = OntologyService.NS;
    private static final String TENANT_GRAPH_URN_PREFIX = "urn:eip:tenant:";

    private final TenantInfoCache tenantInfo;
    private final String fusekiBaseUrl;

    public InsightsQueryService(TenantInfoCache tenantInfo,
                                 @Value("${fuseki.base-url:http://eip-fuseki:3030}") String fusekiBaseUrl) {
        this.tenantInfo = tenantInfo;
        this.fusekiBaseUrl = stripTrailingSlash(fusekiBaseUrl);
    }

    public record Insight(String uri, String type, String displayLabel) {}

    public List<Insight> currentTenantInsights() {
        var tenantId = TenantContextHolder.requireTenantId();
        TenantRoutingInfo routing = tenantInfo.find(tenantId)
                .filter(TenantRoutingInfo::active)
                .orElseThrow(() -> new IllegalStateException("Cannot resolve routing for " + tenantId));

        String datasetUrl = fusekiBaseUrl + "/" + routing.dataset();
        String fromClause = routing.isolationMode() == TenantRoutingInfo.IsolationMode.SHARED
                ? "FROM <" + TENANT_GRAPH_URN_PREFIX + tenantId + ">"
                : "";

        String sparql = """
                PREFIX eip: <%s>
                PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>
                SELECT ?s ?type (COALESCE(?summary, ?displayName, STR(?s)) AS ?label)
                %s
                WHERE {
                  ?s rdf:type ?type .
                  FILTER(?type IN (eip:OverdueIssue, eip:BlockedIssue, eip:OverloadedPerson))
                  OPTIONAL { ?s eip:summary ?summary }
                  OPTIONAL { ?s eip:displayName ?displayName }
                }
                """.formatted(NS, fromClause);

        List<Insight> out = new ArrayList<>();
        try (RDFConnection conn = RDFConnectionRemote.newBuilder().destination(datasetUrl).build()) {
            try (QueryExecution qe = conn.query(sparql)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution sol = rs.next();
                    out.add(new Insight(
                            sol.getResource("s").getURI(),
                            localName(sol.getResource("type").getURI()),
                            sol.getLiteral("label").getString()));
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not query insights from Fuseki: " + e.getMessage(), e);
        }
        return out;
    }

    /** Insights grouped by class name, for a slightly friendlier admin/API shape. */
    public Map<String, List<Insight>> currentTenantInsightsByType() {
        Map<String, List<Insight>> grouped = new LinkedHashMap<>();
        for (Insight i : currentTenantInsights()) {
            grouped.computeIfAbsent(i.type(), k -> new ArrayList<>()).add(i);
        }
        return grouped;
    }

    private static String localName(String uri) {
        int hash = uri.lastIndexOf('#');
        return hash < 0 ? uri : uri.substring(hash + 1);
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
