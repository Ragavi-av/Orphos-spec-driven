package io.orphos.eip.decision.controller;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.decision.util.AuthSupport;
import io.orphos.eip.decision.vo.DecisionDocument;
import io.orphos.eip.decision.util.DecisionDocumentParser;
import io.orphos.eip.decision.util.DecisionFormatter;
import io.orphos.eip.decision.exception.DecisionParseException;
import io.orphos.eip.decision.component.DocumentTextExtractor;
import io.orphos.eip.decision.service.DecisionService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Document_Parser / Decision_Formatter API (Req 12): parse decision documents
 * (markdown, JSON, plain text, PDF, Word) into structured Decision previews,
 * import them as decisions, and export any decision back to a document.
 * Parse failures return 422 with per-line issue locations (Req 12.2).
 */
@RestController
@RequestMapping("/api/decisions")
public class DecisionDocumentController {

    private final DecisionDocumentParser parser = new DecisionDocumentParser();
    private final DecisionFormatter formatter = new DecisionFormatter();
    private final DocumentTextExtractor extractor;
    private final DecisionService decisions;

    public DecisionDocumentController(DocumentTextExtractor extractor, DecisionService decisions) {
        this.extractor = extractor;
        this.decisions = decisions;
    }

    /** Multipart upload (PDF/Word/text) → parsed preview, without persisting. */
    @PostMapping(value = "/documents/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> parseFile(@RequestPart("file") MultipartFile file) throws Exception {
        TenantContextHolder.requireTenantId();
        String text = toText(file);
        DecisionDocumentParser.ParseResult result = parser.parse(text);
        return ResponseEntity.ok(parseResponse(result));
    }

    /** Raw text/JSON body → parsed preview, without persisting. Body: {@code {content}} */
    @PostMapping(value = "/documents/parse", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> parseText(@RequestBody Map<String, Object> body) {
        TenantContextHolder.requireTenantId();
        DecisionDocumentParser.ParseResult result =
            parser.parse(String.valueOf(body.getOrDefault("content", "")));
        return ResponseEntity.ok(parseResponse(result));
    }

    /** Parses and persists in one step. Multipart {@code file} or JSON {@code {content}}. */
    @PostMapping(value = "/documents/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Object> importFile(@RequestPart("file") MultipartFile file,
                                             Authentication auth) throws Exception {
        return doImport(toText(file), auth);
    }

    @PostMapping(value = "/documents/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Object> importText(@RequestBody Map<String, Object> body, Authentication auth) {
        return doImport(String.valueOf(body.getOrDefault("content", "")), auth);
    }

    /** Formats a stored decision back into a document (Req 12.3). {@code ?format=markdown|json} */
    @GetMapping("/{id}/export")
    public ResponseEntity<Object> export(@PathVariable String id,
                                         @RequestParam(defaultValue = "markdown") String format) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> decision = decisions.get(tenantId, id);
        if (decision == null) return ResponseEntity.notFound().build();
        DecisionDocument doc = toDocument(decision);
        boolean json = "json".equalsIgnoreCase(format);
        String content = json ? formatter.toJson(doc) : formatter.toMarkdown(doc);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=" + id + (json ? ".json" : ".md"))
            .contentType(json ? MediaType.APPLICATION_JSON : MediaType.TEXT_MARKDOWN)
            .body(content);
    }

    @ExceptionHandler(DecisionParseException.class)
    public ResponseEntity<Object> handleParseError(DecisionParseException ex) {
        return ResponseEntity.unprocessableEntity().body(Map.of(
            "error", "Document could not be parsed",
            "issues", ex.issues().stream()
                .map(i -> Map.of("line", i.line(), "message", i.message())).toList()));
    }

