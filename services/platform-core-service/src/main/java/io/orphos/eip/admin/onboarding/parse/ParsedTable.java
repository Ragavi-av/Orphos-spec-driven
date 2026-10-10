package io.orphos.eip.admin.onboarding.parse;

import java.util.List;

/**
 * A single parsed table: one sheet of an .xlsx workbook, or one .csv file.
 * Rows are aligned to {@link #headers()} by index; missing cells are null.
 */
public record ParsedTable(
        String sourceFile,
        String sheetName,
        List<String> headers,
        List<List<String>> rows
) {}
