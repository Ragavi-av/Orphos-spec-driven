package io.orphos.eip.admin.service;

import io.orphos.eip.common.money.FxRate;
import io.orphos.eip.common.money.GraphFxRateSource;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The tenant's FX rate table — read and maintained by tenant admins.
 *
 * <h2>Rows are added, never edited in place</h2>
 * Superseding a rate means writing a later {@code effectiveFrom}, and the lookup
 * takes the most recent row on or before the date being priced. That keeps a
 * closed period showing the number it always showed: editing August's rate in
 * November would silently restate every August figure anyone had already read
 * and acted on. {@link #delete} exists for genuine mistakes — a typo entered
 * this morning — not as an edit path.
 */
@Service
public class FxRateAdminService {

    private static final Logger log = LogManager.getLogger(FxRateAdminService.class);

    /** What an admin sees and submits. {@code id} is null on create. */
    public record RateRow(String id, String fromCurrency, String toCurrency,
                          String rate, String rateType, String effectiveFrom, String source) {}

    private final ArcadeDbTemplate arcade;
    private final GraphFxRateSource rateSource;

    public FxRateAdminService(ArcadeDbTemplate arcade, GraphFxRateSource rateSource) {
        this.arcade = arcade;
        this.rateSource = rateSource;
    }

    /** Every rate this tenant holds, newest effective date first. */
    public List<RateRow> list() {
        String tenantId = tenant();
        List<Map<String, Object>> rows = arcade.query(
                "SELECT @rid AS rid, fromCurrency, toCurrency, rate, rateType, effectiveFrom, source "
                        + "FROM FxRate WHERE tenantId = :tenantId "
                        + "ORDER BY effectiveFrom DESC, fromCurrency ASC, toCurrency ASC",
                Map.of("tenantId", tenantId));

        List<RateRow> out = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            out.add(new RateRow(
                    str(r.get("rid")), str(r.get("fromCurrency")), str(r.get("toCurrency")),
                    str(r.get("rate")), str(r.get("rateType")),
                    str(r.get("effectiveFrom")), str(r.get("source"))));
        }
        return out;
    }

    /**
     * Add one rate.
     *
     * <p>Validated by constructing an {@link FxRate} first: that record rejects a
     * non-positive rate, a malformed currency code and a missing date, and doing
     * it here means a bad row can never reach the table rather than being caught
     * later by the reader that has to ignore it.</p>
     */
    public RateRow create(RateRow req) {
        FxRate validated = new FxRate(
                req.fromCurrency(),
                req.toCurrency(),
                new BigDecimal(req.rate()),
                FxRate.RateType.valueOf(String.valueOf(req.rateType()).trim().toUpperCase(Locale.ROOT)),
                LocalDate.parse(req.effectiveFrom()),
                req.source());

        if (validated.fromCurrency().equals(validated.toCurrency())) {
            throw new IllegalArgumentException("A currency does not need a rate to itself");
        }

        String id = UUID.randomUUID().toString();
        arcade.commandNoResult(
                "INSERT INTO FxRate SET id = :id, tenantId = :tenantId, "
                        + "fromCurrency = :fromCurrency, toCurrency = :toCurrency, rate = :rate, "
                        + "rateType = :rateType, effectiveFrom = :effectiveFrom, source = :source",
                Map.of("id", id,
                        "tenantId", tenant(),
                        "fromCurrency", validated.fromCurrency(),
                        "toCurrency", validated.toCurrency(),
                        // Stored as text so no float representation gets between
                        // what the admin typed and what the reader is shown.
                        "rate", validated.rate().toPlainString(),
                        "rateType", validated.rateType().name(),
                        "effectiveFrom", validated.effectiveFrom().toString(),
                        "source", validated.source()));

        // The reader caches lookups for minutes; without this an admin would add a
        // rate, reload the board, and reasonably conclude the feature is broken.
        rateSource.invalidate();
        log.info("FX rate added: {} {}→{} @{} from {}", validated.rateType(),
                validated.fromCurrency(), validated.toCurrency(),
                validated.rate().toPlainString(), validated.effectiveFrom());

        return new RateRow(id, validated.fromCurrency(), validated.toCurrency(),
                validated.rate().toPlainString(), validated.rateType().name(),
                validated.effectiveFrom().toString(), validated.source());
    }

    /** Remove one rate by its {@code id}. Tenant-scoped so one tenant cannot delete another's. */
    public void delete(String id) {
        arcade.commandNoResult(
                "DELETE FROM FxRate WHERE tenantId = :tenantId AND id = :id",
                Map.of("tenantId", tenant(), "id", id));
        rateSource.invalidate();
        log.info("FX rate deleted: {}", id);
    }

    private static String tenant() {
        return TenantContextHolder.requireTenantId().toString();
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }
}
