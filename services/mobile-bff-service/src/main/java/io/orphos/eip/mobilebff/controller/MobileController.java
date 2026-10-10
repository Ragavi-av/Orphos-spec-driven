package io.orphos.eip.mobilebff.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.orphos.eip.common.access.AccessGrantSource;
import io.orphos.eip.common.access.DataDomains;
import io.orphos.eip.common.access.PersonaDomainPolicy;
import io.orphos.eip.common.security.EipRoles;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.mobilebff.component.DownstreamClient;
import io.orphos.eip.mobilebff.repository.CaptureStore;
import io.orphos.eip.mobilebff.repository.DeviceStore;
import io.orphos.eip.mobilebff.repository.WorkspaceStore;
import io.orphos.eip.mobilebff.service.ApprovalService;
import io.orphos.eip.mobilebff.service.MetricDetailService;
import io.orphos.eip.mobilebff.service.MobileConfigService;
import io.orphos.eip.mobilebff.service.PersonaWidgetService;
import io.orphos.eip.mobilebff.service.TravelService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * /api/mobile/* — one-call aggregations for the native apps, plus BFF-owned capture
 * threads and push registration. Aggregations forward the caller's JWT + tenant to the
 * existing platform services and degrade to partial responses if a service is down.
 */
@RestController
@RequestMapping("/api/mobile")
public class MobileController {

