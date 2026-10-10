package io.orphos.eip.admin.service;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import com.github.mustachejava.MustacheFactory;
import io.orphos.eip.admin.service.AuditService;
import io.orphos.eip.admin.vo.EmailDtos;
import io.orphos.eip.admin.repository.EmailRepository;
import org.springframework.stereotype.Service;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * C3 + C4 - email template customisation and validation.
 *
 * Templates are stored as Mustache source. Variable references are extracted
 * via a regex pre-pass so we can validate that all {@code requiredVariables}
 * are actually used (and warn about ones that look like typos).
 */
@Service
public class TemplateService {

    private static final Pattern VAR_REF = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_\\.]*)\\s*}}");

    private final EmailRepository repo;
    private final AuditService audit;
    private final MustacheFactory mustacheFactory = new DefaultMustacheFactory();

    public TemplateService(EmailRepository repo, AuditService audit) {
        this.repo = repo;
        this.audit = audit;
    }

    public List<EmailDtos.EmailTemplateView> list() {
        return repo.listTemplates().stream().map(TemplateService::toView).toList();
    }

    public EmailDtos.EmailTemplateView upsert(EmailDtos.EmailTemplateRequest req, String actorKey) {
        EmailDtos.TemplateValidationReport report = validate(req);
        if (!report.ok()) {
            throw new IllegalArgumentException("template invalid: " + String.join("; ", report.issues()));
        }
        Map<String, Object> t = new HashMap<>();
        t.put("code", req.code());
        t.put("name", req.name());
        t.put("subject", req.subject());
        t.put("bodyHtml", req.bodyHtml());
        t.put("bodyText", req.bodyText() == null ? "" : req.bodyText());
        t.put("requiredVariables", req.requiredVariables() == null ? List.of() : req.requiredVariables());
        repo.upsertTemplate(t);
        audit.record(actorKey, "EMAIL_TEMPLATE_UPSERT", req.code(),
                Map.of("name", req.name()));
        return repo.findTemplate(req.code()).map(TemplateService::toView)
                .orElseThrow(() -> new IllegalStateException("template not persisted"));
    }

    public void delete(String code, String actorKey) {
        repo.deleteTemplate(code);
        audit.record(actorKey, "EMAIL_TEMPLATE_DELETE", code, Map.of());
    }

    public EmailDtos.TemplateValidationReport validate(EmailDtos.EmailTemplateRequest req) {
        List<String> issues = new ArrayList<>();
        Set<String> declared = extractVariables(req.subject() + " " + req.bodyHtml() + " " + req.bodyText());

        if (req.subject() == null || req.subject().isBlank()) issues.add("subject required");
        if (req.bodyHtml() == null || req.bodyHtml().isBlank()) issues.add("bodyHtml required");

        if (req.requiredVariables() != null) {
            for (String r : req.requiredVariables()) {
                if (!declared.contains(r)) issues.add("declared required variable '" + r + "' not used in template");
            }
        }
        // Try to compile - catches malformed mustache.
        try { mustacheFactory.compile(new StringReader(req.bodyHtml() == null ? "" : req.bodyHtml()), req.code()); }
        catch (Exception e) { issues.add("body html does not compile: " + e.getMessage()); }
        try { mustacheFactory.compile(new StringReader(req.subject() == null ? "" : req.subject()), req.code() + "-subj"); }
        catch (Exception e) { issues.add("subject does not compile: " + e.getMessage()); }

        return new EmailDtos.TemplateValidationReport(issues.isEmpty(), issues,
                new ArrayList<>(declared));
    }

    /** Compile + render {@code code} against the supplied variables. */
    public Rendered render(String code, Map<String, Object> vars) {
        Map<String, Object> t = repo.findTemplate(code)
                .orElseThrow(() -> new IllegalArgumentException("unknown template: " + code));
        Mustache subj = mustacheFactory.compile(new StringReader(Objects.toString(t.get("subject"))),
                code + "-subj");
        Mustache html = mustacheFactory.compile(new StringReader(Objects.toString(t.get("bodyHtml"))),
                code + "-html");
        Mustache text = mustacheFactory.compile(
                new StringReader(Objects.toString(t.getOrDefault("bodyText", ""), "")), code + "-text");

        StringWriter s = new StringWriter();
        subj.execute(s, vars == null ? Map.of() : vars);
        StringWriter h = new StringWriter();
        html.execute(h, vars == null ? Map.of() : vars);
        StringWriter x = new StringWriter();
        text.execute(x, vars == null ? Map.of() : vars);
        return new Rendered(s.toString(), h.toString(), x.toString());
    }

    private static Set<String> extractVariables(String src) {
        Set<String> out = new LinkedHashSet<>();
        if (src == null) return out;
        Matcher m = VAR_REF.matcher(src);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    @SuppressWarnings("unchecked")
    private static EmailDtos.EmailTemplateView toView(Map<String, Object> r) {
        return new EmailDtos.EmailTemplateView(
                Objects.toString(r.get("id")),
                Objects.toString(r.get("code")),
                Objects.toString(r.get("name")),
                Objects.toString(r.get("subject")),
                Objects.toString(r.getOrDefault("bodyHtml", "")),
                Objects.toString(r.getOrDefault("bodyText", "")),
                (List<String>) r.getOrDefault("requiredVariables", List.of()),
                parseInstant(r.get("updatedAt")));
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return Instant.EPOCH;
        try { return Instant.parse(o.toString()); } catch (Exception e) { return Instant.EPOCH; }
    }

    public record Rendered(String subject, String html, String text) {}
}
