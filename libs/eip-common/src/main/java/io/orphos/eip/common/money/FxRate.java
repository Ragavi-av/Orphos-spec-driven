package io.orphos.eip.common.money;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One exchange rate, as of one date, for one purpose.
 *
 * <p>Every field here exists because a converted figure on an executive board has
 * to be defensible: shown a yen number, a reader must be able to ask "from what,
 * at what rate, as of when, says who" and get an answer. A bare
 * {@code double rate} cannot answer any of those, which is why this is a record
 * and not a map lookup.</p>
 *
 * <h2>Why {@code rateType} is part of the identity</h2>
 * A period's average rate and its closing rate are different numbers, and which
 * one is correct depends on what is being converted — see {@link FigureKind}.
 * Holding both under one key and letting the caller pick would make the choice
 * invisible at the call site; holding them as distinct rates makes a missing
 * average rate an explicit miss rather than a silent fallback to a closing rate
 * that overstates a volatile month.
 *
 * <h2>Why {@code effectiveFrom} and not a validity range</h2>
 * Rates are looked up as "the most recent rate effective on or before this
 * date", so an open-ended start is enough and a range would let an admin leave
 * a gap that silently disables conversion mid-history. Superseding a rate means
 * adding a later row, which also keeps the history intact for anyone auditing a
 * figure that was on screen last quarter.
 *
 * @param fromCurrency  ISO-4217 code the amount is denominated in
 * @param toCurrency    ISO-4217 code to express it in
 * @param rate          units of {@code toCurrency} per ONE unit of {@code fromCurrency}
 * @param rateType      which rate this is; must match the figure being converted
 * @param effectiveFrom first date this rate applies to
 * @param source        provenance, shown to the reader ("Tenant rate table", a
 *                      provider name, an internal treasury reference)
 */
public record FxRate(
        String fromCurrency,
        String toCurrency,
        BigDecimal rate,
        RateType rateType,
        LocalDate effectiveFrom,
        String source) {

    /**
     * Which rate a figure needs.
     *
     * <p>Named for the accounting convention rather than for the storage row so
     * the call site reads as the decision it is making. IAS 21 is the reference:
     * income and expense items translate at the rate on the transaction date
     * (in practice a period average), assets and liabilities at the closing
     * rate on the balance-sheet date.</p>
     */
    public enum RateType {
        /** Mean rate across a period — for amounts that accumulated over it. */
        AVERAGE,
        /** Rate on one date — for amounts that stand at a point in time. */
        CLOSING
    }

    public FxRate {
        fromCurrency = normaliseCode(fromCurrency);
        toCurrency = normaliseCode(toCurrency);
        Objects.requireNonNull(rate, "rate");
        Objects.requireNonNull(rateType, "rateType");
        Objects.requireNonNull(effectiveFrom, "effectiveFrom");
        // A zero or negative rate is not a rate. Letting one through would turn
        // every converted figure on the board into 0 or a sign-flipped number,
        // and both look like data rather than like the misconfiguration they are.
        if (rate.signum() <= 0) {
            throw new IllegalArgumentException("FX rate must be positive: " + rate);
        }
        source = (source == null || source.isBlank()) ? "unspecified" : source.trim();
    }

    private static String normaliseCode(String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("currency code required");
        }
        String trimmed = code.trim().toUpperCase(java.util.Locale.ROOT);
        if (trimmed.length() != 3) {
            throw new IllegalArgumentException("currency code must be 3 letters: " + code);
        }
        return trimmed;
    }
}
