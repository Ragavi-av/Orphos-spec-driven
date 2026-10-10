package io.orphos.eip.decision.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.decision.vo.DecisionDocument;

/**
 * Decision_Formatter (Req 12.3): renders a {@link DecisionDocument} back into
 * its canonical markdown form (or JSON). The markdown output is exactly the
 * shape {@link DecisionDocumentParser} consumes, which is what guarantees the
 * round-trip property {@code parse(format(parse(doc))) = parse(doc)}
 * (Req 12.4).
 */
public class DecisionFormatter {

    private final ObjectMapper mapper = new ObjectMapper();

    public String toMarkdown(DecisionDocument doc) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Decision: ").append(doc.title() == null ? "Untitled" : doc.title()).append("\n\n");

        meta(sb, "Status", doc.status());
        meta(sb, "Domain", doc.domain());
        meta(sb, "Decision Area", doc.decisionArea());
        meta(sb, "Priority", doc.priorityLevel());
        meta(sb, "Classification", doc.classificationType());
        meta(sb, "Strategic Importance", doc.strategicImportance());
        meta(sb, "Decided By", doc.decidedBy());
        meta(sb, "Decided At", doc.decidedAt());
        meta(sb, "Due Date", doc.dueDate());
        if (!doc.tags().isEmpty()) meta(sb, "Tags", String.join(", ", doc.tags()));

        if (doc.description() != null) {
            sb.append("\n## Description\n").append(doc.description()).append('\n');
        }
        if (doc.rationale() != null) {
            sb.append("\n## Rationale\n").append(doc.rationale()).append('\n');
        }
        if (!doc.involvedPeople().isEmpty()) {
            sb.append("\n## Involved People\n");
            for (DecisionDocument.Person p : doc.involvedPeople()) {
                sb.append("- ").append(p.name()).append(" | ").append(p.role())
                    .append(" | ").append(p.participation()).append('\n');
            }
        }
        if (!doc.affectedAreas().isEmpty()) {
            sb.append("\n## Affected Areas\n");
            for (DecisionDocument.Area a : doc.affectedAreas()) {
                sb.append("- ").append(a.kind()).append(" | ").append(a.name()).append('\n');
            }
        }
        if (!doc.milestones().isEmpty()) {
            sb.append("\n## Milestones\n");
            for (DecisionDocument.Milestone m : doc.milestones()) {
                sb.append("- ").append(m.name()).append(" | ").append(m.dueDate())
                    .append(" | ").append(m.status()).append('\n');
            }
        }
        return sb.toString();
    }

    public String toJson(DecisionDocument doc) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(doc);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize decision document", e);
        }
    }

    private static void meta(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append("- ").append(label).append(": ").append(value).append('\n');
        }
    }
}
