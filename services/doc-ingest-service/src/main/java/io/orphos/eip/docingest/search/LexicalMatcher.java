package io.orphos.eip.docingest.search;

import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * The keyword half of document retrieval: what text a query is allowed to match
 * against, and whether a given chunk matches it literally.
 *
 * <p>Dense retrieval alone cannot answer "which document is the POSH policy".
 * A short, high-information string — an acronym, a circular number, a scheme
 * name — carries almost no distributional signal, so its embedding sits near the
 * centroid of the corpus and cosine similarity ranks chunks by how generic they
 * are rather than how relevant. Measured against bge-m3 on a two-document
 * corpus, the query "POSH" scored 0.31 against an unrelated social-media policy;
 * every chunk in that document scored within 0.16 of every other. Nothing in
 * that ordering is meaningful.</p>
 *
 * <p>What rescues those queries is an exact match, and the text worth matching
 * against includes the document's identity — its title, file name and section
 * headings — not just the prose of the chunk. Policy documents are habitually
 * referred to by names that never appear in their own body text.</p>
 *
 * <p>Pure functions over text; no Spring, no I/O.</p>
 */
public final class LexicalMatcher {

    private LexicalMatcher() {}

    /**
     * The text a keyword match may see for one chunk: the document's identity
     * followed by the chunk body.
     *
     * <p>Search-only — the stored segment text is never modified, so results
     * render exactly as they were ingested and no synthetic header leaks into
     * the UI or into a citation.</p>
     */
    public static String searchableText(TextSegment seg) {
        if (seg == null) return "";
        var meta = seg.metadata();
        StringBuilder sb = new StringBuilder();
        append(sb, get(meta, "title"));
        append(sb, stripExtension(get(meta, "file_name")));
        append(sb, get(meta, "reference_number"));
        append(sb, get(meta, "section_heading"));
        append(sb, seg.text());
        return sb.toString();
    }

    /**
     * The query's distinct content tokens, via the same bilingual tokenizer BM25
     * uses so the gate and the ranking agree on what a word is.
     *
     * <p>Single-character Latin and digit tokens are dropped: they match almost
     * any text and would turn the gate into a pass-through. Single-character CJK
     * tokens are kept — a lone kanji is a whole word.</p>
     */
    public static List<String> queryTokens(String query, boolean caseSensitive) {
        List<String> out = new ArrayList<>();
        for (String t : JapaneseTextTokenizer.tokenize(query, caseSensitive)) {
            if (t.isBlank()) continue;
            if (t.length() < 2 && t.codePointAt(0) < 0x3000) continue;
            if (!out.contains(t)) out.add(t);
        }
        return out;
    }

    /**
     * Narrows {@code queryTokens} to the ones that actually distinguish one
     * candidate from another, by dropping any term present in more than
     * {@code maxDocFreqRatio} of the pool.
     *
     * <p>Coverage alone is too blunt on short queries. "POSH Policy" tokenises to
     * two terms, so a half-coverage rule is satisfied by "policy" — a word every
     * document in a policy corpus contains — and the gate admits the whole
     * library. Term frequency across the pool is what separates the term the user
     * meant from the term they merely used: "posh" appears in one document,
     * "policy" in all of them, so only "posh" gets a vote.</p>
     *
     * <p>If every term turns out to be common (the query really is just
     * "policy"), the full token list is returned rather than an empty one — a
     * gate that can never open is worse than a blunt one, and the semantic floor
     * is still there to do the filtering.</p>
     *
     * @param poolTexts searchable text of every candidate under consideration
     */
    public static List<String> discriminative(List<String> queryTokens, List<String> poolTexts,
                                              boolean caseSensitive, double maxDocFreqRatio) {
        if (queryTokens == null || queryTokens.isEmpty()) return List.of();
        if (poolTexts == null || poolTexts.size() < 2) return queryTokens;

        double cutoff = (Double.isNaN(maxDocFreqRatio) || maxDocFreqRatio <= 0 || maxDocFreqRatio > 1.0)
            ? 0.8 : maxDocFreqRatio;

        List<String> hays = new ArrayList<>(poolTexts.size());
        for (String t : poolTexts) hays.add(t == null ? "" : (caseSensitive ? t : t.toLowerCase()));

        List<String> kept = new ArrayList<>();
        for (String token : queryTokens) {
            int df = 0;
            for (String hay : hays) if (hay.contains(token)) df++;
            if ((double) df / hays.size() <= cutoff) kept.add(token);
        }
        return kept.isEmpty() ? queryTokens : kept;
    }

    /**
     * True when at least {@code requiredRatio} of the query's distinct content
     * tokens appear literally in {@code text}.
     *
     * <p>This gate is what lets a chunk in despite a dense score below the
     * semantic floor, so it has to mean something. Requiring a fraction of the
     * whole query rather than any single token is the load-bearing detail: with
     * an any-token rule, one shared generic word ("policy") admits every document
     * in the corpus and the gate stops discriminating. At the 0.5 default, the
     * one-word query "POSH" needs its single term present, while "Social Media
     * Policy" needs two of its three — which the social-media policy has and an
     * unrelated document sharing only "policy" does not.</p>
     *
     * @param requiredRatio fraction in (0,1]; out-of-range values fall back to 0.5
     */
    public static boolean covers(String text, List<String> queryTokens,
                                 boolean caseSensitive, double requiredRatio) {
        if (queryTokens == null || queryTokens.isEmpty()) return false;
        if (text == null || text.isBlank()) return false;
        String hay = caseSensitive ? text : text.toLowerCase();
        int hits = 0;
        for (String t : queryTokens) if (hay.contains(t)) hits++;
        int needed = (int) Math.max(1, Math.ceil(queryTokens.size() * ratio(requiredRatio)));
        return hits >= needed;
    }

    private static double ratio(double v) {
        if (Double.isNaN(v) || v <= 0 || v > 1.0) return 0.5;
        return v;
    }

    /** "Social-Media-Policy.pdf" → "Social-Media-Policy"; separators are left to the tokenizer. */
    static String stripExtension(String fileName) {
        if (fileName == null || fileName.isBlank()) return "";
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static void append(StringBuilder sb, String v) {
        if (v == null || v.isBlank()) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(v);
    }

    private static String get(dev.langchain4j.data.document.Metadata meta, String key) {
        if (meta == null) return null;
        try { return meta.getString(key); } catch (Exception e) { return null; }
    }
}
