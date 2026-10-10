package io.orphos.eip.decision.vo;

import java.util.List;

/**
 * The canonical, structured representation of one decision document —
 * what Document_Parser produces and Decision_Formatter consumes (Req 12).
 *
 * <p>The canonical constructor normalizes every component (trim, single-line
 * fields lose embedded newlines, blank → {@code null}, lists never
 * {@code null}), so two documents with the same semantic content are
 * {@code equals()} regardless of surface formatting. That normalization is
 * what makes the round-trip property (Req 12.4) hold:
 * {@code parse(format(parse(doc))) = parse(doc)}.</p>
 */
public record DecisionDocument(
        String title,
        String status,
        String domain,
        String decisionArea,
        String priorityLevel,
        String classificationType,
        String strategicImportance,
        String decidedBy,
        String decidedAt,
        String dueDate,
        List<String> tags,
        String description,
        String rationale,
        List<Person> involvedPeople,
        List<Area> affectedAreas,
        List<Milestone> milestones) {

    public record Person(String name, String role, String participation) {
        public Person {
            name = line(name);
            role = line(role);
            participation = line(participation);
        }
    }

    public record Area(String kind, String name) {
        public Area {
            kind = line(kind);
            name = line(name);
        }
    }

    public record Milestone(String name, String dueDate, String status) {
        public Milestone {
            name = line(name);
            dueDate = line(dueDate);
            status = line(status);
        }
    }

    public DecisionDocument {
        title = nullIfBlank(singleLine(title));
        status = nullIfBlank(singleLine(status));
        domain = nullIfBlank(singleLine(domain));
        decisionArea = nullIfBlank(singleLine(decisionArea));
        priorityLevel = nullIfBlank(singleLine(priorityLevel));
        classificationType = nullIfBlank(singleLine(classificationType));
        strategicImportance = nullIfBlank(singleLine(strategicImportance));
        decidedBy = nullIfBlank(singleLine(decidedBy));
        decidedAt = nullIfBlank(singleLine(decidedAt));
        dueDate = nullIfBlank(singleLine(dueDate));
        // Commas are the tag separator in the canonical format — normalize them
        // away inside a tag so parse∘format is lossless.
        tags = tags == null ? List.of()
            : tags.stream().map(t -> singleLine(t == null ? "" : t.replace(',', ' ')))
                .filter(t -> !t.isBlank()).toList();
        description = nullIfBlank(multiLine(description));
        rationale = nullIfBlank(multiLine(rationale));
        involvedPeople = involvedPeople == null ? List.of() : List.copyOf(involvedPeople);
        affectedAreas = affectedAreas == null ? List.of() : List.copyOf(affectedAreas);
        milestones = milestones == null ? List.of() : List.copyOf(milestones);
    }

    /** In-list field normalization: trimmed, no pipes (the list-line separator), no newlines. */
    private static String line(String s) {
        return s == null ? "" : s.replace('|', '/').replaceAll("\\s+", " ").strip();
    }

    private static String singleLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").strip();
    }

    /** Multi-line bodies keep paragraph structure but normalize line endings and edge whitespace. */
    private static String multiLine(String s) {
        if (s == null) return "";
        return s.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
