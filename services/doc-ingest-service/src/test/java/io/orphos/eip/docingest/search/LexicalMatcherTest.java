package io.orphos.eip.docingest.search;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression cover for the keyword gate that decides which chunks may enter the
 * candidate pool on a lexical match alone.
 *
 * <p>The scenario these tests encode is the one that broke: a corpus holding a
 * POSH policy and a social-media policy, where searching "POSH" returned the
 * social-media policy. Dense similarity cannot separate those two documents for
 * a four-letter query — measured cosine was 0.31 for the wrong document, with
 * every chunk in it within 0.16 of every other — so correctness rests entirely
 * on the gate below refusing a document that never mentions the term.</p>
 */
class LexicalMatcherTest {

    private static final double COVERAGE = 0.5;

    private static TextSegment chunk(String fileName, String title, String heading, String body) {
        Metadata meta = new Metadata();
        meta.put("file_name", fileName);
        meta.put("title", title);
        meta.put("section_heading", heading);
        meta.put("reference_number", "");
        return TextSegment.from(body, meta);
    }

    /** A POSH policy that spells the term out and only abbreviates it in its name. */
    private static TextSegment poshChunk() {
        return chunk("POSH-Policy.pdf", "", "Scope",
            "This policy applies to all employees, contractors and interns of the organization "
            + "and covers the prevention of sexual harassment at the workplace.");
    }

    /** The social-media policy — no POSH content anywhere in body, title or name. */
    private static TextSegment socialChunk() {
        return chunk("Social-Media-Policy.pdf", "", "Scope",
            "This Policy applies to all the employees of the organization.");
    }

    @Nested
    @DisplayName("the reported failure")
    class ReportedFailure {

        @Test
        @DisplayName("\"POSH\" matches the POSH policy through its file name")
        void poshMatchesPoshDocument() {
            List<String> q = LexicalMatcher.queryTokens("POSH", false);
            assertTrue(LexicalMatcher.covers(
                LexicalMatcher.searchableText(poshChunk()), q, false, COVERAGE),
                "the POSH policy must match its own name even though the body never says \"POSH\"");
        }

        @Test
        @DisplayName("\"POSH\" does not match the social media policy")
        void poshDoesNotMatchSocialMediaDocument() {
            List<String> q = LexicalMatcher.queryTokens("POSH", false);
            assertFalse(LexicalMatcher.covers(
                LexicalMatcher.searchableText(socialChunk()), q, false, COVERAGE),
                "a document with no POSH content must not be admitted on a lexical match");
        }

        @Test
        @DisplayName("\"Social Media Policy\" matches the social media policy")
        void socialMediaMatchesItsOwnDocument() {
            List<String> q = LexicalMatcher.queryTokens("Social Media Policy", false);
            assertTrue(LexicalMatcher.covers(
                LexicalMatcher.searchableText(socialChunk()), q, false, COVERAGE));
        }

        @Test
        @DisplayName("\"Social Media Policy\" is not admitted by the POSH policy sharing only \"policy\"")
        void sharedGenericWordDoesNotAdmitUnrelatedDocument() {
            List<String> q = LexicalMatcher.queryTokens("Social Media Policy", false);
            assertFalse(LexicalMatcher.covers(
                LexicalMatcher.searchableText(poshChunk()), q, false, COVERAGE),
                "one shared generic term must not clear the gate — that is what makes it a gate");
        }
    }

    @Nested
    @DisplayName("discriminative terms")
    class Discriminative {

        /** Both documents, as the gate sees them. */
        private List<String> pool() {
            return List.of(
                LexicalMatcher.searchableText(poshChunk()),
                LexicalMatcher.searchableText(socialChunk()));
        }

        @Test
        @DisplayName("\"policy\" is dropped because every document has it")
        void dropsUbiquitousTerm() {
            List<String> tokens = LexicalMatcher.discriminative(
                LexicalMatcher.queryTokens("POSH Policy", false), pool(), false, 0.8);
            assertEquals(List.of("posh"), tokens);
        }

        @Test
        @DisplayName("so \"POSH Policy\" no longer admits the social media policy")
        void poshPolicyDoesNotAdmitSocialMedia() {
            List<String> tokens = LexicalMatcher.discriminative(
                LexicalMatcher.queryTokens("POSH Policy", false), pool(), false, 0.8);
            assertTrue(LexicalMatcher.covers(
                LexicalMatcher.searchableText(poshChunk()), tokens, false, COVERAGE));
            assertFalse(LexicalMatcher.covers(
                LexicalMatcher.searchableText(socialChunk()), tokens, false, COVERAGE),
                "\"policy\" must not be enough to pull in an unrelated document");
        }

        @Test
        @DisplayName("\"Social Media Policy\" keeps its distinguishing terms")
        void keepsDistinguishingTerms() {
            List<String> tokens = LexicalMatcher.discriminative(
                LexicalMatcher.queryTokens("Social Media Policy", false), pool(), false, 0.8);
            assertTrue(tokens.contains("social"));
            assertTrue(tokens.contains("media"));
            assertFalse(tokens.contains("policy"));
        }

        @Test
        @DisplayName("when every term is common, the query is left intact rather than emptied")
        void allCommonFallsBack() {
            List<String> tokens = LexicalMatcher.discriminative(
                LexicalMatcher.queryTokens("policy", false), pool(), false, 0.8);
            assertEquals(List.of("policy"), tokens,
                "a gate that can never open is worse than a blunt one");
        }

