package io.orphos.eip.admin.service;

import io.orphos.eip.admin.component.PersonaWidgetPolicy;
import io.orphos.eip.common.money.ConvertedAmount;
import io.orphos.eip.common.money.FigureKind;
import io.orphos.eip.common.money.MoneyConverter;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Serves widget values from {@code GovMetricObservation} — the ontology-backed
 * metric store — rather than from a bespoke per-widget endpoint.
 *
 * <h2>Why this exists</h2>
 * The original 15 widgets each read a hand-written endpoint
 * ({@code /api/priority/sla}, {@code /api/briefing/latest},
 * {@code /api/zenitus/hr/summary} …), which is why the dashboard could only ever
 * show what someone had already built a feed for — and why it skewed towards IT.
 * Every widget added through the ontology instead declares a {@code gov:Metric}
 * with a domain and a sensitivity, so its value can be served generically and, more
 * importantly, <b>filtered by the same persona rule as everything else</b>. Adding a
 * widget becomes a vocabulary change plus a display tile, not a new backend feed.
 *
 * <h2>Permission is re-checked here, not assumed</h2>
 * The layout endpoint already hides widgets a persona may not see, but a client can
 * call this endpoint directly with any widget key. So the persona check is repeated
 * on the value path: hiding a tile is presentation, refusing the number is access
 * control, and the second must not depend on the first.
 */
@Service
public class PersonaMetricService {

    private static final Logger log = LogManager.getLogger(PersonaMetricService.class);

    private final ArcadeDbTemplate arcade;
    private final PersonaWidgetPolicy personaPolicy;
    private final MoneyConverter money;

    public PersonaMetricService(ArcadeDbTemplate arcade, PersonaWidgetPolicy personaPolicy,
                                MoneyConverter money) {
        this.arcade = arcade;
        this.personaPolicy = personaPolicy;
        this.money = money;
    }

    /**
     * Provenance for a converted figure, or null when nothing was converted.
     *
     * <p>Nested rather than flattened into {@link MetricValue} so the wire shape
     * says what it means: {@code fx == null} is "this is the reported number",
     * and the SPA needs no flag comparison to decide whether to draw the
     * conversion marker. Every field the tooltip shows is here, because a
     * converted figure the reader cannot trace back is not worth showing.</p>
     *
     * @param reportedValue  the figure as stored, in {@code reportedCurrency}
     * @param reportedTarget the target as stored, same currency; null if none
     * @param rateAsOf       ISO date of the rate — the "as of" in the tooltip
     * @param rateType       AVERAGE or CLOSING, so the reader knows which was right
     */
    public record Fx(Object reportedValue, Object reportedTarget, String reportedCurrency,
                     BigDecimal rate, String rateAsOf, String rateType, String rateSource) {}

    /** A widget value ready for display, or {@link #denied()} / {@link #noData()}. */
    public record MetricValue(String widgetKey, Object value, Object targetValue, String unit,
                              String currency, String ragStatus, String period, String subtitle,
                              String periodStart, String periodEnd,
                              boolean permitted, boolean available, Fx fx) {}

    public static MetricValue denied(String widgetKey) {
        return new MetricValue(widgetKey, null, null, null, null, null, null,
                "Not available for your role", null, null, false, false, null);
    }

    public static MetricValue noData(String widgetKey) {
        return new MetricValue(widgetKey, null, null, null, null, null, null,
                "No data for this period", null, null, true, false, null);
    }

    /**
     * Latest observation for the metric rendered by {@code widgetKey}.
     *
     * <p>"Latest" is by the period's {@code startDate}, not by insertion order, so
     * back-filling an older period cannot displace the current figure.</p>
     */
    public MetricValue latest(String widgetKey) {
        return latest(widgetKey, null);
    }

