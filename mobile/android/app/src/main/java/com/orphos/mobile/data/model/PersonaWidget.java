package com.orphos.mobile.data.model;

import java.util.Locale;

/**
 * One tile of the caller's web widget board, as served by the BFF's /api/mobile/metrics:
 * the persona-resolved widget list with each widget's value. Formatting follows the web
 * tile — CURRENCY in its own currency (compact), PERCENT to one decimal, counts as-is —
 * and a widget with no figure keeps {@code value == null} rather than showing a made-up 0.
 */
public class PersonaWidget {

    /** Status colour: the source's RAG, never decorative. NONE = neutral. */
    public enum Status { GOOD, WARN, BAD, NONE }

    public String id, label, area;
    /** Display value, or null when there is no figure (see {@link #caption}). */
    public String value;
    /** Raw numeric value, NaN when not numeric. */
    public double raw = Double.NaN;
    /** One-line comparison / context: "92% of target ₹20 Cr · FY2027 Q2", "Awaiting approval". */
    public String caption;
    public String target;
    /** value / target × 100, or -1 when there is no usable target. */
    public int attainmentPct = -1;
    /** Share of the observation's period elapsed today, or -1 when it has no bounds. */
    public int periodElapsedPct = -1;
    public String period;
    public String unit;
    public Status status = Status.NONE;
    /** False when the caller's persona may not see the figure. */
    public boolean permitted = true;

    // --- Definition: what the widget measures, shown when it is tapped ---
    /** Catalog description, e.g. "Total residual financial exposure on the risk register". */
    public String description;
    /** The system the figure is read from ("Decision Log", "Enterprise Ontology", …). */
    public String source;
    /** Ontology metric key, e.g. "risk.exposure.residual"; null when not declared. */
    public String metricKey;
    /** How observations roll up: AVG, SUM, LAST, COUNT; null when not declared. */
    public String aggregation;
    /** Direction of good; null when not declared. */
    public Boolean higherIsBetter;

    /** One /api/mobile/metrics response: the widget tiles plus the records behind them. */
    public static class Board {
        public java.util.List<PersonaWidget> widgets = new java.util.ArrayList<>();
        public MetricDetails details = new MetricDetails();
    }

    public boolean hasValue() {
        return value != null;
    }

    /** Compact money: ₹4.14 Cr / ₹12.5 L for INR, $1.20M / $850K otherwise. */
    public static String money(double v, String currency) {
        Locale loc = Locale.getDefault();
        double abs = Math.abs(v);
        String sym = symbol(currency);
        String n;
        if ("INR".equalsIgnoreCase(currency)) {
            n = abs >= 1e7 ? String.format(loc, "%.2f Cr", abs / 1e7)
                    : abs >= 1e5 ? String.format(loc, "%.1f L", abs / 1e5)
                    : String.format(loc, "%,.0f", abs);
        } else {
            n = abs >= 1e9 ? String.format(loc, "%.2fB", abs / 1e9)
                    : abs >= 1e6 ? String.format(loc, "%.2fM", abs / 1e6)
                    : abs >= 1e3 ? String.format(loc, "%.0fK", abs / 1e3)
                    : String.format(loc, "%.0f", abs);
        }
        return (v < 0 ? "−" : "") + sym + n;
    }

    private static String symbol(String currency) {
        if (currency == null || currency.isEmpty()) return "";
        try {
            return java.util.Currency.getInstance(currency).getSymbol(Locale.getDefault());
        } catch (IllegalArgumentException e) {
            return currency + " ";
        }
    }
}
