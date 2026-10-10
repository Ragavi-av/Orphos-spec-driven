package io.orphos.eip.common.money;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An amount ready for display, carrying the reported figure alongside it.
 *
 * <p>Both figures travel together, always. The SPA renders {@link #value} in
 * {@link #currency} and shows the rest on hover, so a reader looking at ¥ can
 * see the ₹ it came from, the rate, and the date that rate is from. Returning
 * only the converted number would make a derived figure indistinguishable from
 * a reported one — the specific failure this whole package exists to avoid.</p>
 *
 * <p>{@code converted == false} is the normal, safe state, not an error: the
 * amount is already in the requested currency, no currency was requested, or no
 * rate covers the date. In every one of those the reported figure is what shows,
 * and it shows without a conversion marker, because nothing was converted.</p>
 *
 * @param value          amount to display
 * @param currency       currency {@link #value} is in
 * @param converted      whether a rate was applied
 * @param sourceValue    the reported amount, as stored
 * @param sourceCurrency the currency the data declares
 * @param rate           rate applied, null when {@code converted} is false
 * @param rateAsOf       {@code effectiveFrom} of the applied rate, null when not converted
 * @param rateType       which rate was applied, null when not converted
 * @param rateSource     provenance of the applied rate, null when not converted
 */
public record ConvertedAmount(
        BigDecimal value,
        String currency,
        boolean converted,
        BigDecimal sourceValue,
        String sourceCurrency,
        BigDecimal rate,
        LocalDate rateAsOf,
        FxRate.RateType rateType,
        String rateSource) {

    /** The reported figure, untouched — same currency, no rate, no marker. */
    public static ConvertedAmount asReported(BigDecimal value, String currency) {
        return new ConvertedAmount(value, currency, false, value, currency,
                null, null, null, null);
    }

    /** The figure restated through {@code rate}. */
    public static ConvertedAmount using(BigDecimal converted, BigDecimal sourceValue, FxRate rate) {
        return new ConvertedAmount(converted, rate.toCurrency(), true,
                sourceValue, rate.fromCurrency(),
                rate.rate(), rate.effectiveFrom(), rate.rateType(), rate.source());
    }
}
