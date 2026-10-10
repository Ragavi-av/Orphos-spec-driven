package com.orphos.mobile.data.model;

import java.util.List;

/** Request/response for POST /api/nl-query/ask (nl-query-service). */
public class NlQuery {

    public static class Request {
        public String question;   // required, <= 500 chars
        public String provider;   // optional
        public String model;      // optional

        public Request(String question) {
            this.question = question;
        }
    }

    public static class Response {
        public String answer;
        public List<String> followups;
        public String provider;
        public String model;
        /** Family of the model that actually answered; preferred over {@code provider} for display. */
        public String modelFamily;
        public String question;
        public String timestamp;
        public Trust trust;       // optional
        /** Set from the {@code { "error": … }} body when the service rejects the question. */
        public String error;

        public static class Trust {
            public String grounding;
            public Double confidence;
            public String verification;
            public List<String> caveats;
            public List<Source> sources;

            public static class Source {
                public String type;
                public String id;
                public Double relevance;
            }
        }
    }
}
