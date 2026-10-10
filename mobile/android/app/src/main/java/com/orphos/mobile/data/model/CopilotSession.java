package com.orphos.mobile.data.model;

import java.util.ArrayList;
import java.util.List;

/**
 * One AI Co-pilot conversation, as stored by nl-query-service at
 * {@code /api/nl-query/me/copilot/sessions}. This is the SAME store the web dashboard's
 * History ▸ AI tab reads and writes (ai-copilot/sessionStore.js), so a question asked on
 * the phone shows up on the dashboard and vice versa. Field names and shapes must stay
 * identical to the web's session object — the server keeps {@code messages} verbatim.
 */
public class CopilotSession {

    public String id;
    public String label;
    public String startedAt;   // ISO-8601
    public String endedAt;     // ISO-8601, null while the session is still open
    public List<Message> messages = new ArrayList<>();

    /** A completed question/answer pair (web: copilotRuntime.js message object). */
    public static class Message {
        public String id;
        public String question;
        public String answer;
        public List<String> followups = new ArrayList<>();
        public NlQuery.Response.Trust trust;
        public String provider;
        public String timestamp;   // ISO-8601
        public boolean loading;    // always false once stored
        public String source;      // "Context: …" badge; null for direct asks
    }

    /** GET response envelope: {@code { "sessions": [...] }}, newest first. */
    public static class ListResponse {
        public List<CopilotSession> sessions;
    }

    /** PUT body — the session minus its id, which travels in the path. */
    public static class Payload {
        public String label;
        public String startedAt;
        public String endedAt;
        public List<Message> messages;

        public Payload(CopilotSession s) {
            this.label = s.label;
            this.startedAt = s.startedAt;
            this.endedAt = s.endedAt;
            this.messages = s.messages;
        }
    }

    public int questionCount() {
        return messages == null ? 0 : messages.size();
    }
}
