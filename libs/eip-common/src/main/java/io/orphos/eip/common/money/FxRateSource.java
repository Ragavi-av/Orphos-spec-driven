package io.orphos.eip.common.money;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Where rates come from.
 *
 * <p>An interface rather than a class so the rate table can be swapped for a
 * provider feed later without touching {@link MoneyConverter} or any caller.
 * The platform ships the tenant-maintained table
 * ({@code GraphFxRateSource}); a live provider would implement this same
 * method and cache behind it.</p>
 */
@FunctionalInterface
public interface FxRateSource {

    /**
     * The rate to use for {@code asOf}: the most recent rate of this type
     * effective on or before that date.
     *
     * <p>"On or before" rather than "nearest" deliberately. A figure for August
     * must not be converted at a September rate that did not exist when the
     * period closed — re-running the board a month later would then change a
     * closed period's number. Looking only backwards makes a converted
     * historical figure stable forever.</p>
     *
     * <p>Returns empty when nothing covers the date. Callers must fall back to
     * the reported amount; an empty result is a normal state (a tenant that has
     * not entered rates yet), never an error.</p>
     */
    Optional<FxRate> rateAt(String fromCurrency, String toCurrency,
                            FxRate.RateType rateType, LocalDate asOf);
}
