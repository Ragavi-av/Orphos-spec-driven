package io.orphos.eip.admin.onboarding.parse;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses uploaded .xlsx and .csv files into {@link ParsedTable}s — one table per
 * worksheet (xlsx) or per file (csv). Header row is the first non-empty row.
 */
@Component
public class TableParser {

    private final DataFormatter formatter = new DataFormatter(Locale.ENGLISH);

    public List<ParsedTable> parse(String fileName, byte[] bytes) throws IOException {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ENGLISH);
        if (lower.endsWith(".csv")) {
            return List.of(parseCsv(fileName, bytes));
        }
        return parseXlsx(fileName, bytes);
    }

    // ── XLSX ────────────────────────────────────────────────────────────────

    private List<ParsedTable> parseXlsx(String fileName, byte[] bytes) throws IOException {
        List<ParsedTable> tables = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            for (int si = 0; si < wb.getNumberOfSheets(); si++) {
                Sheet sheet = wb.getSheetAt(si);
                int firstRow = sheet.getFirstRowNum();
                Row header = sheet.getRow(firstRow);
                if (header == null) continue;

                List<String> headers = new ArrayList<>();
                int lastCol = header.getLastCellNum();
                for (int c = 0; c < lastCol; c++) {
                    String h = cellString(header.getCell(c)).trim();
                    headers.add(h.isEmpty() ? "column" + (c + 1) : h);
                }

                List<List<String>> rows = new ArrayList<>();
                for (int r = firstRow + 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    List<String> values = new ArrayList<>();
                    boolean anyValue = false;
                    for (int c = 0; c < headers.size(); c++) {
                        String v = cellString(row.getCell(c));
                        if (!v.isBlank()) anyValue = true;
                        values.add(v.isEmpty() ? null : v);
                    }
                    if (anyValue) rows.add(values);
                }
                tables.add(new ParsedTable(fileName, wb.getSheetName(si), headers, rows));
            }
        }
        return tables;
    }

    private String cellString(Cell cell) {
        if (cell == null) return "";
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault())
                    .toLocalDate().toString();
        }
        return formatter.formatCellValue(cell).trim();
    }

    // ── CSV ──────────────────────────────────────────────────────────────────

    private ParsedTable parseCsv(String fileName, byte[] bytes) throws IOException {
        List<List<String>> all = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                all.add(splitCsvLine(line));
            }
        }
        if (all.isEmpty()) {
            return new ParsedTable(fileName, null, List.of(), List.of());
        }
        List<String> headers = new ArrayList<>();
        List<String> rawHeader = all.get(0);
        for (int c = 0; c < rawHeader.size(); c++) {
            String h = rawHeader.get(c) == null ? "" : rawHeader.get(c).trim();
            headers.add(h.isEmpty() ? "column" + (c + 1) : h);
        }
        List<List<String>> rows = new ArrayList<>();
        for (int i = 1; i < all.size(); i++) {
            List<String> raw = all.get(i);
            List<String> values = new ArrayList<>();
            for (int c = 0; c < headers.size(); c++) {
                String v = c < raw.size() ? raw.get(c) : null;
                values.add(v == null || v.isBlank() ? null : v.trim());
            }
            rows.add(values);
        }
        return new ParsedTable(fileName, null, headers, rows);
    }

    /** Minimal RFC-4180-ish splitter: handles quoted fields and escaped quotes. */
    private List<String> splitCsvLine(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else {
                    cur.append(ch);
                }
            } else if (ch == '"') {
                inQuotes = true;
            } else if (ch == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        out.add(cur.toString());
        return out;
    }
}
