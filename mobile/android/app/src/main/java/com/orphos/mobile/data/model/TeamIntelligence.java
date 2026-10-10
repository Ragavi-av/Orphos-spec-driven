package com.orphos.mobile.data.model;

import java.util.List;

/** Business / Team Intelligence screen data (briefing Zenitus HR + okg-reasoning insights). */
public class TeamIntelligence {

    public String period;                 // e.g. "This week"
    public List<Productivity> productivity;
    public List<Availability> availability;
    public List<Burnout> burnout;

    public static class Productivity {
        public String function;           // Marketing | Engineering | Sales | Support
        public int percent;               // 0–100
        public Productivity() { }
        public Productivity(String function, int percent) { this.function = function; this.percent = percent; }
    }

    public static class Availability {
        public String status;             // Onsite | PTO | Illness | Sabbatical | Casual leave | Others
        public int count;
        public Availability() { }
        public Availability(String status, int count) { this.status = status; this.count = count; }
    }

    /** From okg-reasoning insights: a wellbeing/burnout signal + suggested action. */
    public static class Burnout {
        public String subject;            // role, not a name (PII policy)
        public String signal;             // e.g. "Working 3 months continuously"
        public String assessment;         // e.g. "Possible burnout"
        public String suggestedAction;    // e.g. "Appreciate and encourage time off"
        public Burnout() { }
        public Burnout(String subject, String signal, String assessment, String suggestedAction) {
            this.subject = subject; this.signal = signal; this.assessment = assessment; this.suggestedAction = suggestedAction;
        }
    }
}
