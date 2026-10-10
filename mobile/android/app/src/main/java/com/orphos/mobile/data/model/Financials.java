package com.orphos.mobile.data.model;

import java.util.List;

/**
 * Financials screen (read-only). Values come from briefing Zenitus finance metrics /
 * dashboard persona-metrics in production. Amounts are minor-unit-agnostic doubles here.
 */
public class Financials {

    public String period;
    public String currency;          // ISO 4217, e.g. "USD" | "JPY"
    public List<Metric> metrics;
    public Integer creditScore;      // e.g. 780

    public static class Metric {
        public String key;           // cashFlow | income | expenses | pnl | forecast | capital
        public String label;
        public double amount;
        public String delta;         // e.g. "+8% QoQ"
        public boolean positive;     // drives colour (locale-aware; see note in FinancialsFragment)
        public Metric() { }
        public Metric(String key, String label, double amount, String delta, boolean positive) {
            this.key = key; this.label = label; this.amount = amount; this.delta = delta; this.positive = positive;
        }
    }
}