    /**
     * As {@link #latest(String)}, restated in {@code displayCurrency} where a rate
     * allows it.
     *
     * <p>The display currency comes from the reader's country pick in the SPA, so
     * it is a request parameter rather than tenant configuration — two people
     * looking at the same tenant may want different currencies, and neither is
     * more correct. When no rate covers the observation's period the reported
     * figure is returned unchanged with {@code fx == null}; the reader then sees
     * the real currency rather than a number priced at a rate nobody chose.</p>
     */
    public MetricValue latest(String widgetKey, String displayCurrency) {
        if (widgetKey == null || widgetKey.isBlank()) return noData(widgetKey);

        // Access check first — never query the number we might have to refuse.
        if (personaPolicy.enforced()
                && personaPolicy.permitted(List.of(widgetKey), personaPolicy.currentRoles()).isEmpty()) {
            log.debug("Persona metric refused: widget={} roles={}", widgetKey, personaPolicy.currentRoles());
            return denied(widgetKey);
        }

        try {
            String tenantId = TenantContextHolder.requireTenantId().toString();
            List<Map<String, Object>> rows = arcade.query(
                    // The metric is OUT from the observation (an observation is an
                    // observation OF a metric) — same direction the WHERE clause below
                    // uses. This projection previously traversed in(), which matches no
                    // rows, so `unit` was always null and every CURRENCY/PERCENT value
                    // rendered as a naked number (₹5.86 Cr showing as 58550000).
                    "SELECT value, targetValue, ragStatus, currency, "
                            + "  out('OBSERVATION_OF').unit AS unit, "
                            // Flow or balance — decides whether a converted figure
                            // takes the period's average rate or its closing rate.
                            // Declared on the metric, defaulted by FigureKind.parse
                            // when a tenant's vocabulary predates the property.
                            + "  out('OBSERVATION_OF').figureKind AS figureKind, "
                            + "  out('FOR_PERIOD').name AS periodName, "
                            + "  out('FOR_PERIOD').startDate AS periodStart, "
                            + "  out('FOR_PERIOD').endDate AS periodEnd "
                            + "FROM GovMetricObservation "
                            + "WHERE tenantId = :tenantId "
                            + "  AND out('OBSERVATION_OF').out('RENDERED_BY').widgetKey CONTAINS :widgetKey "
                            + "  AND out('SCOPED_TO_ORG_UNIT').size() = 0 "
                            + "ORDER BY periodStart DESC LIMIT 1",
                    Map.of("tenantId", tenantId, "widgetKey", widgetKey));

            if (rows.isEmpty()) return noData(widgetKey);
            Map<String, Object> r = rows.get(0);

            String unit = first(r.get("unit"));
            String currency = str(r.get("currency"));
            Object value = r.get("value");
            Object target = r.get("targetValue");
            Fx fx = null;

            // Only CURRENCY figures convert. A PERCENT or a count carrying a stray
            // currency property must be left exactly as reported — multiplying an
            // uptime percentage by an FX rate is the kind of error that survives
            // review because the result still looks like a number.
            if ("CURRENCY".equals(unit) && currency != null) {
                ConvertedAmount[] pair = money.convertWithTarget(
                        decimal(value), decimal(target), currency, displayCurrency,
                        FigureKind.parse(first(r.get("figureKind"))),
                        date(first(r.get("periodEnd"))));
                if (pair[0].converted()) {
                    fx = new Fx(value, target, currency,
                            pair[0].rate(),
                            pair[0].rateAsOf() == null ? null : pair[0].rateAsOf().toString(),
                            pair[0].rateType() == null ? null : pair[0].rateType().name(),
                            pair[0].rateSource());
                    value = pair[0].value();
                    target = pair[1].value();
                    currency = pair[0].currency();
                }
            }

            return new MetricValue(
                    widgetKey,
                    value,
                    target,
                    unit,
                    currency,
                    str(r.get("ragStatus")),
                    first(r.get("periodName")),
                    null,
                    // Period bounds let the SPA show pace: 33% progress at 50% of the
                    // period elapsed is behind, and should colour amber on its own.
                    // Null when the period carries no endDate — the SPA then simply
                    // omits pace rather than guessing.
                    first(r.get("periodStart")),
                    first(r.get("periodEnd")),
                    true, true, fx);
        } catch (Exception e) {
            // Missing Gov* types (tenant has not adopted the model) or a database
            // blip. Reported as "no data" rather than an error: a dashboard tile
            // showing a dash is the right degradation, and it must not be confused
            // with "refused", which is a policy outcome the UI words differently.
            log.debug("Persona metric unavailable (widget={}): {}", widgetKey, e.getMessage());
            return noData(widgetKey);
        }
    }

    /** Widget keys this tenant can serve values for — i.e. keys with a metric bound. */
    public Optional<List<String>> servableKeys() {
        try {
            String tenantId = TenantContextHolder.requireTenantId().toString();
            List<Map<String, Object>> rows = arcade.query(
                    "SELECT out('RENDERED_BY').widgetKey AS widgetKey FROM GovMetric "
                            + "WHERE tenantId = :tenantId", Map.of("tenantId", tenantId));
            List<String> keys = new java.util.ArrayList<>();
            for (Map<String, Object> r : rows) {
                String k = first(r.get("widgetKey"));
                if (k != null && !keys.contains(k)) keys.add(k);
            }
            return Optional.of(keys);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Coerce a stored value to BigDecimal, or null.
     *
     * <p>Via {@code String.valueOf} rather than a cast: the graph hands back
     * Integer, Long, Double or String for the same column depending on how the
     * row was written, and going through the text form keeps a Double's binary
     * representation from leaking into a money figure.</p>
     */
    private static BigDecimal decimal(Object v) {
        String s = str(v);
        if (s == null) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Period end as a date, or null — an unparseable bound just means "price it today". */
    private static LocalDate date(String iso) {
        if (iso == null) return null;
        try {
            // Tolerates both "2026-08-31" and a full timestamp, which the period
            // rows carry inconsistently depending on which loader wrote them.
            return LocalDate.parse(iso.length() > 10 ? iso.substring(0, 10) : iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** ArcadeDB returns traversal projections as lists even for a single match. */
    private static String first(Object v) {
        if (v instanceof List<?> l) return l.isEmpty() ? null : str(l.get(0));
        return str(v);
    }

    private static String str(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o).trim();
        return s.isEmpty() || "null".equals(s) ? null : s;
    }
}
