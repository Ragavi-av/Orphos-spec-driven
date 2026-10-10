package com.orphos.mobile.data.model;

/**
 * A trip extracted from an email/mail-insight item. Populated by {@code TravelExtractor}
 * from keyword matches on the subject/body: origin, destination, mode, date and purpose.
 */
public class TravelItem {
    public String from;      // origin (e.g. "Bangalore")
    public String to;        // destination (e.g. "Delhi")
    public String mode;      // Flight | Train | Cab | Car | Bus
    public String date;      // human date label
    public String time;      // human time label (e.g. "6:30 AM")
    public String purpose;   // why the trip (subject / "for ...")
    public String confirmationStatus; // CONFIRMED | TENTATIVE
    public String sourceId;  // originating mail-insight item id
    /** Departure as epoch ms, or null when the row has no readable date. Set by the repository. */
    public Long startAtMillis;

    public TravelItem() { }

    public TravelItem(String from, String to, String mode, String date, String purpose) {
        this.from = from; this.to = to; this.mode = mode; this.date = date; this.purpose = purpose;
    }

    /** e.g. "Bangalore → Delhi" (falls back to whichever endpoint is known). */
    public String route() {
        if (from != null && to != null) return from + " → " + to;
        if (to != null) return "To " + to;
        if (from != null) return "From " + from;
        return mode != null ? mode : "Trip";
    }
}
