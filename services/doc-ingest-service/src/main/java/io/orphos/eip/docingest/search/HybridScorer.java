package io.orphos.eip.docingest.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Hybrid retrieval fusion: combines the dense (vector-similarity) ranking with
 * a BM25 keyword ranking using Reciprocal Rank Fusion (RRF).
 *
 * <p>The dense candidate pool returned by the vector store is over-fetched, then
 * BM25 is computed over that pool (IDF over the pool, standard k1/b). The two
 * rankings are fused with RRF — {@code score(d) = Σ 1/(k + rank_r(d))} — which
 * is robust to the different score scales of cosine similarity vs BM25 and needs
 * no per-corpus tuning.</p>
 *
 * <p>Note: BM25 here ranks within the dense candidate pool rather than via a
 * separate full-corpus inverted index (which would require Qdrant sparse
 * vectors). Over-fetching a wide pool keeps recall high; the fusion fixes the
 * common failure where pure-dense retrieval misses exact keyword/number matches
 * (circular numbers, scheme names, clause references).</p>
 */
public final class HybridScorer {

    private static final double K1  = 1.2;
    private static final double B   = 0.75;
    private static final int    RRF_K = 60;

    private HybridScorer() {}

    /**
     * @param denseOrderedTexts candidate chunk texts in dense-ranked order (best first)
     * @param query             the user query
     * @param caseSensitive     whether keyword matching is case-sensitive
     * @return candidate indices re-ordered by the fused score, best first
     */
    public static List<Integer> fuse(List<String> denseOrderedTexts, String query, boolean caseSensitive) {
        int n = denseOrderedTexts.size();
        List<Integer> identity = new ArrayList<>(n);
        for (int i = 0; i < n; i++) identity.add(i);
        if (n <= 1) return identity;

        // ── BM25 over the candidate pool ──────────────────────────────────────
        List<List<String>> docs = new ArrayList<>(n);
        Map<String, Integer> df = new HashMap<>();
        long totalLen = 0;
        for (String text : denseOrderedTexts) {
            List<String> toks = tokenize(text, caseSensitive);
            docs.add(toks);
            totalLen += toks.size();
            for (String t : new java.util.HashSet<>(toks)) df.merge(t, 1, Integer::sum);
        }
        double avgdl = n > 0 ? (double) totalLen / n : 0.0;
        List<String> qToks = tokenize(query, caseSensitive);

        double[] bm25 = new double[n];
        for (int i = 0; i < n; i++) {
            Map<String, Integer> tf = new HashMap<>();
            for (String t : docs.get(i)) tf.merge(t, 1, Integer::sum);
            double dl = docs.get(i).size();
            double score = 0.0;
            for (String q : qToks) {
                Integer f = tf.get(q);
                if (f == null) continue;
                int n_q = df.getOrDefault(q, 0);
                double idf = Math.log(1 + (n - n_q + 0.5) / (n_q + 0.5));
                double denom = f + K1 * (1 - B + B * (avgdl > 0 ? dl / avgdl : 0));
                score += idf * (f * (K1 + 1)) / (denom == 0 ? 1 : denom);
            }
            bm25[i] = score;
        }

        // BM25 rank (1-based). Ties keep dense order (stable).
        Integer[] byBm25 = identity.toArray(new Integer[0]);
        java.util.Arrays.sort(byBm25, (a, b) -> Double.compare(bm25[b], bm25[a]));
        int[] bm25Rank = new int[n];
        for (int rank = 0; rank < n; rank++) bm25Rank[byBm25[rank]] = rank + 1;

        // ── RRF fusion (dense rank is just position + 1) ──────────────────────
        double[] rrf = new double[n];
        for (int i = 0; i < n; i++) {
            int denseRank = i + 1;
            rrf[i] = 1.0 / (RRF_K + denseRank) + 1.0 / (RRF_K + bm25Rank[i]);
        }

        List<Integer> fused = new ArrayList<>(identity);
        fused.sort((a, b) -> Double.compare(rrf[b], rrf[a]));
        return fused;
    }

    private static List<String> tokenize(String s, boolean caseSensitive) {
        // Bilingual (JA+EN) segmentation via Kuromoji — see JapaneseTextTokenizer.
        return JapaneseTextTokenizer.tokenize(s, caseSensitive);
    }
}
