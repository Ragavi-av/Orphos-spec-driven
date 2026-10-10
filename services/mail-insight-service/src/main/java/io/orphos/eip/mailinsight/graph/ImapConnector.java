package io.orphos.eip.mailinsight.graph;

import jakarta.mail.Address;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.ReceivedDateTerm;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

/**
 * Reads mail over IMAP for providers without an app-only API in this build —
 * Google Workspace (imap.gmail.com), Zoho (imap.zoho.com), and any other IMAP
 * server. Each mailbox authenticates with its own username (the address) and an
 * app password resolved from Infisical. Mail only — IMAP carries no calendar.
 *
 * Returns normalized {@link GraphItem}s; never persists or logs raw bodies.
 */
@Component
public class ImapConnector {

    private static final Logger log = LogManager.getLogger(ImapConnector.class);

    private final int maxItems;

    public ImapConnector(@Value("${graph.max-items:100}") int maxItems) {
        this.maxItems = maxItems;
    }

    public List<GraphItem> fetch(String host, int port, boolean ssl,
                                 String username, String password, int windowDays) {
        if (host == null || host.isBlank()) {
            throw new GraphException("IMAP host is not configured");
        }
        if (password == null || password.isBlank()) {
            throw new GraphException("No app password stored for mailbox " + username);
        }

        Properties props = new Properties();
        String protocol = ssl ? "imaps" : "imap";
        props.put("mail.store.protocol", protocol);
        props.put("mail." + protocol + ".host", host);
        props.put("mail." + protocol + ".port", String.valueOf(port));
        props.put("mail." + protocol + ".connectiontimeout", "15000");
        props.put("mail." + protocol + ".timeout", "30000");
        if (ssl) {
            props.put("mail.imaps.ssl.enable", "true");
        }

        Session session = Session.getInstance(props);
        Store store = null;
        Folder inbox = null;
        List<GraphItem> out = new ArrayList<>();
        try {
            store = session.getStore(protocol);
            store.connect(host, port, username, password);
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);

            Date since = Date.from(Instant.now().minus(windowDays, ChronoUnit.DAYS));
            Message[] messages = inbox.search(new ReceivedDateTerm(ComparisonTerm.GE, since));

            // Newest first, capped at maxItems.
            int start = Math.max(0, messages.length - maxItems);
            for (int i = messages.length - 1; i >= start; i--) {
                try {
                    out.add(toItem((MimeMessage) messages[i], username));
                } catch (Exception e) {
                    log.debug("Skipping unreadable IMAP message: {}", e.getMessage());
                }
            }
        } catch (GraphException e) {
            throw e;
        } catch (Exception e) {
            throw new GraphException("IMAP fetch failed for " + username + "@" + host + ": " + e.getMessage(), e);
        } finally {
            closeQuietly(inbox);
            closeQuietly(store);
        }
        return out;
    }

    private GraphItem toItem(MimeMessage msg, String mailbox) throws Exception {
        String subject = msg.getSubject() == null ? "" : msg.getSubject();
        String sourceRef = firstHeader(msg, "Message-ID");
        if (sourceRef == null || sourceRef.isBlank()) {
            sourceRef = "imap-" + Integer.toHexString((subject + "|" + msg.getSentDate()).hashCode());
        }
        Instant received = msg.getReceivedDate() != null ? msg.getReceivedDate().toInstant()
                : msg.getSentDate() != null ? msg.getSentDate().toInstant() : null;

        String senderDisplay = "";
        Address[] from = msg.getFrom();
        if (from != null && from.length > 0 && from[0] instanceof InternetAddress ia) {
            senderDisplay = ia.getPersonal() != null && !ia.getPersonal().isBlank()
                    ? ia.getPersonal() : ia.getAddress();
        }

        boolean importanceHigh = headerHasHighImportance(msg);
        boolean direct = isDirectRecipient(msg, mailbox);
        String preview = extractPreview(msg);

        return new GraphItem(sourceRef, "EMAIL", subject, preview, senderDisplay,
                received, importanceHigh, direct, null);
    }

    private static boolean headerHasHighImportance(MimeMessage msg) {
        try {
            String imp = firstHeader(msg, "Importance");
            if (imp != null && imp.equalsIgnoreCase("high")) return true;
            String prio = firstHeader(msg, "X-Priority");
            if (prio != null) {
                String d = prio.trim();
                return d.startsWith("1") || d.startsWith("2");
            }
        } catch (Exception ignored) { /* default false */ }
        return false;
    }

    private static boolean isDirectRecipient(MimeMessage msg, String mailbox) {
        try {
            Address[] to = msg.getRecipients(Message.RecipientType.TO);
            if (to == null) return true;
            for (Address a : to) {
                if (a instanceof InternetAddress ia && mailbox.equalsIgnoreCase(ia.getAddress())) return true;
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** Short text preview — first text/plain part, capped. Never the full body. */
    private static String extractPreview(Part part) {
        try {
            if (part.isMimeType("text/plain")) {
                return trim(readText(part));
            }
            if (part.isMimeType("multipart/*")) {
                Multipart mp = (Multipart) part.getContent();
                for (int i = 0; i < mp.getCount(); i++) {
                    String text = extractPreview(mp.getBodyPart(i));
                    if (text != null && !text.isBlank()) return text;
                }
            }
            if (part.isMimeType("text/html")) {
                String html = readText(part);
                if (!html.isBlank()) {
                    // Strip tags for a rough preview.
                    return trim(html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " "));
                }
            }
        } catch (Exception ignored) { /* fall through */ }
        return "";
    }

    /**
     * Decodes a text part using its declared charset, reading the raw (transfer-
     * decoded) bytes explicitly rather than relying on {@code getContent()}, whose
     * fallback when no charset is declared mangles Japanese (ISO-2022-JP / Shift_JIS
     * / EUC-JP). When the charset is missing or unsupported we try a Japanese-aware
     * decode ladder before giving up to lenient UTF-8.
     */
    private static String readText(Part part) throws Exception {
        byte[] bytes;
        try (InputStream in = part.getInputStream()) {
            bytes = in.readAllBytes();
        }
        String declared = charsetOf(part);
        if (declared != null && Charset.isSupported(declared)) {
            return new String(bytes, Charset.forName(declared));
        }
        return decodeUnknownCharset(bytes);
    }

    /** Reads the charset parameter from a part's Content-Type, or null if absent/unparseable. */
    private static String charsetOf(Part part) {
        try {
            String ct = part.getContentType();
            if (ct == null) return null;
            String cs = new ContentType(ct).getParameter("charset");
            return (cs == null || cs.isBlank()) ? null : cs;
        } catch (Exception e) {
            return null;
        }
    }

    /** No declared charset: try strict UTF-8, then common Japanese encodings, then lenient UTF-8. */
    private static String decodeUnknownCharset(byte[] bytes) {
        for (String cs : new String[]{"UTF-8", "Shift_JIS", "ISO-2022-JP", "EUC-JP"}) {
            try {
                CharsetDecoder decoder = Charset.forName(cs).newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                return decoder.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (Exception ignored) { /* try the next candidate */ }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String firstHeader(MimeMessage msg, String name) throws Exception {
        String[] h = msg.getHeader(name);
        return (h != null && h.length > 0) ? h[0] : null;
    }

    private static String trim(String s) {
        String t = s.trim();
        return t.length() > 300 ? t.substring(0, 300) + "…" : t;
    }

    private static void closeQuietly(Folder f) {
        try { if (f != null && f.isOpen()) f.close(false); } catch (Exception ignored) { }
    }

    private static void closeQuietly(Store s) {
        try { if (s != null && s.isConnected()) s.close(); } catch (Exception ignored) { }
    }
}
