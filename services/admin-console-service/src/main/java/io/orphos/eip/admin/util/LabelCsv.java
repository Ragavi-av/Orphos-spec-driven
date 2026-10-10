package io.orphos.eip.admin.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Minimal RFC-4180 CSV codec for UI-label bulk import/export.
 *
 * <p>Expected columns (located by header name, case-insensitive):
 * {@code key}, {@code language}, {@code value}. Extra columns (e.g. a
 * {@code default} column in the exported template) are ignored, so a file
 * exported from the Branding screen round-trips unchanged.</p>
 */
public final class LabelCsv {

    private LabelCsv() {}

    public record Row(int lineNumber, String key, String language, String value) {}

    public record ParseResult(List<Row> rows, List<String> errors) {}

    public static ParseResult parse(String csv) {
        List<Row> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<List<String>> records = tokenize(csv);
        if (records.isEmpty()) {
            errors.add("Empty file");
            return new ParseResult(rows, errors);
        }
        List<String> header = records.get(0);
        int keyCol = indexOf(header, "key", "labelkey");
        int langCol = indexOf(header, "language", "lang");
        int valueCol = indexOf(header, "value", "override");
        if (keyCol < 0 || langCol < 0 || valueCol < 0) {
            errors.add("Header must contain 'key', 'language' and 'value' columns "
                    + "(got: " + String.join(", ", header) + ")");
            return new ParseResult(rows, errors);
        }
        for (int i = 1; i < records.size(); i++) {
            List<String> rec = records.get(i);
            // Tolerate blank trailing lines.
            if (rec.size() == 1 && rec.get(0).isBlank()) continue;
            int line = i + 1;
            if (rec.size() <= Math.max(keyCol, Math.max(langCol, valueCol))) {
                errors.add("Line " + line + ": expected at least "
                        + (Math.max(keyCol, Math.max(langCol, valueCol)) + 1) + " columns");
                continue;
            }
            rows.add(new Row(line,
                    rec.get(keyCol).trim(),
                    rec.get(langCol).trim().toLowerCase(Locale.ROOT),
                    rec.get(valueCol)));
        }
        return new ParseResult(rows, errors);
    }

    private static int indexOf(List<String> header, String... names) {
        for (int i = 0; i < header.size(); i++) {
            String h = header.get(i).trim().toLowerCase(Locale.ROOT);
            for (String n : names) {
                if (h.equals(n)) return i;
            }
        }
        return -1;
    }

    /** Splits CSV text into records/fields, honouring quoted fields with embedded commas, quotes and newlines. */
    private static List<List<String>> tokenize(String csv) {
        List<List<String>> records = new ArrayList<>();
        if (csv == null) return records;
        // Strip a UTF-8 BOM so the first header cell matches by name.
        if (!csv.isEmpty() && csv.charAt(0) == '\uFEFF') csv = csv.substring(1);
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char c = csv.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') { field.append('"'); i++; }
                    else quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\r') {
                // swallow; \n closes the record
            } else if (c == '\n') {
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    public static String write(List<Row> rows) {
        StringBuilder sb = new StringBuilder("key,language,value\r\n");
        for (Row r : rows) {
            sb.append(escape(r.key())).append(',')
              .append(escape(r.language())).append(',')
              .append(escape(r.value())).append("\r\n");
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }
}
