package io.orphos.eip.docingest.search;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.LowerCaseFilter;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.Tokenizer;
import org.apache.lucene.analysis.cjk.CJKWidthFilter;
import org.apache.lucene.analysis.ja.JapaneseTokenizer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Bilingual (JA + EN) word tokenizer for keyword matching and BM25 scoring.
 *
 * <p>Uses Lucene's Kuromoji {@link JapaneseTokenizer} in SEARCH mode, which
 * segments Japanese morphologically AND tokenizes Latin runs — so
 * {@code 日本語のテスト} becomes {@code [日本語, テスト]} instead of one opaque token,
 * while English continues to tokenize normally. This replaces the old regex
 * split on {@code [^\p{Alnum}]+}, which treated any space-less Japanese string as
 * a single token and so broke BM25 fusion and ALL_KEYWORDS matching for Japanese.
 *
 * <p>Pure Java (no native MeCab). The analyzers are thread-safe and reusable, so
 * they are built once and shared.
 */
public final class JapaneseTextTokenizer {

    private static final Analyzer LOWERCASE = new KuromojiAnalyzer(true);
    private static final Analyzer EXACT     = new KuromojiAnalyzer(false);

    private JapaneseTextTokenizer() {}

    /**
     * Tokenizes {@code text} into content words.
     *
     * @param caseSensitive when false (the default), Latin tokens are lowercased
     *                      so matching is case-insensitive; Japanese is unaffected.
     */
    public static List<String> tokenize(String text, boolean caseSensitive) {
        if (text == null || text.isBlank()) return List.of();
        Analyzer analyzer = caseSensitive ? EXACT : LOWERCASE;
        List<String> out = new ArrayList<>();
        try (TokenStream ts = analyzer.tokenStream("f", new StringReader(text))) {
            CharTermAttribute term = ts.addAttribute(CharTermAttribute.class);
            ts.reset();
            while (ts.incrementToken()) {
                String t = term.toString();
                if (!t.isBlank()) out.add(t);
            }
            ts.end();
        } catch (Exception e) {
            // Tokenization must never fail a search — fall back to a whitespace split.
            out.clear();
            for (String t : text.split("\\s+")) {
                if (!t.isBlank()) out.add(caseSensitive ? t : t.toLowerCase());
            }
        }
        return out;
    }

    /** Kuromoji SEARCH-mode analyzer; width-normalises CJK and optionally lowercases Latin. */
    private static final class KuromojiAnalyzer extends Analyzer {
        private final boolean lowercase;

        KuromojiAnalyzer(boolean lowercase) { this.lowercase = lowercase; }

        @Override
        protected TokenStreamComponents createComponents(String fieldName) {
            Tokenizer source = new JapaneseTokenizer(null, true, JapaneseTokenizer.Mode.SEARCH);
            TokenStream result = new CJKWidthFilter(source);
            if (lowercase) result = new LowerCaseFilter(result);
            return new TokenStreamComponents(source, result);
        }
    }
}
