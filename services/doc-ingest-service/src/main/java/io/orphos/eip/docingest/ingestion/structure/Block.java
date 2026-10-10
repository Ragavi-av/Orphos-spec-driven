package io.orphos.eip.docingest.ingestion.structure;

import java.util.List;

/**
 * One structural element of a parsed document, in reading order.
 *
 * @param type             element kind
 * @param text             plain text; for TABLE this is the Markdown serialisation
 * @param level            heading level 1-6; 0 for non-headings
 * @param pageNumber       1-based page; 0 = unknown (HTML, CSV, etc.)
 * @param tableRows        raw cell text row-major; null for non-tables
 * @param firstRowIsHeader whether tableRows[0] came from th cells
 */
public record Block(
    Type type,
    String text,
    int level,
    int pageNumber,
    List<List<String>> tableRows,
    boolean firstRowIsHeader
) {
    public enum Type { HEADING, PARAGRAPH, LIST_ITEM, TABLE }

    public static Block heading(String text, int level, int page) {
        return new Block(Type.HEADING, text, level, page, null, false);
    }

    public static Block paragraph(String text, int page) {
        return new Block(Type.PARAGRAPH, text, 0, page, null, false);
    }

    public static Block listItem(String text, int page) {
        return new Block(Type.LIST_ITEM, text, 0, page, null, false);
    }

    public static Block table(String markdown, List<List<String>> rows, boolean headerRow, int page) {
        return new Block(Type.TABLE, markdown, 0, page, rows, headerRow);
    }
}
