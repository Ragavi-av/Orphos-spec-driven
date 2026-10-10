package io.orphos.eip.reasoning.repository;

import io.orphos.eip.reasoning.vo.TenantRoutingInfo;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdfconnection.RDFConnection;
import org.apache.jena.rdfconnection.RDFConnectionRemote;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

/**
 * Writes a reasoning cycle's output model to Fuseki, honouring the same
 * per-tenant partitioning convention graph-proxy-service already reads with
 * ({@code SparqlTenantRewriter.TENANT_GRAPH_URN_PREFIX}) so results are
 * immediately visible to graph-proxy's tenant-scoped SPARQL queries:
 *
 * <ul>
 *   <li><b>SHARED</b> tenants: written into the shared {@code orphos} Fuseki
 *       dataset, under the named graph {@code urn:eip:tenant:{tenantId}}.</li>
 *   <li><b>DEDICATED</b> tenants: written into their own Fuseki dataset
 *       (named the same as their {@code arcadeDatabase}), into its default
 *       graph — the whole dataset is already exclusively theirs, so no named
 *       graph is needed there.</li>
 * </ul>
 *
 * <p>Uses {@code RDFConnection.put(...)} (GSP PUT — replace) rather than
 * {@code load} (merge) so each cycle's output is a clean recomputation, not
 * an ever-growing accumulation of stale inferred triples from earlier cycles.</p>
 */
@Repository
public class FusekiWriter {

    private static final Logger log = LogManager.getLogger(FusekiWriter.class);
    private static final String TENANT_GRAPH_URN_PREFIX = "urn:eip:tenant:";

    private final String fusekiBaseUrl;

    public FusekiWriter(@Value("${fuseki.base-url:http://eip-fuseki:3030}") String fusekiBaseUrl) {
        this.fusekiBaseUrl = stripTrailingSlash(fusekiBaseUrl);
    }

    public void write(TenantRoutingInfo routing, Model model) {
        String datasetUrl = fusekiBaseUrl + "/" + routing.dataset();
        try (RDFConnection conn = RDFConnectionRemote.newBuilder()
                .destination(datasetUrl)
                .build()) {
            if (routing.isolationMode() == TenantRoutingInfo.IsolationMode.SHARED) {
                String graphUri = TENANT_GRAPH_URN_PREFIX + routing.tenantId();
                conn.put(graphUri, model);
                log.debug("Wrote {} statements to {} graph {}", model.size(), datasetUrl, graphUri);
            } else {
                conn.put(model);
                log.debug("Wrote {} statements to {} (default graph)", model.size(), datasetUrl);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Could not write reasoning output to Fuseki dataset "
                    + routing.dataset() + ": " + e.getMessage(), e);
        }
    }

    private static String stripTrailingSlash(String s) {
        if (s == null) return "";
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }
}
