package io.orphos.eip.proxy.component;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QueryParseException;
import org.apache.jena.sparql.core.Quad;
import org.apache.jena.sparql.modify.request.QuadDataAcc;
import org.apache.jena.sparql.modify.request.UpdateDataDelete;
import org.apache.jena.sparql.modify.request.UpdateDataInsert;
import org.apache.jena.sparql.modify.request.UpdateModify;
import org.apache.jena.sparql.syntax.Element;
import org.apache.jena.sparql.syntax.ElementNamedGraph;
import org.apache.jena.update.Update;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Rewrites incoming SPARQL so it runs only against a single tenant's named graph.
 *
 * <p>Used only for SHARED-mode tenants. DEDICATED-mode tenants get their own
 * Fuseki dataset and need no query rewriting — see {@link SparqlController}
 * for the routing logic.</p>
 *
 * <p>Strategy: parse the query via Jena ARQ, strip ANY existing {@code FROM}
 * and {@code FROM NAMED} clauses (defense against an attacker submitting
 * {@code SELECT ?s FROM <urn:eip:tenant:OTHER> WHERE { ... }} to override the
 * scope we inject), then add a single {@code FROM <urn:eip:tenant:{uuid}>}
 * pointing at the calling tenant's graph. Re-serialize and return.</p>
 *
 * <p>Supports {@code SELECT}, {@code CONSTRUCT}, {@code DESCRIBE}, {@code ASK}
 * for reads. For writes, supports {@code INSERT DATA}, {@code DELETE DATA},
 * and {@code INSERT/DELETE ... WHERE} via {@link #rewriteUpdateForTenant} —
 * every produced quad is forced into {@code <urn:eip:tenant:{uuid}>}, any
 * user-supplied default graph is overridden, and the {@code WHERE} pattern
 * is wrapped in an {@code ElementNamedGraph} so reads inside the update only
 * see the tenant's data. Graph-level operations
 * ({@code CREATE}/{@code DROP}/{@code CLEAR}/{@code LOAD}/{@code ADD}/
 * {@code MOVE}/{@code COPY}) remain rejected — they treat the named graph
 * itself as a resource, which crosses the tenant boundary by definition.</p>
 */
@Component
public class SparqlTenantRewriter {

    /** URN scheme for tenant-scoped named graphs. Stable; do NOT change. */
    public static final String TENANT_GRAPH_URN_PREFIX = "urn:eip:tenant:";

    /**
     * Rewrites SPARQL — read or update — to scope it to the tenant's named graph.
     *
     * @return the rewritten SPARQL string
     * @throws CrossTenantUpdateRejectedException if the input is a graph-level
     *         SPARQL Update operation ({@code CREATE}/{@code DROP}/{@code LOAD}/etc.)
     * @throws InvalidSparqlException             if Jena can't parse the input
     */
    public String rewriteForTenant(String sparql, UUID tenantId) {
        if (sparql == null || sparql.isBlank()) {
            throw new InvalidSparqlException("empty SPARQL request body");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }

        // Probe for an update first. UpdateFactory throws if the input is a
        // query; Query parsing throws if it's an update. Use a cheap pre-check
        // on the first non-comment, non-PREFIX keyword.
        if (looksLikeUpdate(sparql)) {
            try {
                UpdateRequest update = UpdateFactory.create(sparql);
                return rewriteUpdateForTenant(update, tenantId);
            } catch (QueryParseException ignored) {
                // False positive — fall through to read-path parsing.
            }
        }

        Query query;
        try {
            query = QueryFactory.create(sparql);
        } catch (QueryParseException e) {
            throw new InvalidSparqlException("Could not parse SPARQL: " + e.getMessage(), e);
        }

        // Strip any user-supplied FROM / FROM NAMED — these are the attack surface.
        query.getGraphURIs().clear();
        query.getNamedGraphURIs().clear();

        // Pin the dataset to the tenant's named graph as the DEFAULT graph.
        // FROM (not FROM NAMED) means: "treat this named graph as the default
        // graph for this query", so { ?s ?p ?o } matches inside it.
        query.addGraphURI(TENANT_GRAPH_URN_PREFIX + tenantId);

        return query.serialize();
    }

    /**
     * Rewrites a parsed SPARQL Update so every produced quad lands in the
     * tenant's named graph and every read inside a {@code DELETE/INSERT WHERE}
     * sees only the tenant's graph. The original {@link UpdateRequest} is
     * NOT mutated; a fresh one is built and returned as serialized text.
     */
    private String rewriteUpdateForTenant(UpdateRequest input, UUID tenantId) {
        Node tenantGraph = NodeFactory.createURI(TENANT_GRAPH_URN_PREFIX + tenantId);
        UpdateRequest out = new UpdateRequest();
        // Preserve PREFIX / BASE declarations from the original — they affect
        // any IRIs the user wrote in shorthand form.
        out.setPrefixMapping(input.getPrefixMapping());
        if (input.getBaseURI() != null) out.setBaseURI(input.getBaseURI());

        for (Update upd : input.getOperations()) {
            if (upd instanceof UpdateDataInsert ins) {
                QuadDataAcc acc = new QuadDataAcc();
                for (Quad q : ins.getQuads()) {
                    acc.addQuad(Quad.create(tenantGraph,
                            q.getSubject(), q.getPredicate(), q.getObject()));
                }
                out.add(new UpdateDataInsert(acc));
            } else if (upd instanceof UpdateDataDelete del) {
                QuadDataAcc acc = new QuadDataAcc();
                for (Quad q : del.getQuads()) {
                    acc.addQuad(Quad.create(tenantGraph,
                            q.getSubject(), q.getPredicate(), q.getObject()));
                }
                out.add(new UpdateDataDelete(acc));
            } else if (upd instanceof UpdateModify mod) {
                UpdateModify rewritten = new UpdateModify();
                // INSERT template — force every quad's graph
                for (Quad q : mod.getInsertQuads()) {
                    rewritten.getInsertAcc().addQuad(Quad.create(tenantGraph,
                            q.getSubject(), q.getPredicate(), q.getObject()));
                }
                // DELETE template — same
                for (Quad q : mod.getDeleteQuads()) {
                    rewritten.getDeleteAcc().addQuad(Quad.create(tenantGraph,
                            q.getSubject(), q.getPredicate(), q.getObject()));
                }
                // WHERE — wrap in ElementNamedGraph so all reads inside this
                // update see only the tenant graph. User-supplied USING /
                // USING NAMED clauses are intentionally dropped.
                Element where = mod.getWherePattern();
                rewritten.setElement(new ElementNamedGraph(tenantGraph, where));
                out.add(rewritten);
            } else {
                throw new CrossTenantUpdateRejectedException(
                        "SPARQL Update operation " + upd.getClass().getSimpleName() +
                        " is not allowed for SHARED-mode tenants — it manipulates graphs " +
                        "as resources, which crosses the tenant boundary.");
            }
        }
        return out.toString();
    }

    /**
     * Cheap classifier: returns true if the query body begins with an
     * Update keyword. Skips line comments and leading whitespace. False
     * positives are harmless (we then call UpdateFactory.create which throws
     * QueryParseException for non-updates and we fall through).
     */
    private static boolean looksLikeUpdate(String sparql) {
        int i = 0;
        int n = sparql.length();
        while (i < n) {
            char c = sparql.charAt(i);
            if (Character.isWhitespace(c)) { i++; continue; }
            if (c == '#') {
                // skip a line comment
                while (i < n && sparql.charAt(i) != '\n') i++;
                continue;
            }
            // Skip PREFIX / BASE declarations — they can appear before either reads or updates.
            String upper = sparql.substring(i).toUpperCase();
            if (upper.startsWith("PREFIX") || upper.startsWith("BASE")) {
                // advance to end of this clause; cheap approach is to next newline
                while (i < n && sparql.charAt(i) != '\n') i++;
                continue;
            }
            // First real keyword reached.
            return upper.startsWith("INSERT")
                || upper.startsWith("DELETE")
                || upper.startsWith("LOAD")
                || upper.startsWith("CLEAR")
                || upper.startsWith("DROP")
                || upper.startsWith("CREATE")
                || upper.startsWith("ADD")
                || upper.startsWith("MOVE")
                || upper.startsWith("COPY")
                || upper.startsWith("WITH");
        }
        return false;
    }

    /** Thrown when the input SPARQL is syntactically invalid — surfaces as HTTP 400. */
    public static class InvalidSparqlException extends RuntimeException {
        public InvalidSparqlException(String message) { super(message); }
        public InvalidSparqlException(String message, Throwable cause) { super(message, cause); }
    }

    /** Thrown when a SHARED-mode tenant submits an UPDATE — surfaces as HTTP 400. */
    public static class CrossTenantUpdateRejectedException extends RuntimeException {
        public CrossTenantUpdateRejectedException(String message) { super(message); }
    }
}
