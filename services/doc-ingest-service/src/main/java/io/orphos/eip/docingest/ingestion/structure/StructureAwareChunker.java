package io.orphos.eip.docingest.ingestion.structure;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structure-aware ("semantic") chunker ported from Ragas MVP.
 *
 * Rules:
 *  - TABLES are atomic (one chunk per table; oversized tables split by row group).
 *  - NUMBERED CLAUSES are never cut mid-clause when the whole clause fits.
 *  - HEADINGS define section boundaries — chunks never span a heading change,
 *    and every chunk carries a section breadcrumb in its metadata.
 *  - PROSE groups consecutive paragraphs under one heading up to the token budget,
 *    with sentence-level overlap across budget-forced splits only.
 *
 * Metadata added per chunk: section_heading, clause_number, content_type, page_number.
 */
@Component
public class StructureAwareChunker {

    private static final Logger log = LogManager.getLogger(StructureAwareChunker.class);

    private final int chunkTokens;
    private final int overlapTokens;

    public StructureAwareChunker() { this(512, 64); }

    public StructureAwareChunker(int chunkTokens, int overlapTokens) {
        this.chunkTokens  = chunkTokens;
        this.overlapTokens = overlapTokens;
    }

    private static final Pattern CLAUSE_START = Pattern.compile(
        "^(?:"
        + "([0-9०-९]+(?:\\.[0-9०-९]+)+)"
        + "|([0-9०-९]+)[.)]"
        + "|\\(([a-z]|[ivxlc]+|[क-ह])\\)"
        + ")\\s+.*", Pattern.DOTALL);

