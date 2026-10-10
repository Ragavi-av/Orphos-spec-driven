package io.orphos.eip.common.money;

import java.util.Locale;

/**
 * What kind of quantity an amount is — the one thing a formatter cannot infer
 * and the thing that decides which rate converts it.
 *
 * <p>"Net cash flow, Aug 2026" accumulated across August, so it converts at
 * August's average rate. "Risk exposure, FY2027 Q2" is what stands exposed at
 * the end of the quarter, so it converts at the closing rate on that date.
 * Using one where the other belongs is not a rounding difference: in a month
 * where a rate moves 5%, a flow converted at the closing rate misstates the
 * figure by roughly half that move, in a direction that depends on which way
 * the currency went.</p>
 *
 * <p>This is declared per metric in the governance profile rather than guessed
 * from the metric's name or its period shape. Both guesses were tried on paper
 * and both break on the real data: "budget variance" is a flow whose name
 * suggests a position, and risk exposure carries a quarter-long period while
 * being a point-in-time balance.</p>
 */
public enum FigureKind {

    /** Accumulated over a period — revenue, payroll, cash in/out, variance. */
    FLOW(FxRate.RateType.AVERAGE),

    /** Stands at a point in time — exposure, pipeline, headcount value, ARR. */
    BALANCE(FxRate.RateType.CLOSING);

    private final FxRate.RateType rateType;

    FigureKind(FxRate.RateType rateType) {
        this.rateType = rateType;
    }

    /** The rate this kind of figure must be converted at. */
    public FxRate.RateType rateType() {
        return rateType;
    }

    /**
     * Parse a declared kind, defaulting to {@link #BALANCE} for anything absent
     * or unrecognised.
     *
     * <p>BALANCE is the safer default because it converts at a single dated
     * rate the reader can be shown and can check. An unrecognised value
     * defaulting to FLOW would instead pick a period-average rate — a number
     * that is harder to trace and that quietly implies the metric was
     * classified when it was not.</p>
     */
    public static FigureKind parse(String declared) {
        if (declared == null || declared.isBlank()) return BALANCE;
        try {
            return valueOf(declared.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BALANCE;
        }
    }
}
