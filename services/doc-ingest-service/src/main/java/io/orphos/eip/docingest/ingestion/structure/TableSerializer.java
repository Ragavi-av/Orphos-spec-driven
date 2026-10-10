package io.orphos.eip.docingest.ingestion.structure;

import java.util.ArrayList;
import java.util.List;

/**
 * Serialises table cells to GitHub-style Markdown pipe tables.
 * Keeps row/column relationships intact in plain text so embeddings
 * and BM25 index actual cell values, not word soup.
 */
final class TableSerializer {

    private TableSerializer() {}

    static String toMarkdown(List<List<String>> rows, boolean firstRowIsHeader) {
        if (rows.isEmpty()) return "";
        int columns = rows.stream().mapToInt(List::size).max().orElse(1);

        StringBuilder sb = new StringBuilder();
        int bodyStart;
        if (firstRowIsHeader) {
            appendRow(sb, rows.get(0), columns);
            bodyStart = 1;
        } else {
            List<String> generic = new ArrayList<>();
            for (int i = 1; i <= columns; i++) generic.add("Col " + i);
            appendRow(sb, generic, columns);
            bodyStart = 0;
        }
        sb.append("|").append(" --- |".repeat(columns)).append("\n");
        for (int i = bodyStart; i < rows.size(); i++) {
            appendRow(sb, rows.get(i), columns);
        }
        return sb.toString().trim();
    }

    static String toMarkdownSlice(List<String> headerRow, List<List<String>> bodyRows, int columns) {
        StringBuilder sb = new StringBuilder();
        appendRow(sb, headerRow, columns);
        sb.append("|").append(" --- |".repeat(columns)).append("\n");
        for (List<String> row : bodyRows) {
            appendRow(sb, row, columns);
        }
        return sb.toString().trim();
    }

    private static void appendRow(StringBuilder sb, List<String> cells, int columns) {
        sb.append("|");
        for (int c = 0; c < columns; c++) {
            String cell = c < cells.size() ? cells.get(c) : "";
            sb.append(" ").append(cell.replace("|", "\\|")).append(" |");
        }
        sb.append("\n");
    }
}