        @Test
        @DisplayName("a pool too small to measure frequency is left intact")
        void tinyPoolFallsBack() {
            List<String> q = LexicalMatcher.queryTokens("POSH Policy", false);
            assertEquals(q, LexicalMatcher.discriminative(q, List.of("anything"), false, 0.8));
            assertEquals(q, LexicalMatcher.discriminative(q, List.of(), false, 0.8));
        }

        @Test
        @DisplayName("an out-of-range ratio falls back to the 0.8 default")
        void invalidRatioFallsBack() {
            List<String> q = LexicalMatcher.queryTokens("POSH Policy", false);
            for (double bad : new double[]{0.0, -1.0, 4.2, Double.NaN}) {
                assertEquals(List.of("posh"), LexicalMatcher.discriminative(q, pool(), false, bad),
                    "ratio " + bad + " should behave like the default");
            }
        }
    }

    @Nested
    @DisplayName("searchable text")
    class SearchableText {

        @Test
        @DisplayName("folds in document identity, not just the chunk body")
        void includesIdentityFields() {
            String text = LexicalMatcher.searchableText(
                chunk("POSH-Policy.pdf", "Prevention of Sexual Harassment", "Scope", "Body text."));
            assertTrue(text.contains("Prevention of Sexual Harassment"), "title");
            assertTrue(text.contains("POSH-Policy"), "file name");
            assertTrue(text.contains("Scope"), "section heading");
            assertTrue(text.contains("Body text."), "body");
        }

        @Test
        @DisplayName("drops the file extension so \"pdf\" cannot match every document")
        void dropsExtension() {
            String text = LexicalMatcher.searchableText(socialChunk());
            assertFalse(text.contains(".pdf"));
            assertFalse(LexicalMatcher.covers(
                text, LexicalMatcher.queryTokens("pdf", false), false, COVERAGE));
        }

        @Test
        @DisplayName("tolerates a segment with no metadata")
        void handlesBareSegment() {
            assertEquals("just a body", LexicalMatcher.searchableText(TextSegment.from("just a body")));
            assertEquals("", LexicalMatcher.searchableText(null));
        }
    }

    @Nested
    @DisplayName("query tokens")
    class QueryTokens {

        @Test
        @DisplayName("lowercases and de-duplicates by default")
        void lowercasesAndDeduplicates() {
            assertEquals(List.of("posh"), LexicalMatcher.queryTokens("POSH posh Posh", false));
        }

        @Test
        @DisplayName("drops single-character Latin and digit tokens")
        void dropsSingleLatinCharacters() {
            assertFalse(LexicalMatcher.queryTokens("a 1 posh", false).contains("a"));
            assertFalse(LexicalMatcher.queryTokens("a 1 posh", false).contains("1"));
            assertTrue(LexicalMatcher.queryTokens("a 1 posh", false).contains("posh"));
        }

        @Test
        @DisplayName("segments Japanese, where a one-character token is a real word")
        void segmentsJapanese() {
            List<String> tokens = LexicalMatcher.queryTokens("日本語のテスト", false);
            assertTrue(tokens.contains("日本語"), "expected morphological segmentation, got " + tokens);
            assertTrue(tokens.contains("テスト"), "expected morphological segmentation, got " + tokens);
        }

        @Test
        @DisplayName("an empty query yields no tokens and never clears the gate")
        void emptyQuery() {
            assertTrue(LexicalMatcher.queryTokens("", false).isEmpty());
            assertFalse(LexicalMatcher.covers("anything at all", List.of(), false, COVERAGE));
        }
    }

    @Nested
    @DisplayName("coverage threshold")
    class Coverage {

        private static final List<String> THREE = List.of("alpha", "beta", "gamma");

        @Test
        @DisplayName("half the query's terms is enough at the 0.5 default")
        void halfIsEnough() {
            assertTrue(LexicalMatcher.covers("alpha beta only", THREE, false, 0.5));
        }

        @Test
        @DisplayName("a single term out of three is not")
        void oneOfThreeIsNot() {
            assertFalse(LexicalMatcher.covers("alpha only", THREE, false, 0.5));
        }

        @Test
        @DisplayName("a ratio of 1.0 demands every term")
        void fullCoverage() {
            assertTrue(LexicalMatcher.covers("alpha beta gamma", THREE, false, 1.0));
            assertFalse(LexicalMatcher.covers("alpha beta", THREE, false, 1.0));
        }

        @Test
        @DisplayName("an out-of-range ratio falls back to the 0.5 default rather than opening the gate")
        void invalidRatioFallsBack() {
            assertFalse(LexicalMatcher.covers("alpha only", THREE, false, 0.0));
            assertFalse(LexicalMatcher.covers("alpha only", THREE, false, -1.0));
            assertFalse(LexicalMatcher.covers("alpha only", THREE, false, 7.5));
            assertFalse(LexicalMatcher.covers("alpha only", THREE, false, Double.NaN));
        }

        @Test
        @DisplayName("case sensitivity is honoured when requested")
        void caseSensitivity() {
            List<String> exact = LexicalMatcher.queryTokens("POSH", true);
            assertTrue(LexicalMatcher.covers("POSH-Policy", exact, true, COVERAGE));
            assertFalse(LexicalMatcher.covers("posh-policy", exact, true, COVERAGE));
        }

        @Test
        @DisplayName("blank text never clears the gate")
        void blankText() {
            assertFalse(LexicalMatcher.covers("", THREE, false, COVERAGE));
            assertFalse(LexicalMatcher.covers(null, THREE, false, COVERAGE));
        }
    }
}
