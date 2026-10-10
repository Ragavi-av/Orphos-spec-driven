package io.orphos.eip.docingest.ingestion.structure;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Extracts tables from PDF files using PDFBox text-position analysis.
 *
 * Algorithm: capture per-character positions → cluster into horizontal lines
 * by Y-proximity → split lines into cells by X-gap → group consecutive
 * column-aligned lines into table regions.
 *
 * PDFBox is on the classpath transitively via tika-parsers-standard-package.
 */
public class PdfTableExtractor {

    private static final Logger log = LogManager.getLogger(PdfTableExtractor.class);

    private static final float ROW_Y_TOLERANCE = 2.5f;
    private static final float COL_GAP_PT      = 15.0f;
    private static final float COL_ALIGN_PT    = 8.0f;
    private static final int   MIN_COLS        = 2;
    private static final int   MIN_TABLE_ROWS  = 2;

    /** A table found on a specific 1-based page of the PDF. */
    public record ExtractedTable(int page, List<List<String>> rows) {}

    /**
     * Returns all tables extracted from the PDF.
     * Returns an empty list on any error rather than throwing.
     */
    public List<ExtractedTable> extract(Path pdfPath) {
        List<ExtractedTable> results = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdfPath.toFile())) {
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                results.addAll(extractPage(doc, page));
            }
            log.debug("PDF table extraction: {} table(s) in {} page(s) — {}",
                results.size(), doc.getNumberOfPages(), pdfPath.getFileName());
        } catch (Exception e) {
            log.warn("PDF table extraction skipped for {} — {}", pdfPath.getFileName(), e.getMessage());
        }
        return results;
    }

    private List<ExtractedTable> extractPage(PDDocument doc, int pageNum) throws IOException {
        PositionCapture stripper = new PositionCapture();
        stripper.setStartPage(pageNum);
        stripper.setEndPage(pageNum);
        stripper.setSortByPosition(true);
        stripper.getText(doc);

        if (stripper.positions.isEmpty()) return List.of();

        List<Line> lines = groupIntoLines(stripper.positions);
        List<Line> multiCol = lines.stream()
            .filter(l -> l.cells().size() >= MIN_COLS)
            .collect(Collectors.toList());

        return detectTables(multiCol, pageNum);
    }

    private List<Line> groupIntoLines(List<TextPosition> positions) {
        positions.sort(Comparator
            .comparingDouble(TextPosition::getY)
            .thenComparingDouble(TextPosition::getX));

        List<Line> lines = new ArrayList<>();
        List<TextPosition> current = new ArrayList<>();
        float prevY = Float.NaN;

        for (TextPosition pos : positions) {
            float y = pos.getY();
            if (Float.isNaN(prevY) || Math.abs(y - prevY) <= ROW_Y_TOLERANCE) {
                current.add(pos);
            } else {
                if (!current.isEmpty()) lines.add(buildLine(current));
                current = new ArrayList<>();
                current.add(pos);
            }
            prevY = y;
        }
        if (!current.isEmpty()) lines.add(buildLine(current));
        return lines;
    }

    private Line buildLine(List<TextPosition> positions) {
        positions.sort(Comparator.comparingDouble(TextPosition::getX));

        List<String> cells = new ArrayList<>();
        List<Float> xStarts = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        float prevRight = Float.NaN;
        float cellStartX = positions.get(0).getX();

        for (TextPosition pos : positions) {
            float x = pos.getX();
            float w = pos.getWidth();
            if (!Float.isNaN(prevRight) && (x - prevRight) > COL_GAP_PT) {
                String cell = sb.toString().trim();
                if (!cell.isEmpty()) {
                    cells.add(cell);
                    xStarts.add(cellStartX);
                }
                sb.setLength(0);
                cellStartX = x;
            }
            sb.append(pos.getUnicode());
            prevRight = Float.isNaN(prevRight) ? x + w : Math.max(prevRight, x + w);
        }
        String last = sb.toString().trim();
        if (!last.isEmpty()) {
            cells.add(last);
            xStarts.add(cellStartX);
        }
        return new Line(cells, xStarts, positions.get(0).getY());
    }

    private List<ExtractedTable> detectTables(List<Line> multiColLines, int pageNum) {
        if (multiColLines.size() < MIN_TABLE_ROWS) return List.of();

        List<ExtractedTable> tables = new ArrayList<>();
        int i = 0;
        while (i < multiColLines.size()) {
            int j = i + 1;
            while (j < multiColLines.size() && columnsAlign(multiColLines.get(i), multiColLines.get(j))) {
                j++;
            }
            if (j - i >= MIN_TABLE_ROWS) {
                List<List<String>> rows = multiColLines.subList(i, j).stream()
                    .map(Line::cells)
                    .collect(Collectors.toList());
                tables.add(new ExtractedTable(pageNum, rows));
            }
            i = j;
        }
        return tables;
    }

    private boolean columnsAlign(Line a, Line b) {
        int matches = 0;
        for (float ax : a.xStarts()) {
            for (float bx : b.xStarts()) {
                if (Math.abs(ax - bx) <= COL_ALIGN_PT) {
                    matches++;
                    break;
                }
            }
            if (matches >= MIN_COLS) return true;
        }
        return false;
    }

    private record Line(List<String> cells, List<Float> xStarts, float y) {}

    private static final class PositionCapture extends PDFTextStripper {

        final List<TextPosition> positions = new ArrayList<>();

        PositionCapture() throws IOException { super(); }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            positions.addAll(textPositions);
        }

        @Override
        public void writeString(String text) {}
    }
}
