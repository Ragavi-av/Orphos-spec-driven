package io.orphos.eip.mailinsight.pipeline;

/** Outcome counts for a single scan invocation. */
public record ScanResult(int mailboxesScanned, int itemsCreated, int itemsUpdated, int failures) {
    public static ScanResult empty() {
        return new ScanResult(0, 0, 0, 0);
    }

    public ScanResult plus(ScanResult o) {
        return new ScanResult(
                mailboxesScanned + o.mailboxesScanned,
                itemsCreated + o.itemsCreated,
                itemsUpdated + o.itemsUpdated,
                failures + o.failures);
    }
}
