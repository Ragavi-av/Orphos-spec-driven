package io.orphos.eip.admin.service;

import io.orphos.eip.admin.vo.EmailDtos;
import io.orphos.eip.admin.repository.EmailRepository;
import jakarta.mail.internet.MimeMessage;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * C5 - delivery testing.
 *
 * Constructs a JavaMailSender from the currently-active EmailConfig and a
 * resolved password, renders a template, and sends. Records the outcome as
 * an EmailEvent so the analytics page reflects test sends too.
 */
@Service
public class EmailSendService {

    private static final Logger log = LogManager.getLogger(EmailSendService.class);

    private final EmailRepository repo;
    private final EmailConfigService configService;
    private final TemplateService templateService;

    @Value("${admin.email.test-recipient-domain-allowlist:}")
    private List<String> testAllowlist;

    public EmailSendService(EmailRepository repo, EmailConfigService configService,
                            TemplateService templateService) {
        this.repo = repo;
        this.configService = configService;
        this.templateService = templateService;
    }

    public EmailDtos.DeliveryResult sendTest(EmailDtos.TestEmailRequest req) {
        if (testAllowlist != null && !testAllowlist.isEmpty()) {
            String to = req.to();
            String domain = to.substring(to.indexOf('@') + 1);
            if (!testAllowlist.contains(domain)) {
                throw new IllegalArgumentException(
                        "recipient domain not on the test allowlist");
            }
        }

        TemplateService.Rendered rendered = templateService.render(
                req.templateCode() == null ? "test" : req.templateCode(),
                req.variables() == null ? Map.of() : req.variables());
        return sendRendered(req.templateCode(), req.to(), rendered);
    }

    /**
     * A composed message for {@link #sendMessage}: recipients, optional
     * threading headers (In-Reply-To/References so mail clients keep the
     * reply in the original conversation), an optional Reply-To (so answers
     * route to the acting user rather than the shared from-address), and an
     * optional inline text/calendar part (meeting invite).
     */
    public record OutboundMessage(
            List<String> to,
            List<String> cc,
            String subject,
            String bodyText,
            String bodyHtml,
            String replyTo,
            String inReplyTo,
            String icsContent,
            String icsMethod) {}

    /**
     * Sends a fully-composed message through the tenant's active SMTP
     * configuration. Used by the email-task agent (mail-insight-service via
     * the internal send endpoint). Outcome is recorded as an EmailEvent under
     * {@code sourceTag} so agent sends show up in delivery analytics.
     */
    public EmailDtos.DeliveryResult sendMessage(String sourceTag, OutboundMessage m) {
        Map<String, Object> raw = configService.findActiveRaw()
                .orElseThrow(() -> new IllegalStateException("no active SMTP configuration"));
        String passwordRef = Objects.toString(raw.getOrDefault("passwordRef", ""), "");
        String password = configService.resolvePassword(passwordRef)
                .orElseThrow(() -> new IllegalStateException(
                        "SMTP password not resolvable (re-enter password to refresh secret reference)"));

        JavaMailSenderImpl sender = buildSender(raw, password);
        String primary = m.to().isEmpty() ? "" : m.to().get(0);
        try {
            MimeMessage msg = sender.createMimeMessage();
            // multipart=true so the optional text/calendar part can ride along.
            MimeMessageHelper h = new MimeMessageHelper(msg, true, "UTF-8");
            h.setFrom(Objects.toString(raw.get("fromAddress")), Objects.toString(raw.get("fromName")));
            h.setTo(m.to().toArray(String[]::new));
            if (m.cc() != null && !m.cc().isEmpty()) h.setCc(m.cc().toArray(String[]::new));
            if (m.replyTo() != null && !m.replyTo().isBlank()) h.setReplyTo(m.replyTo());
            h.setSubject(m.subject());
            String text = m.bodyText() == null ? "" : m.bodyText();
            String html = m.bodyHtml() == null || m.bodyHtml().isBlank()
                    ? text.replace("\n", "<br/>") : m.bodyHtml();
            h.setText(text, html);
            if (m.inReplyTo() != null && !m.inReplyTo().isBlank()) {
                msg.setHeader("In-Reply-To", m.inReplyTo());
                msg.setHeader("References", m.inReplyTo());
            }
            if (m.icsContent() != null && !m.icsContent().isBlank()) {
                String method = m.icsMethod() == null || m.icsMethod().isBlank() ? "REQUEST" : m.icsMethod();
                h.addAttachment("invite.ics",
                        new org.springframework.core.io.ByteArrayResource(
                                m.icsContent().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "text/calendar; charset=UTF-8; method=" + method);
            }
            sender.send(msg);
            repo.recordEvent(sourceTag, primary, "DELIVERED", "ok");
            return new EmailDtos.DeliveryResult(true, "sent", Instant.now());
        } catch (Exception e) {
            repo.recordEvent(sourceTag, primary, "FAILED", e.getMessage());
            log.warn("Email send failed [source={}]: {}", sourceTag, e.getMessage());
            return new EmailDtos.DeliveryResult(false, e.getMessage(), Instant.now());
        }
    }

    /**
     * Sends an already-rendered message through the tenant's active SMTP
     * configuration. Used by the event-communication framework
     * (EventNotificationService) in addition to {@link #sendTest}; the outcome
     * is recorded as an EmailEvent so event alerts show up in the existing
     * delivery analytics.
     */
    public EmailDtos.DeliveryResult sendRendered(String templateCode, String to,
                                                 TemplateService.Rendered rendered) {
        Map<String, Object> raw = configService.findActiveRaw()
                .orElseThrow(() -> new IllegalStateException("no active SMTP configuration"));
        String passwordRef = Objects.toString(raw.getOrDefault("passwordRef", ""), "");
        String password = configService.resolvePassword(passwordRef)
                .orElseThrow(() -> new IllegalStateException(
                        "SMTP password not resolvable (re-enter password to refresh secret reference)"));

        JavaMailSenderImpl sender = buildSender(raw, password);
        try {
            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(msg, "UTF-8");
            h.setFrom(Objects.toString(raw.get("fromAddress")), Objects.toString(raw.get("fromName")));
            h.setTo(to);
            h.setSubject(rendered.subject());
            h.setText(rendered.text(), rendered.html());
            sender.send(msg);
            repo.recordEvent(templateCode, to, "DELIVERED", "ok");
            return new EmailDtos.DeliveryResult(true, "sent", Instant.now());
        } catch (Exception e) {
            repo.recordEvent(templateCode, to, "FAILED", e.getMessage());
            log.warn("Email send failed [template={}]: {}", templateCode, e.getMessage());
            return new EmailDtos.DeliveryResult(false, e.getMessage(), Instant.now());
        }
    }

    private static JavaMailSenderImpl buildSender(Map<String, Object> raw, String password) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(Objects.toString(raw.get("host")));
        sender.setPort(((Number) raw.getOrDefault("port", 587)).intValue());
        sender.setUsername(Objects.toString(raw.get("username")));
        sender.setPassword(password);
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        String security = Objects.toString(raw.getOrDefault("security", "STARTTLS"));
        switch (security) {
            case "STARTTLS" -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
            case "SSL" -> {
                props.put("mail.smtp.ssl.enable", "true");
            }
            default -> { /* NONE - useful for an internal relay only */ }
        }
        return sender;
    }
}
