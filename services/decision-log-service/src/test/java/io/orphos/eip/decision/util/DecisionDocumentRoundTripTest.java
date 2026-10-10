package io.orphos.eip.decision.util;

import io.orphos.eip.decision.exception.DecisionParseException;
import io.orphos.eip.decision.vo.DecisionDocument;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Req 12.4 round-trip property: for all valid Decision objects,
 * parse(format(parse(doc))) produces an object equal to parse(doc) —
 * plus the error-location contract of Req 12.2.
 */
class DecisionDocumentRoundTripTest {

    private final DecisionDocumentParser parser = new DecisionDocumentParser();
    private final DecisionFormatter formatter = new DecisionFormatter();

    private DecisionDocument fullDocument() {
        return new DecisionDocument(
            "Adopt ArcadeDB as primary graph store",
            "APPROVED", "IT", "Platform Engineering",
            "HIGH", "STRATEGIC", "HIGH",
            "CTO", "2026-07-01T09:00:00Z", "2026-09-30",
            List.of("database", "architecture"),
            "Replace the legacy store with ArcadeDB across all services.\n\nPhased rollout per service.",
            "Lower licensing cost and one engine for graph + document workloads.",
            List.of(new DecisionDocument.Person("Platform Lead", "Engineering", "DECIDER"),
                    new DecisionDocument.Person("Finance Partner", "Finance", "CONTRIBUTOR")),
            List.of(new DecisionDocument.Area("DEPARTMENT", "Engineering"),
                    new DecisionDocument.Area("POLICY", "Data Retention Policy")),
            List.of(new DecisionDocument.Milestone("Migrate first service", "2026-08-01", "PENDING"),
                    new DecisionDocument.Milestone("Full rollout", "2026-09-30", "PENDING")));
    }

    @Test
    void markdownRoundTripIsLossless() {
        DecisionDocument original = fullDocument();
        String markdown = formatter.toMarkdown(original);
        DecisionDocument reparsed = parser.parse(markdown).document();
        assertEquals(original, reparsed);
        // and it is stable on a second pass: parse(format(parse(doc))) == parse(doc)
        assertEquals(reparsed, parser.parse(formatter.toMarkdown(reparsed)).document());
    }

    @Test
    void jsonRoundTripIsLossless() {
        DecisionDocument original = fullDocument();
        DecisionDocument reparsed = parser.parse(formatter.toJson(original)).document();
        assertEquals(original, reparsed);
    }

    @Test
    void minimalDocumentRoundTrips() {
        DecisionDocument minimal = new DecisionDocument("Only a title", null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, null);
        assertEquals(minimal, parser.parse(formatter.toMarkdown(minimal)).document());
    }

    @Test
    void normalizationMakesEquivalentInputsEqual() {
        DecisionDocument messy = new DecisionDocument("  Adopt   ArcadeDB as primary graph store ",
            "APPROVED", "IT", "Platform Engineering", "HIGH", "STRATEGIC", "HIGH",
            "CTO", "2026-07-01T09:00:00Z", "2026-09-30",
            List.of("database", " architecture "),
            "Replace the legacy store with ArcadeDB across all services.\r\n\r\nPhased rollout per service.\n",
            "Lower licensing cost and one engine for graph + document workloads.",
            fullDocument().involvedPeople(), fullDocument().affectedAreas(), fullDocument().milestones());
        assertEquals(fullDocument(), messy);
    }

    @Test
    void plainTextFallbackParses() {
        DecisionDocumentParser.ParseResult result =
            parser.parse("We approved the vendor switch.\nEffective next quarter.");
        assertEquals("We approved the vendor switch.", result.document().title());
        assertEquals("Effective next quarter.", result.document().description());
        assertTrue(result.warnings().stream().anyMatch(w -> w.line() == 1));
        // plain text also round-trips once canonicalized
        assertEquals(result.document(),
            parser.parse(formatter.toMarkdown(result.document())).document());
    }

    @Test
    void pipesInsideListFieldsAreNormalizedNotCorrupting() {
        DecisionDocument doc = new DecisionDocument("T", null, null, null, null, null, null,
            null, null, null, null, null, null,
            List.of(new DecisionDocument.Person("A | B", "role", "DECIDER")), null, null);
        assertEquals("A / B", doc.involvedPeople().get(0).name());
        assertEquals(doc, parser.parse(formatter.toMarkdown(doc)).document());
    }

    @Test
    void emptyDocumentFailsWithLocation() {
        DecisionParseException ex = assertThrows(DecisionParseException.class, () -> parser.parse("   "));
        assertEquals(0, ex.issues().get(0).line());
    }

    @Test
    void badMetadataLineReportsLineNumber() {
        String bad = "# Decision: Title\n\n- Status APPROVED\n";   // missing ':'
        DecisionParseException ex = assertThrows(DecisionParseException.class, () -> parser.parse(bad));
        assertEquals(3, ex.issues().get(0).line());
        assertTrue(ex.issues().get(0).message().contains("':'"));
    }

    @Test
    void badListEntryReportsLineNumber() {
        String bad = "# Decision: Title\n\n## Involved People\n- a | b | c | d\n";
        DecisionParseException ex = assertThrows(DecisionParseException.class, () -> parser.parse(bad));
        assertEquals(4, ex.issues().get(0).line());
    }

    @Test
    void invalidJsonReportsDescriptiveError() {
        DecisionParseException ex = assertThrows(DecisionParseException.class,
            () -> parser.parse("{\"title\": \"x\", }"));
        assertTrue(ex.issues().get(0).message().startsWith("Invalid JSON"));
    }

    @Test
    void jsonWithoutTitleFails() {
        DecisionParseException ex = assertThrows(DecisionParseException.class,
            () -> parser.parse("{\"status\": \"PENDING\"}"));
        assertTrue(ex.issues().get(0).message().contains("title"));
    }

    @Test
    void unknownSectionIsWarnedAndIgnored() {
        String md = "# Decision: T\n\n## Nonsense\nblah\n\n## Description\nreal\n";
        DecisionDocumentParser.ParseResult result = parser.parse(md);
        assertEquals("real", result.document().description());
        assertNull(result.document().rationale());
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("Nonsense")));
    }
}
