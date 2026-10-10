package com.orphos.mobile.data.model;

import java.util.List;
import java.util.Map;

/** GET /api/priority/queue (priority-engine-service). */
public class PriorityQueue {
    public List<PriorityItem> queue;
    public Integer count;
    public String source;       // "EPQ-v1"

    public static class PriorityItem {
        public String id;
        public String type;      // INCIDENT | ALERT | BUDGET_ITEM | DECISION | KEY_RESULT | PROJECT
        public String title;
        public String severity;
        public String status;
        public String domain;
        public Double epqScore;  // 0–100
        public Integer rank;     // 1-based
        public Map<String, Double> scoreBreakdown;
        public String rationale;
    }
}