    private static final Logger log = LogManager.getLogger(MobileController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DownstreamClient ds;
    private final CaptureStore captures;
    private final DeviceStore devices;
    private final WorkspaceStore workspace;
    private final ApprovalService approvals;
    private final TravelService travel;
    private final MobileConfigService configService;
    private final AccessGrantSource grants;
    private final PersonaWidgetService personaWidgets;
    private final MetricDetailService metricDetails;

    private final String briefingUrl;
    private final String decisionLogUrl;
    private final String priorityUrl;
    private final String mailInsightUrl;
    private final String okgReasoningUrl;
    private final String docIngestUrl;
    private final String platformCoreUrl;
    private final String widgetsUrl;

    /** Highest-privilege first — persona is the first of these the user holds (shared catalog). */
    private static final List<String> ROLE_PRIORITY = EipRoles.PERSONA_PRIORITY;

    public MobileController(
            DownstreamClient ds, CaptureStore captures, DeviceStore devices,
            WorkspaceStore workspace,
            ApprovalService approvals, TravelService travel, MobileConfigService configService,
            AccessGrantSource grants,
            PersonaWidgetService personaWidgets,
            MetricDetailService metricDetails,
            @Value("${eip.downstream.briefing-url:http://eip-briefing:8084}") String briefingUrl,
            @Value("${eip.downstream.decision-log-url:http://eip-decision-log:8086}") String decisionLogUrl,
            @Value("${eip.downstream.priority-url:http://eip-priority-engine:8083}") String priorityUrl,
            @Value("${eip.downstream.mail-insight-url:http://eip-mail-insight:8092}") String mailInsightUrl,
            @Value("${eip.downstream.okg-reasoning-url:http://eip-okg-reasoning:8088}") String okgReasoningUrl,
            @Value("${eip.downstream.doc-ingest-url:http://eip-doc-ingest:8091}") String docIngestUrl,
            @Value("${eip.downstream.platform-core-url:http://eip-platform-core:8090}") String platformCoreUrl,
            @Value("${eip.downstream.widgets-url:http://eip-widgets:8095}") String widgetsUrl) {
        this.ds = ds;
        this.captures = captures;
        this.devices = devices;
        this.workspace = workspace;
        this.approvals = approvals;
        this.travel = travel;
        this.configService = configService;
        this.grants = grants;
        this.personaWidgets = personaWidgets;
        this.metricDetails = metricDetails;
        this.briefingUrl = briefingUrl;
        this.decisionLogUrl = decisionLogUrl;
        this.priorityUrl = priorityUrl;
        this.mailInsightUrl = mailInsightUrl;
        this.okgReasoningUrl = okgReasoningUrl;
        this.docIngestUrl = docIngestUrl;
        this.platformCoreUrl = platformCoreUrl;
        this.widgetsUrl = widgetsUrl;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("status", "ok", "service", "mobile-bff");
    }

    /**
     * Home: one round-trip replaces four (digest + decisions + meetings + priority).
     * {@code lang} and the {@code from}/{@code to} day window are forwarded exactly as the
     * web greeting band and meetings rail send them, so both surfaces get the same digest
     * (briefing caches it per tenant AND language) and the same calendar day.
     */
    @GetMapping("/home")
    public Map<String, Object> home(HttpServletRequest req,
                                    @RequestParam(required = false) String lang,
                                    @RequestParam(required = false) String from,
                                    @RequestParam(required = false) String to) {
        String auth = auth(req), tid = tenant();
        Map<String, Object> out = new LinkedHashMap<>();
        String digestPath = "/api/briefing/daily-digest"
                + (lang == null || lang.isBlank() ? "" : "?lang=" + URLEncoder.encode(lang, StandardCharsets.UTF_8));
        out.put("digest", ds.get(briefingUrl, digestPath, auth, tid));
        out.put("decisions", ds.get(decisionLogUrl, "/api/decisions", auth, tid));
        out.put("meetings", ds.get(mailInsightUrl, meetingsPath(from, to), auth, tid));
        out.put("priority", ds.get(priorityUrl, "/api/priority/queue", auth, tid));
        return out;
    }

    /**
     * Web widget-board metric ids (frontend catalog.js PERSONA_METRIC_IDS) served by
     * widgets-service /api/dashboard/persona-metrics — the mobile tiles read the SAME
     * figures, so a number on the phone is the number on the dashboard.
     */
    private static final List<String> FINANCE_METRICS = List.of(
            "net-cashflow", "cash-inflow", "cash-outflow", "budget-variance", "payroll",
            "revenue-arr", "pipeline-value", "risk-exposure", "objective-progress");
    private static final List<String> PEOPLE_METRICS = List.of("attrition", "payroll");

    /**
     * Business / Team Intelligence: the HR summary and people metrics the web dashboard
     * shows, plus OKG insights. (The old productivity / wellbeing / availability snapshot
     * types were never part of the schema — only demo rows — so they are no longer read.)
     */
    @GetMapping("/team-intelligence")
    public Map<String, Object> teamIntelligence(HttpServletRequest req,
                                                @RequestParam(required = false) String displayCurrency) {
        String auth = auth(req), tid = tenant();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hrSummary", ds.get(briefingUrl, "/api/zenitus/hr/summary", auth, tid));
        out.put("insights", ds.get(okgReasoningUrl, "/api/okg-reasoning/insights", auth, tid));
        out.put("metrics", personaMetrics(PEOPLE_METRICS, displayCurrency, auth, tid));
        return out;
    }

    /**
     * Financials: the web dashboard's sources — briefing cashflow + budget overruns and the
     * widgets-service persona metrics. (Portfolio / credit score / forecast were demo-only
     * ArcadeDB types with no web counterpart, so they are no longer served.)
     */
    @GetMapping("/financials")
    public Map<String, Object> financials(HttpServletRequest req,
                                          @RequestParam(required = false) String displayCurrency) {
        String auth = auth(req), tid = tenant();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cashflow", ds.get(briefingUrl, "/api/zenitus/cashflow/summary", auth, tid));
        JsonNode latest = ds.get(briefingUrl, "/api/briefing/latest", auth, tid);
        out.put("budgetOverruns", latest != null ? latest.get("budgetOverruns") : null);
        out.put("metrics", personaMetrics(FINANCE_METRICS, displayCurrency, auth, tid));
        return out;
    }

    /**
     * Metrics tab: the caller's own web widget board — the persona-resolved widget list
     * with each widget's value — so every role's phone shows the tiles their dashboard does.
     */
    @GetMapping("/metrics")
    public Map<String, Object> metrics(HttpServletRequest req,
                                       @RequestParam(required = false) String displayCurrency) {
        String auth = auth(req), tid = tenant();
        PersonaDomainPolicy.Scope scope = PersonaDomainPolicy.current(grants);
        Map<String, Object> out = personaWidgets.board(scope, displayCurrency, auth, tid);
        out.put("details", metricDetails.details(scope, auth, tid));
        return out;
    }

    /** metric id → widgets-service MetricValue (null when the call failed). */
    private Map<String, JsonNode> personaMetrics(List<String> ids, String displayCurrency, String auth, String tid) {
        String query = displayCurrency == null || displayCurrency.isBlank() ? ""
                : "?displayCurrency=" + URLEncoder.encode(displayCurrency, StandardCharsets.UTF_8);
        Map<String, JsonNode> out = new LinkedHashMap<>();
        for (String id : ids) {
            out.put(id, ds.get(widgetsUrl, "/api/dashboard/persona-metrics/" + id + query, auth, tid));
        }
        return out;
    }

    /** Today's meeting: mail-insight meetings with their AI briefs. */
    /**
     * Meetings passthrough. {@code from}/{@code to} are FORWARDED rather than dropped:
     * mail-insight defaults to a rolling now→now+24h window when they are absent, which
     * is not the same thing as a calendar day. Only the client knows where the tenant's
     * day starts (see the note on MailInsightController#meetings), so a mobile client
     * sending midnight-to-midnight was previously silently downgraded to "next 24 hours"
     * — showing tomorrow morning while hiding meetings earlier today, and disagreeing
     * with the web dashboard, which has always sent the window.
     */
    @GetMapping("/meetings")
    public JsonNode meetings(HttpServletRequest req,
                             @RequestParam(required = false) String from,
                             @RequestParam(required = false) String to) {
        return ds.get(mailInsightUrl, meetingsPath(from, to), auth(req), tenant());
    }

    /**
     * The mail-insight meetings path for a window.
     *
     * The bounds are NOT URL-encoded here. {@code RestClient.uri(String)} encodes the whole
     * string itself, so a pre-encoded {@code %3A} was sent as {@code %253A}. mail-insight
     * then read {@code "…T18%3A30%3A00Z"}, could not parse it, and dropped the bound. Every
     * meeting ever stored came back as "today", on the calendar and on Home alike. Instead
     * each bound is normalised to the {@code Instant} form ({@code 2026-09-24T18:30:00Z}).
     * Those characters are all legal in a query string, and no {@code +} offset survives
     * that the server would decode as a space.
     */
    private static String meetingsPath(String from, String to) {
        StringBuilder path = new StringBuilder("/api/mail-insight/meetings");
        String f = canonicalInstant(from), t = canonicalInstant(to);
        if (f != null && t != null) {
            path.append("?from=").append(f).append("&to=").append(t);
        }
        return path.toString();
    }

    /** An ISO-8601 instant or offset date-time as {@code Instant.toString()}, or null. */
    private static String canonicalInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return java.time.Instant.parse(iso.trim()).toString();
        } catch (Exception e) {
            try {
                return java.time.OffsetDateTime.parse(iso.trim()).toInstant().toString();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    // --- Scribbles: capture index + share/activity threads (owner-scoped, durable) ---

    @GetMapping("/captures")
    public List<Map<String, Object>> listCaptures(HttpServletRequest req) {
        return captures.list(tenant(), ownerSub());
    }

    /**
     * Capture WITHOUT a file (typed note / voice transcript). Persists the record and, when
     * it carries real text content, forwards that text into doc-ingest (sourceChannel=MOBILE)
     * so the note becomes persistent and searchable alongside other ingested data — matching
     * the byte-upload path. Placeholder-only bodies (e.g. "[Captured photo]" from a stub) are
     * skipped; those arrive via /captures/upload with their real bytes.
     *
     * <p>A REVIEWED typed note also carries the {@code summary} and {@code keyPoints} the
     * user confirmed, plus the {@code actions} and {@code decisions} the AI found in it. They are stored next to the original {@code content} (which stays as
     * typed) and indexed with it, so Web Document Search matches either one.</p>
     */
    @PostMapping("/captures")
    public Map<String, Object> addCapture(HttpServletRequest req, @RequestBody Map<String, Object> capture) {
        String tid = tenant(), auth = auth(req), owner = ownerSub();
        Map<String, Object> record = new LinkedHashMap<>(capture);
        String summary = asText(capture.get("summary")).strip();
        if (summary.isEmpty()) record.remove("summary"); else record.put("summary", summary);
        List<String> keyPoints = keyPointList(capture.get("keyPoints"));
        if (keyPoints.isEmpty()) record.remove("keyPoints"); else record.put("keyPoints", keyPoints);
        // Kept even when empty: [] means the AI found none, absent means it never looked.
        for (String field : List.of("actions", "decisions")) {
            if (capture.get(field) instanceof List<?>) record.put(field, keyPointList(capture.get(field)));
            else record.remove(field);
        }

        Map<String, Object> stored = captures.add(tid, owner, record);
        ingestCapture(tid, auth, owner, stored, null);
        return stored;
    }

    /**
     * Retries the doc-ingest forward for the caller's captures that never made it into
     * Document Search (doc-ingest was down, or — before /api/doc-ingest/captures — a
     * non-admin's forward was refused). Idempotent: captures already ingested are skipped.
     * Returns {@code {pending, ingested}}.
     */
    @PostMapping("/captures/reingest")
    public Map<String, Object> reingestCaptures(HttpServletRequest req) {
        String tid = tenant(), auth = auth(req), owner = ownerSub();
        int pending = 0, ingested = 0;
        for (Map<String, Object> c : captures.list(tid, owner)) {
            if (Boolean.TRUE.equals(c.get("ingested"))) continue;
            if (pending >= REINGEST_BATCH) break;
            byte[] raw = null;
            if (!isReviewed(c) && Boolean.TRUE.equals(c.get("hasFile"))) {
                CaptureStore.StoredFile f = captures.file(tid, owner, String.valueOf(c.get("id")));
                raw = f == null ? null : f.bytes();
            }
            if (!isReviewed(c) && raw == null) continue;   // nothing that can be indexed
            pending++;
            if (ingestCapture(tid, auth, owner, c, raw)) ingested++;
        }
        return Map.of("pending", pending, "ingested", ingested);
    }

    /** Upper bound on captures re-sent per call, so one request can't fan out unbounded. */
    private static final int REINGEST_BATCH = 25;

    /**
     * Sends a saved capture to doc-ingest as the caller's OWN document — searchable by
     * them in Web Document Search, invisible to colleagues — and records the outcome
     * ({@code ingested}, {@code documentId}) on the capture. A reviewed capture indexes
     * the text the user confirmed ({@link #searchableText}); an unreviewed one sends
     * {@code rawBytes} (OCR / transcription happen in doc-ingest). Best-effort: the
     * capture is already persisted, so a failure is logged, never thrown.
     *
     * <p>Goes to {@code /captures}, not {@code /upload}: {@code /upload} is the admin Doc
     * Ingestion tab, gated on {@code doc-upload}, which refused every non-admin's note
     * (and {@link DownstreamClient#postFile} turns that 403 into a silent null).</p>
     */
    private boolean ingestCapture(String tid, String auth, String owner,
                                  Map<String, Object> stored, byte[] rawBytes) {
        try {
            String title = asText(stored.get("title")).strip();
            String name = title.isEmpty() ? "Mobile note" : title;
            byte[] body;
            String fileName;
            if (isReviewed(stored)) {
                body = searchableText(stored).getBytes(StandardCharsets.UTF_8);
                fileName = name.replaceAll("[^A-Za-z0-9._-]", "_") + ".txt";
            } else if (rawBytes != null) {
                body = rawBytes;
                String original = asText(stored.get("fileName"));
                fileName = original.isBlank() ? "capture" : original;
            } else {
                return false;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("title", name);
            fields.put("inputType", mapInputType(asText(stored.get("type"))));
            JsonNode ingest = ds.postFile(docIngestUrl, "/api/doc-ingest/captures", auth, tid,
                    body, fileName, fields);
            stored.put("ingested", ingest != null);
            if (ingest != null && ingest.get("documentId") != null) {
                stored.put("documentId", ingest.get("documentId").asText());
            }
            if (ingest == null) {
                log.warn("Capture {} saved but not sent to Document Search (doc-ingest refused or unreachable)",
                        stored.get("id"));
            }
            captures.update(tid, owner, stored);
            return ingest != null;
        } catch (Exception e) {
            log.warn("Capture {} ingest not recorded: {}", stored.get("id"), e.getMessage());
            return false;
        }
    }

    /** True once the user has confirmed text or a summary for the capture on the review screen. */
    private static boolean isReviewed(Map<String, Object> capture) {
        return isIngestableText(asText(capture.get("content")))
                || !asText(capture.get("summary")).isBlank();
    }

    /** Key points from a JSON body: a list of strings, blanks dropped. */
    private static List<String> keyPointList(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object p : list) {
                String s = asText(p).strip();
                if (!s.isEmpty()) out.add(s);
            }
        }
        return out;
    }

    private static String asText(Object o) { return o == null ? "" : o.toString(); }

    /** True for real note text worth ingesting — non-trivial and not a client "[placeholder]". */
    private static boolean isIngestableText(String s) {
        String t = s == null ? "" : s.trim();
        if (t.length() < 3) return false;
        return !(t.startsWith("[") && t.endsWith("]"));
    }

    /**
     * Review step before a capture is saved: recognise and AI-summarise it WITHOUT
     * persisting anything. {@code text} is what the device already recognised (on-device
     * handwriting recognition); without it the {@code file} is OCR'd by doc-ingest.
     * Returns {text, source, title?, summary?, keyPoints?, actions?, decisions?}.
     */
    @PostMapping(value = "/captures/extract", consumes = "multipart/form-data")
    public JsonNode extractCapture(
            HttpServletRequest req,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "text", required = false) String text) throws Exception {
        boolean hasFile = file != null && !file.isEmpty();
        if (!hasFile && (text == null || text.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "file or text is required");
        }
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("text", text);
        JsonNode result = ds.postFile(docIngestUrl, "/api/doc-ingest/extract", auth(req), tenant(),
                hasFile ? file.getBytes() : null,
                hasFile && file.getOriginalFilename() != null ? file.getOriginalFilename() : "capture",
                fields);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Capture extraction is unavailable");
        }
        return result;
    }

    /**
     * Capture WITH its file (photo/voice/handwriting): persist the capture record and its
     * original bytes, AND forward the content to doc-ingest so it lands in the shared
     * ingestion storage, tagged as inputType + sourceChannel=MOBILE. The capture is stored
     * even if the doc-ingest forward fails, so nothing is lost.
     *
     * <p>A REVIEWED capture carries {@code content} (the recognised text as the user
     * corrected it), {@code summary} and {@code keyPoints} (a JSON array). That text is what
     * gets ingested — re-OCRing the image would index the uncorrected recognition — so Web
     * Document Search finds the note by what the user confirmed it says. Without
     * {@code content} the raw bytes are ingested (OCR for images, transcription for audio).</p>
     *
     * <p>A reviewed voice note also carries {@code transcript}: the speech-to-text output
     * exactly as Whisper returned it, before the user's edits. It is stored untouched next
     * to {@code content} and indexed with it, so the original words stay searchable even
     * when the reviewed text was corrected.</p>
     */
    @PostMapping(value = "/captures/upload", consumes = "multipart/form-data")
    public Map<String, Object> uploadCapture(
            HttpServletRequest req,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "mimeType", required = false) String mimeType,
            @RequestParam(value = "content", required = false) String content,
            @RequestParam(value = "summary", required = false) String summary,
            @RequestParam(value = "keyPoints", required = false) String keyPointsJson,
            @RequestParam(value = "actions", required = false) String actionsJson,
            @RequestParam(value = "decisions", required = false) String decisionsJson,
            @RequestParam(value = "transcript", required = false) String transcript) throws Exception {

        String tid = tenant(), auth = auth(req), owner = ownerSub();
        String fileName = file.getOriginalFilename() != null ? file.getOriginalFilename() : "capture";
        String name = title == null || title.isBlank() ? fileName : title.strip();
        // An image with no readable text can still be confirmed with a summary the user wrote.
        boolean reviewed = (content != null && !content.isBlank()) || (summary != null && !summary.isBlank());
        byte[] bytes = file.getBytes();

        // 1) persist the capture record (owner-scoped) and its original bytes.
        Map<String, Object> capture = new LinkedHashMap<>();
        capture.put("title", name);
        capture.put("type", type);
        capture.put("mimeType", mimeType);
        capture.put("fileName", fileName);
        if (reviewed) {
            capture.put("content", content == null ? "" : content.strip());
            if (summary != null && !summary.isBlank()) capture.put("summary", summary.strip());
            List<String> keyPoints = parseKeyPoints(keyPointsJson);
            if (!keyPoints.isEmpty()) capture.put("keyPoints", keyPoints);
            // Kept even when empty: [] means the AI found none, absent means it never looked.
            if (actionsJson != null) capture.put("actions", parseKeyPoints(actionsJson));
            if (decisionsJson != null) capture.put("decisions", parseKeyPoints(decisionsJson));
            if (transcript != null && !transcript.isBlank()) capture.put("transcript", transcript.strip());
        }
        Map<String, Object> stored = captures.add(tid, owner, capture);
        try {
            captures.addFile(tid, owner, String.valueOf(stored.get("id")), mimeType, bytes);
            stored.put("hasFile", true);
        } catch (Exception e) {
            log.warn("Capture {} saved without its original file: {}", stored.get("id"), e.getMessage());
        }

        // 2) forward into doc-ingest (best-effort) so it is searchable by its owner.
        ingestCapture(tid, auth, owner, stored, bytes);
        return stored;
    }

    /** One of the caller's captures, in full. */
    @GetMapping("/captures/{id}")
    public Map<String, Object> capture(@PathVariable String id) {
        Map<String, Object> c = captures.get(tenant(), ownerSub(), id);
        if (c == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Capture not found");
        return c;
    }

    /** The original bytes of one of the caller's captures (e.g. the handwriting image). */
    @GetMapping("/captures/{id}/file")
    public ResponseEntity<byte[]> captureFile(@PathVariable String id) {
        CaptureStore.StoredFile f = captures.file(tenant(), ownerSub(), id);
        if (f == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Capture file not found");
        MediaType type;
        try {
            type = MediaType.parseMediaType(f.mimeType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return ResponseEntity.ok().contentType(type).body(f.bytes());
    }

    /**
     * The document doc-ingest indexes for a reviewed capture: the text, the original
     * transcript when the user changed it, then the summary, key points, actions and decisions.
     */
    static String searchableText(Map<String, Object> capture) {
        StringBuilder sb = new StringBuilder();
        sb.append(asText(capture.get("title"))).append("\n\n");
        String content = asText(capture.get("content"));
        sb.append(content).append("\n");
        String transcript = asText(capture.get("transcript"));
        if (!transcript.isBlank() && !transcript.strip().equals(content.strip())) {
            sb.append("\nOriginal transcription:\n").append(transcript).append("\n");
        }
        String summary = asText(capture.get("summary"));
        if (!summary.isBlank()) sb.append("\nSummary:\n").append(summary).append("\n");
        appendList(sb, "Key points", capture.get("keyPoints"));
        appendList(sb, "Actions", capture.get("actions"));
        appendList(sb, "Decisions needed", capture.get("decisions"));
        return sb.toString();
    }

    private static void appendList(StringBuilder sb, String heading, Object value) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            sb.append("\n").append(heading).append(":\n");
            for (Object p : list) sb.append("- ").append(p).append("\n");
        }
    }

    private static List<String> parseKeyPoints(String json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            for (JsonNode n : JSON.readTree(json)) {
                String s = n.asText("").strip();
                if (!s.isEmpty()) out.add(s);
            }
        } catch (Exception e) {
            log.debug("Ignoring unreadable keyPoints: {}", e.getMessage());
        }
        return out;
    }

    @GetMapping("/captures/{id}/thread")
    public List<Map<String, Object>> thread(@PathVariable String id) {
        requireOwnCapture(id);
        return captures.thread(tenant(), id);
    }

    @PostMapping("/captures/{id}/thread")
    public Map<String, Object> postMessage(HttpServletRequest req, @PathVariable String id,
                                           @RequestBody Map<String, Object> message) {
        requireOwnCapture(id);
        return captures.addMessage(tenant(), ownerSub(), id, message);
    }

    /**
     * A capture's thread belongs to the capture's owner. Thread rows are keyed by capture
     * id alone, so without this any user in the tenant who learned an id could read or
     * append to someone else's thread. 404 rather than 403, so ids can't be probed.
     */
    private void requireOwnCapture(String captureId) {
        if (captures.get(tenant(), ownerSub(), captureId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Capture not found");
        }
    }

    // --- Push device registration (priority-contact router) ---

    @GetMapping("/devices")
    public List<Map<String, Object>> listDevices(HttpServletRequest req) {
        return devices.devices(tenant(), ownerSub());
    }

    @PostMapping("/devices")
    public Map<String, Object> registerDevice(HttpServletRequest req, @RequestBody Map<String, Object> device) {
        return devices.register(tenant(), ownerSub(), device);
    }

    // --- Approvals: auto-inferred + persistent; the exec decision is the only write ---

    /** Inferred + persisted approvals for this owner, with expiry applied. */
    @GetMapping("/approvals")
    public List<Map<String, Object>> listApprovals(HttpServletRequest req) {
        return inPersonaScope(approvals.listAndSync(tenant(), ownerSub(), auth(req)));
    }

    /** Record the executive's decision. Body: {"decision":"APPROVE|REJECT|YES|NO|GO|HOLD"}. */
    @PostMapping("/approvals/{id}/decision")
    public Map<String, Object> decideApproval(HttpServletRequest req, @PathVariable String id,
                                              @RequestBody Map<String, Object> body) {
        try {
            Map<String, Object> updated = approvals.decide(
                    tenant(), ownerSub(), id, String.valueOf(body.get("decision")), auth(req));
            if (updated == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Approval not found");
            return updated;
        } catch (ApprovalService.LinkedTaskUpdateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage());   // not recorded
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());          // expired
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());   // bad decision
        }
    }

    // --- Travel: auto-inferred + persistent; shown in the app's Travel section ---

    @GetMapping("/travel")
    public List<Map<String, Object>> listTravel(HttpServletRequest req) {
        return inPersonaScope(travel.listAndSync(tenant(), ownerSub(), auth(req)));
    }

    /**
     * Approvals and trips are stored per user but inferred from shared tenant items (and,
     * in the demo tenant, seeded identically for everyone), so the stored copies are
     * filtered to the caller's persona the same way the source lists are
     * ({@link PersonaDomainPolicy}). A row is placed by its own {@code domain} if it has
     * one, else by its title and summary; critical rows also reach the CEO.
     */
    private List<Map<String, Object>> inPersonaScope(List<Map<String, Object>> rows) {
        PersonaDomainPolicy.Scope scope = PersonaDomainPolicy.current(grants);
        if (scope.unrestricted() || rows == null) return rows;
        return rows.stream().filter(r -> {
            String domain = DataDomains.of(str(r.get("domain")), str(r.get("title")),
                    str(r.get("summary")), str(r.get("rationale")), str(r.get("reason")));
            boolean critical = PersonaDomainPolicy.isCritical(str(r.get("priority")), str(r.get("criticality")));
            return scope.allows(domain, critical, false);
        }).toList();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    // --- AI-Admin configuration: status-dot thresholds + inference rules (per tenant) ---

    @GetMapping("/config")
    public Map<String, Object> getConfig(HttpServletRequest req) {
        return configService.get(tenant());
    }

    /**
     * Tenant-wide app configuration, edited from the platform-admin-only Mobile Access tab
     * (the platform admin names the tenant via the verified X-Tenant-Id path). Reads stay
     * open: the app itself loads this config.
     */
    @PutMapping("/config")
    @PreAuthorize("hasRole('platform_admin')")
    public Map<String, Object> putConfig(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return configService.put(tenant(), body);
    }

    // --- Identity/RBAC: persona from the Keycloak JWT + the tenant access grants ---

    /**
     * What this user is and may see: the persona derived from their Keycloak roles
     * (same canonical model + priority as the web shell) and the tenant's access
     * grants (proxied from platform-core, the same source the web SPA gates on). The
     * app uses this to auto-select the persona and to show only permitted sections.
     */
    @GetMapping("/profile")
    public Map<String, Object> profile(HttpServletRequest req) {
        List<String> roles = rolesFromJwt();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sub", ownerSub());
        out.put("name", displayNameFromJwt());
        out.put("email", claim("email"));
        out.put("roles", roles);
        out.put("persona", persona(roles));
        // The tenant access-grant map (roles → resource → allow/deny). Null if
        // platform-core is unreachable; the app then falls back to persona defaults.
        out.put("access", ds.get(platformCoreUrl, "/api/access/grants", auth(req), tenant()));
        return out;
    }

    // --- Personal workspace: priority contacts, trips, notification preference (owner-scoped) ---

    @GetMapping("/priority-contacts")
    public List<Map<String, Object>> listPriorityContacts(HttpServletRequest req) {
        return workspace.list("PriorityContact", tenant(), ownerSub());
    }

    @PostMapping("/priority-contacts")
    public Map<String, Object> addPriorityContact(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return workspace.add("PriorityContact", tenant(), ownerSub(), body);
    }

    @GetMapping("/trips")
    public List<Map<String, Object>> listTrips(HttpServletRequest req) {
        return workspace.list("Trip", tenant(), ownerSub());
    }

    @PostMapping("/trips")
    public Map<String, Object> addTrip(HttpServletRequest req, @RequestBody Map<String, Object> body) {
        return workspace.add("Trip", tenant(), ownerSub(), body);
    }

    @GetMapping("/notification-preference")
    public Map<String, Object> getNotificationPreference(HttpServletRequest req) {
        return workspace.single("NotificationPreference", tenant(), ownerSub());
    }

    @PutMapping("/notification-preference")
    public Map<String, Object> putNotificationPreference(HttpServletRequest req,
                                                         @RequestBody Map<String, Object> body) {
        return workspace.upsertSingleton("NotificationPreference", tenant(), ownerSub(), body);
    }

    // --- helpers: forward the caller's own identity downstream ---

    private String auth(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        return h != null ? h : "";
    }

    /**
     * The tenant resolved by eip-common's TenantResolutionFilter: the JWT {@code tenant_id}
     * claim, or the X-Tenant-Id header only for a platform admin / internal caller. The raw
     * header is never trusted on its own — otherwise the BFF's own rows (approvals, travel,
     * config) would follow the header while every downstream service follows the JWT, and a
     * user could read another tenant's rows by changing the header.
     */
    private String tenant() {
        TenantContext ctx = TenantContextHolder.currentOrNull();
        if (ctx == null || ctx.tenantId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tenant in scope for this caller");
        }
        return ctx.tenantId().toString();
    }

    /** The caller's opaque Keycloak subject — the owner-scoping key for personal app state. */
    private String ownerSub() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof Jwt jwt && jwt.getSubject() != null) {
            return jwt.getSubject();
        }
        return "";
    }

    /** A single string claim off the caller's JWT, or "" when absent. */
    private String claim(String name) {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof Jwt jwt) {
            Object v = jwt.getClaims().get(name);
            if (v != null) return v.toString();
        }
        return "";
    }

    /**
     * The caller's display name for the app greeting, derived from standard OIDC claims:
     * {@code name}, else {@code given_name [family_name]}, else {@code preferred_username},
     * else the local-part of {@code email}. Never the raw email address.
     */
    private String displayNameFromJwt() {
        String name = claim("name");
        if (!name.isBlank()) return name;
        String given = claim("given_name");
        if (!given.isBlank()) {
            String family = claim("family_name");
            return family.isBlank() ? given : given + " " + family;
        }
        String preferred = claim("preferred_username");
        if (!preferred.isBlank() && !preferred.contains("@")) return preferred;
        String email = claim("email");
        int at = email.indexOf('@');
        return at > 0 ? email.substring(0, at) : (preferred.isBlank() ? "" : preferred);
    }

    /** All roles on the caller's JWT: realm_access.roles + every resource_access.*.roles. */
    @SuppressWarnings("unchecked")
    private List<String> rolesFromJwt() {
        List<String> roles = new ArrayList<>();
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof Jwt jwt)) return roles;
        Object realm = jwt.getClaims().get("realm_access");
        if (realm instanceof Map<?, ?> rm && rm.get("roles") instanceof List<?> rl) {
            for (Object r : rl) if (r != null) roles.add(r.toString());
        }
        Object resource = jwt.getClaims().get("resource_access");
        if (resource instanceof Map<?, ?> ra) {
            for (Object client : ra.values()) {
                if (client instanceof Map<?, ?> cm && cm.get("roles") instanceof List<?> rl) {
                    for (Object r : rl) if (r != null) roles.add(r.toString());
                }
            }
        }
        return roles;
    }

    /** Highest-privilege role the user holds, lowercased; "guest" when none match. */
    private static String persona(List<String> roles) {
        java.util.Set<String> held = new java.util.HashSet<>();
        for (String r : roles) if (r != null) held.add(r.toLowerCase());
        for (String p : ROLE_PRIORITY) if (held.contains(p)) return p;
        return "guest";
    }

    /** Map a mobile capture type to the ingestion inputType classification. */
    private static String mapInputType(String captureType) {
        if (captureType == null) return "DOCUMENT";
        switch (captureType.toUpperCase()) {
            case "AUDIO": case "VOICE":       return "AUDIO";
            case "HANDWRITING":                return "HANDWRITING";
            case "IMAGE": case "PHOTO":        return "IMAGE";
            case "TEXT": case "NOTE":          return "NOTE";
            default:                           return "DOCUMENT";
        }
    }
}
