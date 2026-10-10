package io.orphos.eip.mailinsight.model;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Shared ordering for merged task / notification lists. Priority and criticality
 * sort by severity rank (not alphabetically), which is why this can't be a plain
 * SQL ORDER BY.
 */
public final class MailInsightSort {

    private MailInsightSort() {}

    private static final Map<String, Integer> PRIORITY_RANK = Map.of(
            "CRITICAL", 0, "HIGH", 1, "MEDIUM", 2, "LOW", 3);
    private static final Map<String, Integer> CRITICALITY_RANK = Map.of(
            "ALERT", 0, "WARNING", 1, "INFORMATION", 2);

    /** sort = priority (default) | criticality | dueDate. */
    public static List<MailInsightDto> tasks(List<MailInsightDto> rows, String sort) {
        Comparator<MailInsightDto> cmp = switch (sort == null ? "priority" : sort) {
            case "dueDate" -> Comparator.comparing(MailInsightDto::dueDate,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "criticality" -> Comparator.comparingInt(d -> rank(CRITICALITY_RANK, d.criticality()));
            default -> Comparator.comparingInt((MailInsightDto d) -> rank(PRIORITY_RANK, d.priority()))
                    .thenComparing(Comparator.comparingInt(MailInsightDto::score).reversed());
        };
        return rows.stream().sorted(cmp).toList();
    }

    public static List<MailInsightDto> notifications(List<MailInsightDto> rows) {
        return rows.stream().sorted(Comparator
                .comparingInt((MailInsightDto d) -> rank(CRITICALITY_RANK, d.criticality()))
                .thenComparing(Comparator.comparing(MailInsightDto::receivedAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))))
                .toList();
    }

    private static int rank(Map<String, Integer> ranks, String value) {
        if (value == null) return Integer.MAX_VALUE;
        return ranks.getOrDefault(value.toUpperCase(), Integer.MAX_VALUE);
    }
}
