package com.orphos.mobile.data.model;

import java.util.List;

/** GET /api/briefing/daily-digest (DailyDigestService.digest). Powers the Home AI summary. */
public class DailyDigest {
    public String generatedAt;
    public String period;       // e.g. "FY2027 Q2 · WEEK 34"
    public Boolean allClear;
    public Integer totalCount;
    public String source;       // ai | fallback | empty
    public String headline;
    public Integer otherCount;
    public List<DigestItem> items;
    public List<DigestItem> important;
    public List<DigestItem> updates;

    public static class DigestItem {
        public Integer ref;
        public String kind;      // alert | sla | incident | budget | decision (or risk/milestone/…)
        public String id;
        public String title;
        public String detail;
        public String severity;  // critical | warning | info (lowercase here)
        public String sourceTitle;
    }
}
