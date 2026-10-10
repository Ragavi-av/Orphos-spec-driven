package io.orphos.eip.common.money;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Restates a reported amount in the currency a reader asked for.
 *
 * <p>The rule this service enforces, and the reason it exists rather than a
 * multiply at the call site: <b>which</b> rate applies is decided by what the
 * figure is ({@link FigureKind}) and when it is from (its period), not by when
 * the page was opened. Every caller that knows those two things gets the same
 * answer, and a caller that does not know them cannot accidentally convert.</p>
 *
 * <h2>Fails open, always</h2>
 * No rate, unknown currency, a period with no dates, a source that throws — all
 * of them return the reported amount unconverted rather than a blank, a zero or
 * an exception. A tile showing ₹58,550,000 when the reader wanted yen is mildly
 * disappointing; a tile showing nothing, or showing a number derived from a rate
 * nobody can name, is worse than the problem being solved.
 */
public class MoneyConverter {

    private static final Logger log = LogManager.getLogger(MoneyConverter.class);

    /**
     * Minor-unit precision per currency, for the converted result only.
     *
     * <p>¥1,234.56 is not a thing — JPY has no minor unit, and carrying two
     * decimals through a conversion produces figures that look precise and read
     * as wrong to anyone who works in the currency. The same set exists in the
     * SPA's money.js for formatting; here it decides where the arithmetic
     * rounds, which is a different job and has to be done before the number
     * leaves the service.</p>
     */
    private static final Set<String> ZERO_DECIMAL =
            Set.of("JPY", "KRW", "VND", "CLP", "ISK", "HUF");

    /** Enough working precision that rounding happens once, at the end. */
    private static final MathContext WORKING = new MathContext(20, RoundingMode.HALF_UP);

    private final FxRateSource rates;

    public MoneyConverter(FxRateSource rates) {
        this.rates = rates;
    }

    /**
     * Convert one amount, or return it as reported.
     *
     * @param amount        reported amount; null yields a null-valued as-reported result
     * @param fromCurrency  currency the data declares
     * @param toCurrency    currency the reader wants; null/blank means "no preference"
     * @param kind          what sort of figure this is — decides average vs closing
     * @param periodEnd     the date to price it at; null falls back to today
     */
    public ConvertedAmount convert(BigDecimal amount, String fromCurrency, String toCurrency,
                                   FigureKind kind, LocalDate periodEnd) {
        String from = normalise(fromCurrency);
        String to = normalise(toCurrency);

        // Nothing to do, and saying so explicitly keeps the "converted" marker
        // off a tile whose currency the reader already reads in.
        if (amount == null || from == null || to == null || from.equals(to)) {
            return ConvertedAmount.asReported(amount, from);
        }

        FigureKind figureKind = kind == null ? FigureKind.BALANCE : kind;
        // A missing period means the figure carries no date of its own — a live
        // count or an unscoped total. Today is the only honest as-of for those,
        // and it is shown to the reader like any other rate date.
        LocalDate asOf = periodEnd == null ? LocalDate.now() : periodEnd;

        Optional<FxRate> found;
        try {
            found = rates.rateAt(from, to, figureKind.rateType(), asOf);
        } catch (RuntimeException e) {
            // The rate table being unreachable must not take the board down with
            // it. Warn once per call and show the reported figure.
            log.warn("FX lookup failed {}→{} {} as of {}; showing reported amount",
                    from, to, figureKind.rateType(), asOf, e);
            return ConvertedAmount.asReported(amount, from);
        }

        if (found.isEmpty()) {
            log.debug("No {} rate {}→{} on or before {}; showing reported amount",
                    figureKind.rateType(), from, to, asOf);
            return ConvertedAmount.asReported(amount, from);
        }

        FxRate rate = found.get();
        BigDecimal converted = amount.multiply(rate.rate(), WORKING)
                .setScale(ZERO_DECIMAL.contains(to) ? 0 : 2, RoundingMode.HALF_UP);
        return ConvertedAmount.using(converted, amount, rate);
    }

    /**
     * Convert a value and its target together, at the SAME rate.
     *
     * <p>The board compares every figure against its target and prints the
     * attainment percentage. Converting one and not the other silently changes
     * that percentage; converting them at two different rates does the same
     * thing more subtly. Callers that have both must come through here — that
     * is the only reason this method exists rather than two {@code convert}
     * calls at the call site.</p>
     *
     * @return the converted value and target, in that order; the target element
     *         is as-reported when the input target was null
     */
    public ConvertedAmount[] convertWithTarget(BigDecimal value, BigDecimal target,
                                               String fromCurrency, String toCurrency,
                                               FigureKind kind, LocalDate periodEnd) {
        ConvertedAmount convertedValue = convert(value, fromCurrency, toCurrency, kind, periodEnd);
        if (target == null) {
            return new ConvertedAmount[]{convertedValue, ConvertedAmount.asReported(null, normalise(fromCurrency))};
        }
        // Reuse the rate the value resolved to rather than looking it up again:
        // a second lookup could straddle a rate change and produce a target
        // priced differently from the value it is compared against.
        if (!convertedValue.converted()) {
            return new ConvertedAmount[]{
                    convertedValue,
                    ConvertedAmount.asReported(target, normalise(fromCurrency))};
        }
        String to = convertedValue.currency();
        BigDecimal convertedTarget = target.multiply(convertedValue.rate(), WORKING)
                .setScale(ZERO_DECIMAL.contains(to) ? 0 : 2, RoundingMode.HALF_UP);
        return new ConvertedAmount[]{
                convertedValue,
                new ConvertedAmount(convertedTarget, to, true, target, convertedValue.sourceCurrency(),
                        convertedValue.rate(), convertedValue.rateAsOf(),
                        convertedValue.rateType(), convertedValue.rateSource())};
    }

    private static String normalise(String code) {
        if (code == null || code.isBlank()) return null;
        String trimmed = code.trim().toUpperCase(Locale.ROOT);
        return trimmed.length() == 3 ? trimmed : null;
    }
}
