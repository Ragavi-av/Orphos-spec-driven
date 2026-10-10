package io.orphos.eip.common.money;

import io.orphos.eip.common.tenant.TenantContextHolder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The tenant's own rate table, stored as {@code FxRate} vertices in the graph
 * beside the observations it prices.
 *
 * <h2>Why a table and not a provider feed</h2>
 * A tenant's finance team already knows which rate it books at — often a board
 * rate fixed for a quarter, not the spot rate on the day. Reading a public feed
 * would produce figures that disagree with the tenant's own reporting and change
 * under the reader between two page loads. A table an admin maintains is
 * auditable, works with no outbound network, and can hold real historical rates
 * so a closed period keeps the number it always had. {@link FxRateSource} is an
 * interface precisely so a feed can be added later for tenants who want one.
 *
 * <h2>Why the query comes in as a lambda</h2>
 * Both widgets-service (which depends on eip-core-arcadedb) and briefing-service
 * (which does not) need rates. Taking a {@link RowQuery} instead of a concrete
 * database client keeps this class in eip-common where both can reach it, and
 * costs each service one bean definition.
 */
public class GraphFxRateSource implements FxRateSource {

    private static final Logger log = LogManager.getLogger(GraphFxRateSource.class);

    /** Whatever the host service already uses to run a parameterised query. */
    @FunctionalInterface
    public interface RowQuery {
        List<Map<String, Object>> run(String sql, Map<String, Object> params);
    }

    /**
     * Ordered by {@code effectiveFrom} descending, take one — "the rate in force
     * on that date". Dates are stored as ISO strings so this comparison is a
     * plain lexicographic one, which sorts correctly for ISO-8601 and sidesteps
     * the graph's date-type coercion entirely.
     */
    private static final String SQL = """
            SELECT fromCurrency, toCurrency, rate, rateType, effectiveFrom, source
            FROM FxRate
            WHERE tenantId = :tenantId
              AND fromCurrency = :fromCurrency
              AND toCurrency = :toCurrency
              AND rateType = :rateType
              AND effectiveFrom <= :asOf
            ORDER BY effectiveFrom DESC
            LIMIT 1
            """;

    // A board load prices every money tile, and each tile would otherwise be its
    // own round trip for a table that changes a few times a quarter. Keyed by
    // everything that changes the answer, including the tenant.
    private static final Duration TTL = Duration.ofMinutes(10);
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(Optional<FxRate> rate, Instant readAt) {}

    private final RowQuery query;

    public GraphFxRateSource(RowQuery query) {
        this.query = query;
    }

    @Override
    public Optional<FxRate> rateAt(String fromCurrency, String toCurrency,
                                   FxRate.RateType rateType, LocalDate asOf) {
        String tenantId = String.valueOf(TenantContextHolder.requireTenantId());
        String key = String.join("|", tenantId, fromCurrency, toCurrency, rateType.name(), asOf.toString());

        Cached hit = cache.get(key);
        if (hit != null && Duration.between(hit.readAt(), Instant.now()).compareTo(TTL) < 0) {
            return hit.rate();
        }

        List<Map<String, Object>> rows = query.run(SQL, Map.of(
                "tenantId", tenantId,
                "fromCurrency", fromCurrency,
                "toCurrency", toCurrency,
                "rateType", rateType.name(),
                "asOf", asOf.toString()));

        Optional<FxRate> result = rows.isEmpty() ? Optional.empty() : toRate(rows.get(0));
        // Absence is cached too: a tenant with no rates would otherwise re-query
        // on every tile of every load, forever.
        cache.put(key, new Cached(result, Instant.now()));
        return result;
    }

    /** Drop cached rates — call after an admin edits the table. */
    public void invalidate() {
        cache.clear();
    }

    private Optional<FxRate> toRate(Map<String, Object> row) {
        try {
            return Optional.of(new FxRate(
                    str(row.get("fromCurrency")),
                    str(row.get("toCurrency")),
                    new BigDecimal(str(row.get("rate"))),
                    FxRate.RateType.valueOf(str(row.get("rateType")).toUpperCase(Locale.ROOT)),
                    LocalDate.parse(str(row.get("effectiveFrom"))),
                    str(row.get("source"))));
        } catch (IllegalArgumentException | DateTimeParseException | NullPointerException e) {
            // A malformed row is a configuration problem, not a reason to fail
            // the request: log it loudly and let the caller show the reported
            // figure. Converting at a rate parsed from a bad row would be worse.
            log.warn("Ignoring unusable FxRate row {}: {}", row, e.toString());
            return Optional.empty();
        }
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
