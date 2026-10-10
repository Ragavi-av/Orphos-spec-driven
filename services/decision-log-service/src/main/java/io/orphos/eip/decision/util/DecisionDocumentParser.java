package io.orphos.eip.decision.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.orphos.eip.decision.exception.DecisionParseException;
import io.orphos.eip.decision.vo.DecisionDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Document_Parser (Req 12.1/12.2/12.5): parses decision documents into
 * structured {@link DecisionDocument} objects. Three text shapes are
 * understood:
 * <ul>
 *   <li><b>JSON</b> — a serialized DecisionDocument (starts with '{')</li>
 *   <li><b>Canonical markdown</b> — the format {@link DecisionFormatter}
 *       emits ({@code # Decision: ...} + metadata list + sections)</li>
 *   <li><b>Plain text</b> — first non-empty line becomes the title, the rest
 *       the description (how text extracted from PDFs/Word lands here)</li>
 * </ul>
 * Invalid input raises {@link DecisionParseException} with per-line issues;
 * recoverable oddities are returned as warnings alongside the document.
 */
public class DecisionDocumentParser {

    /** Successful parse: the document plus non-fatal warnings (line-located). */
    public record ParseResult(DecisionDocument document, List<DecisionParseException.Issue> warnings) {}

    private static final List<String> KNOWN_SECTIONS =
        List.of("description", "rationale", "involved people", "affected areas", "milestones");

    private final ObjectMapper mapper = new ObjectMapper();

    public ParseResult parse(String content) {
        if (content == null || content.isBlank()) {
            throw new DecisionParseException(List.of(
                new DecisionParseException.Issue(0, "Document is empty")));
        }
        String text = content.replace("﻿", "").strip();
        if (text.startsWith("{")) return parseJson(text);
        if (text.contains("# Decision:")) return parseMarkdown(text);
        return parsePlainText(text);
    }

    // ------------------------------------------------------------------ JSON

    private ParseResult parseJson(String text) {
        JsonNode root;
        try {
            root = mapper.readTree(text);
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "invalid JSON" : e.getMessage();
            int line = extractJacksonLine(msg);
            throw new DecisionParseException(List.of(
                new DecisionParseException.Issue(line, "Invalid JSON: " + firstLine(msg))));
        }
        List<DecisionParseException.Issue> warnings = new ArrayList<>();
        String title = root.path("title").asText("");
        if (title.isBlank()) {
            throw new DecisionParseException(List.of(
                new DecisionParseException.Issue(0, "Missing required field: title")));
        }
        DecisionDocument doc = new DecisionDocument(
            title,
            root.path("status").asText(null), root.path("domain").asText(null),
            root.path("decisionArea").asText(null), root.path("priorityLevel").asText(null),
            root.path("classificationType").asText(null), root.path("strategicImportance").asText(null),
            root.path("decidedBy").asText(null), root.path("decidedAt").asText(null),
            root.path("dueDate").asText(null),
            readStringList(root, "tags"),
            root.path("description").asText(null), root.path("rationale").asText(null),
            readList(root, "involvedPeople",
                n -> new DecisionDocument.Person(n.path("name").asText(""), n.path("role").asText(""),
                    n.path("participation").asText(""))),
            readList(root, "affectedAreas",
                n -> new DecisionDocument.Area(n.path("kind").asText(""), n.path("name").asText(""))),
            readList(root, "milestones",
                n -> new DecisionDocument.Milestone(n.path("name").asText(""), n.path("dueDate").asText(""),
                    n.path("status").asText(""))));
        return new ParseResult(doc, warnings);
    }

    // ------------------------------------------------------------------ canonical markdown

    private ParseResult parseMarkdown(String text) {
        List<DecisionParseException.Issue> errors = new ArrayList<>();
        List<DecisionParseException.Issue> warnings = new ArrayList<>();
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);

        Map<String, String> meta = new java.util.LinkedHashMap<>();
        StringBuilder description = new StringBuilder();
        StringBuilder rationale = new StringBuilder();
        List<DecisionDocument.Person> people = new ArrayList<>();
        List<DecisionDocument.Area> areas = new ArrayList<>();
        List<DecisionDocument.Milestone> milestones = new ArrayList<>();
        String title = null;
        String section = "meta";

        for (int i = 0; i < lines.length; i++) {
            String raw = lines[i];
            String line = raw.strip();
            int lineNo = i + 1;
            if (line.startsWith("# Decision:")) {
                title = line.substring("# Decision:".length()).strip();
                if (title.isBlank()) {
                    errors.add(new DecisionParseException.Issue(lineNo, "Decision title is empty"));
                }
                continue;
            }
            if (line.startsWith("## ")) {
                section = line.substring(3).strip().toLowerCase(Locale.ROOT);
                if (!KNOWN_SECTIONS.contains(section)) {
                    warnings.add(new DecisionParseException.Issue(lineNo,
                        "Unknown section '" + line.substring(3).strip() + "' — content ignored"));
                    section = "ignored";
                }
                continue;
            }
            switch (section) {
                case "meta" -> {
                    if (line.isBlank()) continue;
                    if (line.startsWith("- ")) {
                        int colon = line.indexOf(':');
                        if (colon < 0) {
                            errors.add(new DecisionParseException.Issue(lineNo,
                                "Metadata entry has no ':' separator: '" + line + "'"));
                        } else {
                            meta.put(line.substring(2, colon).strip().toLowerCase(Locale.ROOT),
                                line.substring(colon + 1).strip());
                        }
                    } else if (title != null) {
                        warnings.add(new DecisionParseException.Issue(lineNo,
                            "Unexpected text before first section — ignored: '" + truncate(line, 60) + "'"));
                    }
                }
                case "description" -> appendBody(description, raw);
                case "rationale" -> appendBody(rationale, raw);
                case "involved people" -> parsePipeLine(line, lineNo, 3, errors,
                    parts -> people.add(new DecisionDocument.Person(parts[0], parts[1], parts[2])),
                    "Involved People entries need 'name | role | participation'");
                case "affected areas" -> parsePipeLine(line, lineNo, 2, errors,
                    parts -> areas.add(new DecisionDocument.Area(parts[0], parts[1])),
                    "Affected Areas entries need 'KIND | name'");
                case "milestones" -> parsePipeLine(line, lineNo, 3, errors,
                    parts -> milestones.add(new DecisionDocument.Milestone(parts[0], parts[1], parts[2])),
                    "Milestones entries need 'name | dueDate | status'");
                default -> { /* ignored section */ }
            }
        }

        if (title == null) {
            errors.add(new DecisionParseException.Issue(0, "Missing '# Decision: <title>' heading"));
        }
        if (!errors.isEmpty()) throw new DecisionParseException(errors);

        DecisionDocument doc = new DecisionDocument(
            title, meta.get("status"), meta.get("domain"), meta.get("decision area"),
            meta.get("priority"), meta.get("classification"), meta.get("strategic importance"),
            meta.get("decided by"), meta.get("decided at"), meta.get("due date"),
            splitTags(meta.get("tags")),
            description.toString(), rationale.toString(), people, areas, milestones);
        return new ParseResult(doc, warnings);
    }

    // ------------------------------------------------------------------ plain text

    private ParseResult parsePlainText(String text) {
        String[] lines = text.split("\n", 2);
        String title = lines[0].strip();
        String body = lines.length > 1 ? lines[1] : null;
        List<DecisionParseException.Issue> warnings = new ArrayList<>();
        warnings.add(new DecisionParseException.Issue(1,
            "Unstructured document: first line taken as title, remainder as description"));
        DecisionDocument doc = new DecisionDocument(title, null, null, null, null, null, null,
            null, null, null, null, body, null, null, null, null);
        return new ParseResult(doc, warnings);
    }

    // ------------------------------------------------------------------ helpers

    private static void appendBody(StringBuilder sb, String raw) {
        if (!sb.isEmpty()) sb.append('\n');
        sb.append(raw);
    }

    private static void parsePipeLine(String line, int lineNo, int expectedParts,
                                      List<DecisionParseException.Issue> errors,
                                      java.util.function.Consumer<String[]> consumer, String hint) {
        if (line.isBlank()) return;
        if (!line.startsWith("- ")) {
            errors.add(new DecisionParseException.Issue(lineNo,
                "List entries must start with '- ' (" + hint + "): '" + truncate(line, 60) + "'"));
            return;
        }
        String[] rawParts = line.substring(2).split("\\|", -1);
        if (rawParts.length > expectedParts) {
            errors.add(new DecisionParseException.Issue(lineNo,
                "Too many '|' separators (" + hint + "): '" + truncate(line, 60) + "'"));
            return;
        }
        String[] parts = new String[expectedParts];
        for (int p = 0; p < expectedParts; p++) {
            parts[p] = p < rawParts.length ? rawParts[p].strip() : "";
        }
        if (parts[0].isBlank()) {
            errors.add(new DecisionParseException.Issue(lineNo,
                "First field must not be empty (" + hint + ")"));
            return;
        }
        consumer.accept(parts);
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) return List.of();
        return List.of(tags.split(",")).stream().map(String::strip).filter(t -> !t.isBlank()).toList();
    }

    private <T> List<T> readList(JsonNode root, String field, java.util.function.Function<JsonNode, T> fn) {
        JsonNode arr = root.path(field);
        if (!arr.isArray()) return List.of();
        List<T> out = new ArrayList<>();
        arr.forEach(n -> out.add(fn.apply(n)));
        return out;
    }

    private List<String> readStringList(JsonNode root, String field) {
        JsonNode arr = root.path(field);
        if (!arr.isArray()) return List.of();
        try {
            return mapper.convertValue(arr, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static int extractJacksonLine(String message) {
        var m = java.util.regex.Pattern.compile("line: (\\d+)").matcher(message);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
