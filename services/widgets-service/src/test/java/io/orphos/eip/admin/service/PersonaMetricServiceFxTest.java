package io.orphos.eip.admin.service;

import io.orphos.eip.admin.component.PersonaWidgetPolicy;
import io.orphos.eip.admin.service.PersonaMetricService.MetricValue;
import io.orphos.eip.common.money.FxRate;
import io.orphos.eip.common.money.FxRateSource;
import io.orphos.eip.common.money.MoneyConverter;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Where the converter meets the metric store.
 *
 * <p>The conversion arithmetic is covered in {@code MoneyConverterTest}; what is
 * tested here is the wiring around it, which is where a figure can be converted
 * that never should have been. A percentage multiplied by an FX rate still
 * renders as a number, on a tile that looks exactly like the correct one.</p>
 */
class PersonaMetricServiceFxTest {

    private ArcadeDbTemplate arcade;
    private PersonaMetricService service;

    /** One INR→JPY rate of each type, effective long before any test period. */
    private static final FxRateSource RATES = (from, to, type, asOf) ->
            ("INR".equals(from) && "JPY".equals(to))
                    ? Optional.of(new FxRate(from, to, new BigDecimal("1.80"), type,
                        LocalDate.parse("2026-01-01"), "test table"))
                    : Optional.empty();

    @BeforeEach
    void setUp() {
        arcade = mock(ArcadeDbTemplate.class);
        PersonaWidgetPolicy policy = mock(PersonaWidgetPolicy.class);
        // Access control is a separate concern with its own tests; here every
        // widget is permitted so the money path is what the assertions see.
        when(policy.enforced()).thenReturn(false);
        service = new PersonaMetricService(arcade, policy, new MoneyConverter(RATES));
        TenantContextHolder.set(new TenantContext(UUID.randomUUID(), "test", false));
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    private void stubRow(String unit, String currency, Object value, Object target,
                         String figureKind, String periodEnd) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("value", value);
        row.put("targetValue", target);
        row.put("ragStatus", "GREEN");
        row.put("currency", currency);
        // ArcadeDB returns traversal projections as single-element lists.
        row.put("unit", List.of(unit));
        if (figureKind != null) row.put("figureKind", List.of(figureKind));
        row.put("periodName", List.of("Aug 2026"));
        row.put("periodStart", List.of("2026-08-01"));
        row.put("periodEnd", List.of(periodEnd));
        when(arcade.query(anyString(), any())).thenReturn(List.of(row));
    }

    @Test
    void convertsACurrencyFigureAndKeepsTheReportedOneBeside() {
        stubRow("CURRENCY", "INR", 58550000, 40000000, "BALANCE", "2026-08-31");

        MetricValue out = service.latest("wid-risk-exposure", "JPY");

        assertEquals("JPY", out.currency());
        assertEquals(0, new BigDecimal("105390000.00").compareTo(new BigDecimal(out.value().toString())));
        assertNotNull(out.fx());
        assertEquals("INR", out.fx().reportedCurrency());
        assertEquals(58550000, ((Number) out.fx().reportedValue()).longValue());
        assertEquals("CLOSING", out.fx().rateType());
        assertEquals("2026-01-01", out.fx().rateAsOf());
    }

    @Test
    void convertsTheTargetToo() {
        // The tile prints attainment as value/target. A converted value against a
        // reported target would silently rescale that percentage by the rate.
        stubRow("CURRENCY", "INR", 58550000, 40000000, "BALANCE", "2026-08-31");

        MetricValue out = service.latest("wid-risk-exposure", "JPY");

        assertEquals(0, new BigDecimal("72000000.00")
                .compareTo(new BigDecimal(out.targetValue().toString())));
        assertEquals(40000000, ((Number) out.fx().reportedTarget()).longValue());
    }

    @Test
    void neverConvertsANonCurrencyFigure() {
        // A PERCENT row carrying a stray currency property is the trap: 99.6%
        // times 1.8 is 179.28, which renders as a plausible-looking uptime.
        stubRow("PERCENT", "INR", 99.6, 99.9, "BALANCE", "2026-08-31");

        MetricValue out = service.latest("wid-uptime", "JPY");

        assertNull(out.fx());
        assertEquals(99.6, ((Number) out.value()).doubleValue(), 1e-9);
        assertEquals("INR", out.currency());
    }

    @Test
    void leavesTheFigureReportedWhenTheReaderAsksForNoCurrency() {
        stubRow("CURRENCY", "INR", 1850000, 0, "FLOW", "2026-08-31");

        MetricValue out = service.latest("wid-budget-variance");

        assertNull(out.fx());
        assertEquals("INR", out.currency());
        assertEquals(1850000, ((Number) out.value()).longValue());
    }

    @Test
    void leavesTheFigureReportedWhenNoRateCoversThePair() {
        stubRow("CURRENCY", "INR", 1850000, 0, "FLOW", "2026-08-31");

        MetricValue out = service.latest("wid-budget-variance", "EUR");

        assertNull(out.fx());
        assertEquals("INR", out.currency());
    }

    @Test
    void anUnclassifiedMetricStillConverts() {
        // figureKind is absent on any vocabulary loaded before the property
        // existed. Those metrics must not stop converting — they default to a
        // closing rate, which FigureKind documents as the safer of the two.
        stubRow("CURRENCY", "INR", 1000000, null, null, "2026-08-31");

        MetricValue out = service.latest("wid-payroll", "JPY");

        assertNotNull(out.fx());
        assertEquals("CLOSING", out.fx().rateType());
    }

    @Test
    void aDeniedOrEmptyResultCarriesNoConversion() {
        when(arcade.query(anyString(), any())).thenReturn(List.of());

        MetricValue out = service.latest("wid-risk-exposure", "JPY");

        assertFalse(out.available());
        assertNull(out.fx());
    }
}
