package io.orphos.eip.dataingestion.util;

import io.orphos.eip.dataingestion.component.MappingValidator;
import io.orphos.eip.dataingestion.vo.MappingDtos.TargetType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Built-in transformation functions applied while mapping source values to a
 * target property (requirements 3.6 and 6.1). A transform spec is
 * {@code NAME} or {@code NAME:argument}, e.g. {@code CONCAT:, } or
 * {@code DATE_PATTERN:yyyyMMdd}.
 *
 * <p>Multi-source (many-to-one) mappings are reduced to one value first —
 * CONCAT with the given separator (space when unspecified) — then the value
 * conversion applies. Unknown transform names are rejected at save time by
 * {@link MappingValidator}.</p>
 */
public final class TransformFunctions {

    private TransformFunctions() {}

    public static final Set<String> NAMES = Set.of(
            "IDENTITY", "TRIM", "UPPER", "LOWER", "CONCAT",
            "PREFIX", "SUFFIX", "REPLACE", "SUBSTRING", "DATE_PATTERN");

    /** Combine the (already stringified) source values into the pre-conversion raw value. */
    public static String combine(List<String> values, String transformSpec) {
        String name = name(transformSpec);
        String arg = arg(transformSpec);
        if (values.size() == 1 && !"CONCAT".equals(name)) {
            return values.get(0);
        }
        String sep = "CONCAT".equals(name) ? (arg == null ? " " : arg) : " ";
        return values.stream().filter(v -> v != null && !v.isBlank())
                .collect(Collectors.joining(sep));
    }

    /** Apply the (single-value) string transform. */
    public static String apply(String value, String transformSpec) {
        if (value == null) return null;
        String name = name(transformSpec);
        String arg = arg(transformSpec);
        return switch (name) {
            case "TRIM" -> value.trim();
            case "UPPER" -> value.toUpperCase(Locale.ROOT);
            case "LOWER" -> value.toLowerCase(Locale.ROOT);
            case "PREFIX" -> (arg == null ? "" : arg) + value;
            case "SUFFIX" -> value + (arg == null ? "" : arg);
            case "REPLACE" -> replace(value, arg);
            case "SUBSTRING" -> substring(value, arg);
            case "DATE_PATTERN" -> reformatDate(value, arg);
            default -> value; // IDENTITY / CONCAT (already combined)
        };
    }

    /** Requirement 3.4 — convert the transformed string to the target type. */
    public static Object convert(String value, TargetType type) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        return switch (type) {
            case INTEGER -> Long.parseLong(v);
            case NUMBER -> Double.parseDouble(v);
            case BOOLEAN -> parseBoolean(v);
            case DATE -> normaliseDate(v);
            case STRING -> v;
        };
    }

    public static String name(String transformSpec) {
        if (transformSpec == null || transformSpec.isBlank()) return "IDENTITY";
        int colon = transformSpec.indexOf(':');
        String n = (colon < 0 ? transformSpec : transformSpec.substring(0, colon)).trim().toUpperCase(Locale.ROOT);
        return n.isEmpty() ? "IDENTITY" : n;
    }

    public static String arg(String transformSpec) {
        if (transformSpec == null) return null;
        int colon = transformSpec.indexOf(':');
        return colon < 0 ? null : transformSpec.substring(colon + 1);
    }

    private static String replace(String value, String arg) {
        // REPLACE:old=new
        if (arg == null || arg.indexOf('=') < 0) return value;
        int eq = arg.indexOf('=');
        return value.replace(arg.substring(0, eq), arg.substring(eq + 1));
    }

    private static String substring(String value, String arg) {
        // SUBSTRING:start[,end] (0-based, end exclusive)
        if (arg == null) return value;
        try {
            String[] parts = arg.split(",", 2);
            int start = Math.max(0, Integer.parseInt(parts[0].trim()));
            int end = parts.length > 1 ? Math.min(value.length(), Integer.parseInt(parts[1].trim()))
                                       : value.length();
            return start >= end ? "" : value.substring(start, end);
        } catch (NumberFormatException e) {
            return value;
        }
    }

    /** Parse the value with the supplied pattern, emit ISO-8601. */
    private static String reformatDate(String value, String pattern) {
        if (pattern == null || pattern.isBlank()) return value;
        DateTimeFormatter in = DateTimeFormatter.ofPattern(pattern);
        try {
            return LocalDateTime.parse(value, in).toString();
        } catch (Exception ignored) {
            return LocalDate.parse(value, in).toString();
        }
    }

    private static boolean parseBoolean(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "true", "1", "y", "yes", "t" -> true;
            case "false", "0", "n", "no", "f" -> false;
            default -> throw new IllegalArgumentException("not a boolean: " + v);
        };
    }

    /** Accept ISO date/date-time as-is; anything else is passed through untouched. */
    private static String normaliseDate(String v) {
        return v;
    }
}