    private ResponseEntity<Object> doImport(String text, Authentication auth) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        DecisionDocumentParser.ParseResult result = parser.parse(text);
        Map<String, Object> payload = toPayload(result.document());
        payload.put("detectionSource", "IMPORTED");
        Map<String, Object> created = decisions.create(tenantId, payload, AuthSupport.actor(auth));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("decision", created);
        response.put("warnings", issueList(result.warnings()));
        return ResponseEntity.status(201).body(response);
    }

    private String toText(MultipartFile file) throws Exception {
        byte[] bytes = file.getBytes();
        if (extractor.isTextual(file.getOriginalFilename(), file.getContentType())) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        return extractor.extract(bytes, file.getOriginalFilename());
    }

    private Map<String, Object> parseResponse(DecisionDocumentParser.ParseResult result) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("decision", result.document());
        out.put("warnings", issueList(result.warnings()));
        out.put("canonical", formatter.toMarkdown(result.document()));
        return out;
    }

    private static List<Map<String, Object>> issueList(List<DecisionParseException.Issue> issues) {
        return issues.stream()
            .map(i -> Map.<String, Object>of("line", i.line(), "message", i.message())).toList();
    }

    /** DecisionDocument → create-payload (structured lists ride the JSON aliases DecisionService knows). */
    private static Map<String, Object> toPayload(DecisionDocument doc) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", doc.title());
        put(payload, "status", doc.status());
        put(payload, "domain", doc.domain());
        put(payload, "decisionArea", doc.decisionArea());
        put(payload, "priorityLevel", doc.priorityLevel());
        put(payload, "classificationType", doc.classificationType());
        put(payload, "strategicImportance", doc.strategicImportance());
        put(payload, "decidedBy", doc.decidedBy());
        put(payload, "decidedAt", doc.decidedAt());
        put(payload, "dueDate", doc.dueDate());
        put(payload, "description", doc.description());
        put(payload, "rationale", doc.rationale());
        if (!doc.tags().isEmpty()) payload.put("tags", doc.tags());
        if (!doc.involvedPeople().isEmpty()) {
            payload.put("involvedPeople", doc.involvedPeople().stream()
                .map(p -> Map.of("name", p.name(), "role", p.role(), "participation", p.participation()))
                .toList());
        }
        if (!doc.affectedAreas().isEmpty()) {
            payload.put("affectedAreas", doc.affectedAreas().stream()
                .map(a -> Map.of("kind", a.kind(), "name", a.name())).toList());
        }
        if (!doc.milestones().isEmpty()) {
            payload.put("milestones", doc.milestones().stream()
                .map(m -> Map.of("name", m.name(), "dueDate", m.dueDate(), "status", m.status())).toList());
        }
        return payload;
    }

    /** Stored decision row (expanded) → DecisionDocument for export. */
    @SuppressWarnings("unchecked")
    private static DecisionDocument toDocument(Map<String, Object> d) {
        List<Map<String, Object>> people = (List<Map<String, Object>>) d.getOrDefault("involvedPeople", List.of());
        List<Map<String, Object>> areas = (List<Map<String, Object>>) d.getOrDefault("affectedAreas", List.of());
        List<Map<String, Object>> milestones = (List<Map<String, Object>>) d.getOrDefault("milestones", List.of());
        List<Map<String, Object>> tags = (List<Map<String, Object>>) d.getOrDefault("tags", List.of());
        return new DecisionDocument(
            str(d.get("title")), str(d.get("status")), str(d.get("domain")), str(d.get("decisionArea")),
            str(d.get("priorityLevel")), str(d.get("classificationType")), str(d.get("strategicImportance")),
            str(d.get("decidedBy")), str(d.get("decidedAt")), str(d.get("dueDate")),
            tags.stream().map(t -> str(t.get("name"))).toList(),
            str(d.get("description")), str(d.get("rationale")),
            people.stream().map(p -> new DecisionDocument.Person(
                str(p.get("name")), str(p.get("role")), str(p.get("participation")))).toList(),
            areas.stream().map(a -> new DecisionDocument.Area(
                str(a.get("kind")), str(a.get("name")))).toList(),
            milestones.stream().map(m -> new DecisionDocument.Milestone(
                str(m.get("name")), str(m.get("dueDate")), str(m.get("status")))).toList());
    }

    private static void put(Map<String, Object> payload, String key, String value) {
        if (value != null && !value.isBlank()) payload.put(key, value);
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}
