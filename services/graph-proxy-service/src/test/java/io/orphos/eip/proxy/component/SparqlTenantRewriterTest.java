package io.orphos.eip.proxy.component;

import io.orphos.eip.proxy.component.SparqlTenantRewriter.CrossTenantUpdateRejectedException;
import io.orphos.eip.proxy.component.SparqlTenantRewriter.InvalidSparqlException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Security-critical: every test here corresponds to a potential cross-tenant
 * data leakage path. We use the public {@link SparqlTenantRewriter} API
 * exclusively — no reflection — so the tests double as documentation of the
 * contract.
 *
 * <p>The rewritten query is checked for two things on every case:
 * <ol>
 *   <li>The tenant graph URI is present (proves we scope to the right graph).</li>
 *   <li>No OTHER graph URI is present that could leak data — verified by
 *       counting {@code FROM &lt;...&gt;} occurrences and asserting they all
 *       point at the tenant URN.</li>
 * </ol>
 */
class SparqlTenantRewriterTest {

    private SparqlTenantRewriter rewriter;
    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        rewriter = new SparqlTenantRewriter();
        tenantA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        tenantB = UUID.fromString("22222222-2222-2222-2222-222222222222");
    }

    // ── Basic input validation ────────────────────────────────────────────

    @Test
    void rejectsBlankSparql() {
        assertThatThrownBy(() -> rewriter.rewriteForTenant("", tenantA))
                .isInstanceOf(InvalidSparqlException.class)
                .hasMessageContaining("empty");
    }

    @Test
    void rejectsNullSparql() {
        assertThatThrownBy(() -> rewriter.rewriteForTenant(null, tenantA))
                .isInstanceOf(InvalidSparqlException.class);
    }

    @Test
    void rejectsNullTenant() {
        assertThatThrownBy(() -> rewriter.rewriteForTenant("SELECT * WHERE { ?s ?p ?o }", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenantId");
    }

    @Test
    void rejectsInvalidSparql() {
        assertThatThrownBy(() -> rewriter.rewriteForTenant("THIS IS NOT SPARQL", tenantA))
                .isInstanceOf(InvalidSparqlException.class);
    }

    // ── SELECT/ASK/CONSTRUCT/DESCRIBE happy paths ─────────────────────────

    @Nested
    @DisplayName("Read queries: SELECT/ASK/CONSTRUCT/DESCRIBE")
    class ReadQueries {

        @Test
        void selectGetsTenantFromClause() {
            String rewritten = rewriter.rewriteForTenant(
                    "SELECT ?s WHERE { ?s ?p ?o }", tenantA);
            assertThat(rewritten).contains(expectedFrom(tenantA));
            assertOnlyTenantFrom(rewritten, tenantA);
        }

        @Test
        void askGetsTenantFromClause() {
            String rewritten = rewriter.rewriteForTenant(
                    "ASK { ?s ?p ?o }", tenantA);
            assertThat(rewritten).contains(expectedFrom(tenantA));
            assertOnlyTenantFrom(rewritten, tenantA);
        }

        @Test
        void constructGetsTenantFromClause() {
            String rewritten = rewriter.rewriteForTenant(
                    "CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }", tenantA);
            assertThat(rewritten).contains(expectedFrom(tenantA));
            assertOnlyTenantFrom(rewritten, tenantA);
        }

        @Test
        void describeGetsTenantFromClause() {
            String rewritten = rewriter.rewriteForTenant(
                    "DESCRIBE ?s WHERE { ?s ?p ?o }", tenantA);
            assertThat(rewritten).contains(expectedFrom(tenantA));
            assertOnlyTenantFrom(rewritten, tenantA);
        }

        @Test
        void preservesPrefixDeclarations() {
            String rewritten = rewriter.rewriteForTenant(
                    "PREFIX foaf: <http://xmlns.com/foaf/0.1/> " +
                    "SELECT ?p WHERE { ?p foaf:name ?n }", tenantA);
            assertThat(rewritten).contains("foaf:");
            assertThat(rewritten).contains(expectedFrom(tenantA));
        }
    }

    // ── FROM injection defense ────────────────────────────────────────────

    @Nested
    @DisplayName("FROM / FROM NAMED override defense")
    class OverrideDefense {

        @Test
        void stripsAttackerSuppliedFrom() {
            // Attacker tries to scope the query to a different tenant's graph.
            // Our rewriter MUST strip this and replace with tenantA's graph.
            String malicious = "SELECT ?s FROM <urn:eip:tenant:" + tenantB + "> " +
                               "WHERE { ?s ?p ?o }";

            String rewritten = rewriter.rewriteForTenant(malicious, tenantA);

            assertThat(rewritten).contains(expectedFrom(tenantA));
            assertThat(rewritten).doesNotContain("urn:eip:tenant:" + tenantB);
        }

        @Test
        void stripsAttackerSuppliedFromNamed() {
            String malicious = "SELECT ?s FROM NAMED <urn:eip:tenant:" + tenantB + "> " +
                               "WHERE { ?s ?p ?o }";
            String rewritten = rewriter.rewriteForTenant(malicious, tenantA);

            assertThat(rewritten).doesNotContain("urn:eip:tenant:" + tenantB);
            assertThat(rewritten).contains(expectedFrom(tenantA));
        }

        @Test
        void stripsMultipleAttackerFroms() {
            String malicious =
                "SELECT ?s " +
                "FROM <urn:eip:tenant:" + tenantB + "> " +
                "FROM <http://evil.example/all> " +
                "WHERE { ?s ?p ?o }";
            String rewritten = rewriter.rewriteForTenant(malicious, tenantA);

            assertThat(rewritten).doesNotContain("urn:eip:tenant:" + tenantB);
            assertThat(rewritten).doesNotContain("evil.example");
            assertThat(rewritten).contains(expectedFrom(tenantA));
        }
    }

    // ── SPARQL Update: DATA forms ─────────────────────────────────────────

    @Nested
    @DisplayName("INSERT DATA / DELETE DATA rewriting")
    class DataForms {

        @Test
        void insertDataLandsInTenantGraph() {
            String rewritten = rewriter.rewriteForTenant(
                    "INSERT DATA { <urn:s> <urn:p> \"value\" }", tenantA);
            assertThat(rewritten).contains("GRAPH <urn:eip:tenant:" + tenantA + ">");
            assertThat(rewritten).contains("urn:s");
        }

        @Test
        void insertDataAttackerGraphIsOverridden() {
            // Attacker writes into another tenant's graph. Rewriter forces tenantA.
            String malicious =
                "INSERT DATA { " +
                "  GRAPH <urn:eip:tenant:" + tenantB + "> { <urn:s> <urn:p> \"poisoned\" } " +
                "}";
            String rewritten = rewriter.rewriteForTenant(malicious, tenantA);

            assertThat(rewritten).contains("GRAPH <urn:eip:tenant:" + tenantA + ">");
            assertThat(rewritten).doesNotContain("urn:eip:tenant:" + tenantB);
        }

        @Test
        void deleteDataLandsInTenantGraph() {
            String rewritten = rewriter.rewriteForTenant(
                    "DELETE DATA { <urn:s> <urn:p> \"value\" }", tenantA);
            assertThat(rewritten).contains("GRAPH <urn:eip:tenant:" + tenantA + ">");
        }

        @Test
        void deleteDataAttackerGraphIsOverridden() {
            String malicious =
                "DELETE DATA { " +
                "  GRAPH <urn:eip:tenant:" + tenantB + "> { <urn:s> <urn:p> \"x\" } " +
                "}";
            String rewritten = rewriter.rewriteForTenant(malicious, tenantA);

            assertThat(rewritten).contains("GRAPH <urn:eip:tenant:" + tenantA + ">");
            assertThat(rewritten).doesNotContain("urn:eip:tenant:" + tenantB);
        }
    }

    // ── SPARQL Update: INSERT/DELETE WHERE ────────────────────────────────

    @Nested
    @DisplayName("INSERT/DELETE … WHERE rewriting")
    class ModifyForms {

        @Test
        void insertWhereScopesTemplateAndWhere() {
            String rewritten = rewriter.rewriteForTenant(
                    "INSERT { ?s <urn:tagged> true } WHERE { ?s ?p ?o }", tenantA);
            // INSERT template lands in tenant graph
            assertThat(rewritten).contains("GRAPH <urn:eip:tenant:" + tenantA + ">");
            // WHERE pattern is wrapped in the tenant graph too (reads only see tenant data).
            // Jena's serializer uses variable whitespace; \\s+ between vars catches both
            // single-space and double-space output.
            assertThat(rewritten).containsPattern(
                    "GRAPH <urn:eip:tenant:" + tenantA + ">[\\s\\S]*\\?s\\s+\\?p\\s+\\?o");
        }

        @Test
        void deleteWhereScopesTemplateAndWhere() {
            String rewritten = rewriter.rewriteForTenant(
                    "DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }", tenantA);
            assertThat(rewritten).contains("GRAPH <urn:eip:tenant:" + tenantA + ">");
        }

        @Test
        void usingClausesAreDropped() {
            // USING is the UPDATE equivalent of FROM in queries — same attack surface.
            String malicious =
                "DELETE { ?s ?p ?o } " +
                "USING <urn:eip:tenant:" + tenantB + "> " +
                "WHERE { ?s ?p ?o }";
            String rewritten = rewriter.rewriteForTenant(malicious, tenantA);

            assertThat(rewritten).doesNotContain("urn:eip:tenant:" + tenantB);
            assertThat(rewritten).contains("urn:eip:tenant:" + tenantA);
        }
    }

    // ── Graph-level ops: rejected ─────────────────────────────────────────

    @Nested
    @DisplayName("Graph-level ops rejected with CrossTenantUpdateRejectedException")
    class GraphLevelOpsRejected {

        @Test
        void rejectsCreate() {
            assertThatThrownBy(() -> rewriter.rewriteForTenant(
                    "CREATE GRAPH <urn:attacker>", tenantA))
                    .isInstanceOf(CrossTenantUpdateRejectedException.class);
        }

        @Test
        void rejectsDrop() {
            assertThatThrownBy(() -> rewriter.rewriteForTenant(
                    "DROP GRAPH <urn:eip:tenant:" + tenantB + ">", tenantA))
                    .isInstanceOf(CrossTenantUpdateRejectedException.class);
        }

        @Test
        void rejectsClear() {
            assertThatThrownBy(() -> rewriter.rewriteForTenant(
                    "CLEAR GRAPH <urn:eip:tenant:" + tenantB + ">", tenantA))
                    .isInstanceOf(CrossTenantUpdateRejectedException.class);
        }

        @Test
        void rejectsLoad() {
            assertThatThrownBy(() -> rewriter.rewriteForTenant(
                    "LOAD <http://example/data> INTO GRAPH <urn:attacker>", tenantA))
                    .isInstanceOf(CrossTenantUpdateRejectedException.class);
        }

        @Test
        void rejectsAdd() {
            assertThatThrownBy(() -> rewriter.rewriteForTenant(
                    "ADD <urn:eip:tenant:" + tenantB + "> TO <urn:eip:tenant:" + tenantA + ">",
                    tenantA))
                    .isInstanceOf(CrossTenantUpdateRejectedException.class);
        }

        @Test
        void rejectsMove() {
            assertThatThrownBy(() -> rewriter.rewriteForTenant(
                    "MOVE <urn:eip:tenant:" + tenantB + "> TO <urn:eip:tenant:" + tenantA + ">",
                    tenantA))
                    .isInstanceOf(CrossTenantUpdateRejectedException.class);
        }

        @Test
        void rejectsCopy() {
            assertThatThrownBy(() -> rewriter.rewriteForTenant(
                    "COPY <urn:eip:tenant:" + tenantB + "> TO <urn:eip:tenant:" + tenantA + ">",
                    tenantA))
                    .isInstanceOf(CrossTenantUpdateRejectedException.class);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String expectedFrom(UUID tenantId) {
        return "urn:eip:tenant:" + tenantId;
    }

    /**
     * Asserts that the rewritten query's FROM clauses ALL point at the
     * expected tenant. Catches the subtle case where Jena might serialize
     * an attacker-supplied FROM somewhere we didn't expect.
     */
    private static void assertOnlyTenantFrom(String rewritten, UUID expected) {
        String marker = "urn:eip:tenant:";
        int idx = 0;
        while ((idx = rewritten.indexOf(marker, idx)) >= 0) {
            // Each occurrence of the tenant URN prefix MUST be followed by
            // exactly the expected UUID.
            String slice = rewritten.substring(idx, Math.min(idx + marker.length() + 36, rewritten.length()));
            assertThat(slice)
                    .as("FROM at offset %d points at wrong tenant: %s", idx, slice)
                    .contains(expected.toString());
            idx += marker.length();
        }
    }
}