    // Splits sentences for both scripts: after Japanese full-width enders 。！？
    // (which are NOT followed by a space) via a zero-width lookbehind, and after
    // Latin/Indic enders .!?। when followed by whitespace. Without the JA branch,
    // a whole Japanese paragraph was treated as one unsplittable sentence.
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[。！？])|(?<=[.!?।])\\s+");

    public List<TextSegment> chunk(List<Block> blocks, Metadata baseMetadata) {
        List<Unit> units = toUnits(blocks);
        Map<String, Integer> clauseSizes = clauseSizes(units);

        List<TextSegment> segments = new ArrayList<>();
        List<Unit> buffer = new ArrayList<>();
        String overlapCarry = null;

        for (Unit unit : units) {
            if (unit.table() != null) {
                flush(buffer, null, baseMetadata, segments);
                overlapCarry = null;
                emitTable(unit, baseMetadata, segments);
                continue;
            }

            boolean sectionChanged = !buffer.isEmpty()
                && !buffer.get(buffer.size() - 1).headingPath().equals(unit.headingPath());
            if (sectionChanged) {
                flush(buffer, null, baseMetadata, segments);
                overlapCarry = null;
            }

            if (TokenEstimator.estimate(unit.text()) > chunkTokens) {
                flush(buffer, overlapCarry, baseMetadata, segments);
                overlapCarry = emitOversized(unit, baseMetadata, segments);
                continue;
            }

            if (bufferTokens(buffer) + TokenEstimator.estimate(unit.text()) > chunkTokens
                    && !buffer.isEmpty()) {
                String prevClause = buffer.get(buffer.size() - 1).clause();
                List<Unit> carried = new ArrayList<>();
                if (!unit.clause().isEmpty()
                        && unit.clause().equals(prevClause)
                        && clauseSizes.getOrDefault(clauseKey(unit), Integer.MAX_VALUE) <= chunkTokens) {
                    while (!buffer.isEmpty()
                            && buffer.get(buffer.size() - 1).clause().equals(unit.clause())) {
                        carried.add(0, buffer.remove(buffer.size() - 1));
                    }
                }
                flush(buffer, overlapCarry, baseMetadata, segments);
                overlapCarry = (carried.isEmpty() && unit.clause().equals(prevClause))
                    ? trailingOverlap(segments) : null;
                buffer.addAll(carried);
            }
            buffer.add(unit);
        }
        flush(buffer, overlapCarry, baseMetadata, segments);
        log.debug("Chunked {} blocks into {} segments", blocks.size(), segments.size());
        return segments;
    }

    private record Unit(String text, String headingPath, String clause, int page, Block table) {}

    private List<Unit> toUnits(List<Block> blocks) {
        List<Unit> units = new ArrayList<>();
        List<String> headingTexts = new ArrayList<>();
        List<Integer> headingLevels = new ArrayList<>();
        String currentClause = "";

        for (Block block : blocks) {
            switch (block.type()) {
                case HEADING -> {
                    while (!headingLevels.isEmpty()
                            && headingLevels.get(headingLevels.size() - 1) >= block.level()) {
                        headingLevels.remove(headingLevels.size() - 1);
                        headingTexts.remove(headingTexts.size() - 1);
                    }
                    headingLevels.add(block.level());
                    headingTexts.add(block.text());
                    currentClause = "";
                }
                case PARAGRAPH, LIST_ITEM -> {
                    String clause = clauseNumber(block.text());
                    if (clause != null) currentClause = clause;
                    String text = block.type() == Block.Type.LIST_ITEM
                        ? "- " + block.text() : block.text();
                    units.add(new Unit(text, String.join(" › ", headingTexts),
                        currentClause, block.pageNumber(), null));
                }
                case TABLE -> units.add(new Unit(block.text(),
                    String.join(" › ", headingTexts), currentClause,
                    block.pageNumber(), block));
            }
        }
        return units;
    }

    private static String clauseNumber(String text) {
        Matcher m = CLAUSE_START.matcher(text);
        if (!m.matches()) return null;
        for (int g = 1; g <= m.groupCount(); g++) {
            if (m.group(g) != null) return m.group(g);
        }
        return null;
    }

    private Map<String, Integer> clauseSizes(List<Unit> units) {
        Map<String, Integer> sizes = new HashMap<>();
        for (Unit u : units) {
            if (u.table() == null && !u.clause().isEmpty()) {
                sizes.merge(clauseKey(u), TokenEstimator.estimate(u.text()), Integer::sum);
            }
        }
        return sizes;
    }

    private static String clauseKey(Unit u) { return u.headingPath() + " " + u.clause(); }

    private int bufferTokens(List<Unit> buffer) {
        return buffer.stream().mapToInt(u -> TokenEstimator.estimate(u.text())).sum();
    }

    private void flush(List<Unit> buffer, String overlap, Metadata base, List<TextSegment> out) {
        if (buffer.isEmpty()) return;
        Unit first = buffer.get(0);
        StringBuilder body = new StringBuilder();
        if (overlap != null && !overlap.isBlank()) body.append(overlap).append("\n");
        for (Unit u : buffer) body.append(u.text()).append("\n");
        out.add(segment(body.toString().trim(), first.headingPath(), first.clause(),
            first.page(), "prose", base));
        buffer.clear();
    }

    private void emitTable(Unit unit, Metadata base, List<TextSegment> out) {
        Block table = unit.table();
        String markdown = TableSerializer.toMarkdown(table.tableRows(), table.firstRowIsHeader());
        if (TokenEstimator.estimate(markdown) <= chunkTokens) {
            out.add(segment(markdown, unit.headingPath(), unit.clause(), unit.page(), "table", base));
            return;
        }
        List<List<String>> rows = table.tableRows();
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);
        List<String> header;
        List<List<String>> bodyRows;
        if (table.firstRowIsHeader()) {
            header = rows.get(0);
            bodyRows = rows.subList(1, rows.size());
        } else {
            header = new ArrayList<>();
            for (int i = 1; i <= columns; i++) header.add("Col " + i);
            bodyRows = rows;
        }
        List<List<String>> group = new ArrayList<>();
        for (List<String> row : bodyRows) {
            group.add(row);
            String slice = TableSerializer.toMarkdownSlice(header, group, columns);
            if (TokenEstimator.estimate(slice) > chunkTokens && group.size() > 1) {
                group.remove(group.size() - 1);
                out.add(segment(TableSerializer.toMarkdownSlice(header, group, columns),
                    unit.headingPath(), unit.clause(), unit.page(), "table", base));
                group = new ArrayList<>();
                group.add(row);
            }
        }
        if (!group.isEmpty()) {
            out.add(segment(TableSerializer.toMarkdownSlice(header, group, columns),
                unit.headingPath(), unit.clause(), unit.page(), "table", base));
        }
    }

    private String emitOversized(Unit unit, Metadata base, List<TextSegment> out) {
        String[] sentences = SENTENCE_SPLIT.split(unit.text());
        StringBuilder part = new StringBuilder();
        for (String sentence : sentences) {
            if (TokenEstimator.estimate(part + " " + sentence) > chunkTokens && part.length() > 0) {
                out.add(segment(part.toString().trim(), unit.headingPath(), unit.clause(),
                    unit.page(), "prose", base));
                String overlap = trailingSentences(part.toString(), overlapTokens);
                part = new StringBuilder(overlap.isEmpty() ? "" : overlap + " ");
            }
            part.append(sentence).append(" ");
        }
        if (!part.toString().isBlank()) {
            out.add(segment(part.toString().trim(), unit.headingPath(), unit.clause(),
                unit.page(), "prose", base));
        }
        return trailingSentences(part.toString(), overlapTokens);
    }

    private String trailingOverlap(List<TextSegment> segments) {
        if (segments.isEmpty()) return null;
        return trailingSentences(segments.get(segments.size() - 1).text(), overlapTokens);
    }

    private static String trailingSentences(String text, int tokenBudget) {
        String[] sentences = SENTENCE_SPLIT.split(text.trim());
        StringBuilder overlap = new StringBuilder();
        for (int i = sentences.length - 1; i >= 0; i--) {
            String candidate = sentences[i] + (overlap.length() == 0 ? "" : " " + overlap);
            if (TokenEstimator.estimate(candidate) > tokenBudget) break;
            overlap.insert(0, overlap.length() == 0 ? sentences[i] : sentences[i] + " ");
        }
        return overlap.toString().trim();
    }

    private TextSegment segment(String body, String headingPath, String clause,
                                int page, String contentType, Metadata base) {
        String text = headingPath.isEmpty() ? body : headingPath + "\n" + body;
        Metadata meta = base.copy();
        meta.put("section_heading", headingPath);
        meta.put("clause_number", clause);
        meta.put("content_type", contentType);
        meta.put("page_number", String.valueOf(page));
        return TextSegment.from(text, meta);
    }
}
