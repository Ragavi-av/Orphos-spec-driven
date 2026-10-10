package io.orphos.eip.common.money;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The conversion rules that cannot be seen by reading a call site.
 *
 * <p>Every case here is one where a plausible implementation gets it wrong in a
 * way that still renders a believable number on the board — which is the only
 * kind of bug that matters for money on an executive dashboard. A converted
 * figure that is obviously broken gets reported; one that is quietly priced at
 * the wrong rate gets acted on.</p>
 */
class MoneyConverterTest {

    private static final LocalDate AUG_END = LocalDate.parse("2026-08-31");

    /** Rate table stub: whatever rows the test puts in, matched the way the real source matches. */
    private static class Table implements FxRateSource {
        final List<FxRate> rows = new ArrayList<>();
        int lookups = 0;

        Table with(String from, String to, String rate, FxRate.RateType type, String effectiveFrom) {
            rows.add(new FxRate(from, to, new BigDecimal(rate), type, LocalDate.parse(effectiveFrom), "test"));
            return this;
        }

        @Override
        public Optional<FxRate> rateAt(String from, String to, FxRate.RateType type, LocalDate asOf) {
            lookups++;
            return rows.stream()
                    .filter(r -> r.fromCurrency().equals(from) && r.toCurrency().equals(to))
                    .filter(r -> r.rateType() == type)
                    .filter(r -> !r.effectiveFrom().isAfter(asOf))
                    .max((a, b) -> a.effectiveFrom().compareTo(b.effectiveFrom()));
        }
    }

    @Test
    void convertsAtTheRateInForceOnThePeriodEnd() {
        Table table = new Table()
                .with("INR", "JPY", "1.70", FxRate.RateType.CLOSING, "2026-01-01")
                .with("INR", "JPY", "1.80", FxRate.RateType.CLOSING, "2026-08-01");
        ConvertedAmount out = new MoneyConverter(table)
                .convert(new BigDecimal("58550000"), "INR", "JPY", FigureKind.BALANCE, AUG_END);

        assertTrue(out.converted());
        assertEquals(0, new BigDecimal("105390000").compareTo(out.value()));
        assertEquals("JPY", out.currency());
        // The reported figure travels with it — the tooltip has nothing to show otherwise.
        assertEquals(0, new BigDecimal("58550000").compareTo(out.sourceValue()));
        assertEquals("INR", out.sourceCurrency());
        assertEquals(LocalDate.parse("2026-08-01"), out.rateAsOf());
    }

    @Test
    void neverPricesAPeriodAtARateThatPostdatesIt() {
        // A rate entered in September must not retroactively change August's
        // number — otherwise reopening a closed period shows a different figure
        // than it did last month.
        Table table = new Table()
                .with("INR", "JPY", "1.80", FxRate.RateType.CLOSING, "2026-08-01")
                .with("INR", "JPY", "1.95", FxRate.RateType.CLOSING, "2026-09-15");
        ConvertedAmount out = new MoneyConverter(table)
                .convert(new BigDecimal("1000000"), "INR", "JPY", FigureKind.BALANCE, AUG_END);

        assertEquals(LocalDate.parse("2026-08-01"), out.rateAsOf());
        assertEquals(0, new BigDecimal("1800000").compareTo(out.value()));
    }

    @Test
    void aFlowTakesTheAverageRateAndABalanceTheClosingRate() {
        // Both types exist for the same pair and date. Picking by FigureKind is
        // the whole point; a converter that ignored it would still return a
        // plausible number here, just the wrong one.
        Table table = new Table()
                .with("INR", "JPY", "1.75", FxRate.RateType.AVERAGE, "2026-08-01")
                .with("INR", "JPY", "1.80", FxRate.RateType.CLOSING, "2026-08-01");
        MoneyConverter converter = new MoneyConverter(table);

        ConvertedAmount flow = converter.convert(
                new BigDecimal("-380000"), "INR", "JPY", FigureKind.FLOW, AUG_END);
        ConvertedAmount balance = converter.convert(
                new BigDecimal("-380000"), "INR", "JPY", FigureKind.BALANCE, AUG_END);

        assertEquals(FxRate.RateType.AVERAGE, flow.rateType());
        assertEquals(0, new BigDecimal("-665000").compareTo(flow.value()));
        assertEquals(FxRate.RateType.CLOSING, balance.rateType());
        assertEquals(0, new BigDecimal("-684000").compareTo(balance.value()));
    }

    @Test
    void showsTheReportedAmountWhenNoRateCoversTheDate() {
        Table table = new Table().with("INR", "JPY", "1.80", FxRate.RateType.CLOSING, "2027-01-01");
        ConvertedAmount out = new MoneyConverter(table)
                .convert(new BigDecimal("58550000"), "INR", "JPY", FigureKind.BALANCE, AUG_END);

        assertFalse(out.converted());
        assertEquals("INR", out.currency());
        assertEquals(0, new BigDecimal("58550000").compareTo(out.value()));
        assertNull(out.rate());
    }

    @Test
    void showsTheReportedAmountWhenTheRateTableThrows() {
        FxRateSource broken = (f, t, ty, d) -> { throw new IllegalStateException("graph down"); };
        ConvertedAmount out = new MoneyConverter(broken)
                .convert(new BigDecimal("1650000"), "INR", "JPY", FigureKind.FLOW, AUG_END);

        // The board stays up and honest rather than blanking the tile.
        assertFalse(out.converted());
        assertEquals(0, new BigDecimal("1650000").compareTo(out.value()));
        assertEquals("INR", out.currency());
    }

    @Test
    void doesNotMarkAnAmountConvertedWhenItIsAlreadyInTheTargetCurrency() {
        Table table = new Table().with("JPY", "JPY", "1", FxRate.RateType.CLOSING, "2020-01-01");
        ConvertedAmount out = new MoneyConverter(table)
                .convert(new BigDecimal("5000"), "JPY", "JPY", FigureKind.BALANCE, AUG_END);

        assertFalse(out.converted());
        // No lookup at all — a same-currency "conversion" that still consults the
        // table would put a rate marker on a figure nobody converted.
        assertEquals(0, table.lookups);
    }

    @Test
    void roundsToTheTargetCurrencysMinorUnit() {
        Table table = new Table()
                .with("INR", "JPY", "1.7345", FxRate.RateType.CLOSING, "2026-01-01")
                .with("JPY", "INR", "0.5766", FxRate.RateType.CLOSING, "2026-01-01");
        MoneyConverter converter = new MoneyConverter(table);

        // JPY has no minor unit: ¥1,734.50 would read as wrong to anyone who
        // works in yen, however arithmetically defensible it is.
        ConvertedAmount yen = converter.convert(
                new BigDecimal("1000"), "INR", "JPY", FigureKind.BALANCE, AUG_END);
        assertEquals(0, yen.value().scale());

        ConvertedAmount rupees = converter.convert(
                new BigDecimal("1000"), "JPY", "INR", FigureKind.BALANCE, AUG_END);
        assertEquals(2, rupees.value().scale());
    }

    @Test
    void convertsValueAndTargetAtOneRateSoAttainmentSurvives() {
        // -380,000 against a target of 1,500,000 is -25%, which is what the tile
        // prints. That percentage must not move because the two numbers were
        // priced separately.
        Table table = new Table().with("INR", "JPY", "1.80", FxRate.RateType.AVERAGE, "2026-08-01");
        ConvertedAmount[] pair = new MoneyConverter(table).convertWithTarget(
                new BigDecimal("-380000"), new BigDecimal("1500000"),
                "INR", "JPY", FigureKind.FLOW, AUG_END);

        assertTrue(pair[0].converted());
        assertTrue(pair[1].converted());
        assertEquals(pair[0].rate(), pair[1].rate());
        assertEquals(pair[0].rateAsOf(), pair[1].rateAsOf());
        double attainmentBefore = -380000d / 1500000d;
        double attainmentAfter = pair[0].value().doubleValue() / pair[1].value().doubleValue();
        assertEquals(attainmentBefore, attainmentAfter, 1e-9);
    }

    @Test
    void leavesTheTargetReportedWhenTheValueCouldNotBeConverted() {
        // Half-converting a tile is the worst outcome: the number changes unit
        // while the target it is measured against does not.
        Table empty = new Table();
        ConvertedAmount[] pair = new MoneyConverter(empty).convertWithTarget(
                new BigDecimal("-380000"), new BigDecimal("1500000"),
                "INR", "JPY", FigureKind.FLOW, AUG_END);

        assertFalse(pair[0].converted());
        assertFalse(pair[1].converted());
        assertEquals("INR", pair[0].currency());
        assertEquals("INR", pair[1].currency());
    }

    @Test
    void treatsAnAbsentOrUnreadableTargetCurrencyAsNoPreference() {
        Table table = new Table().with("INR", "JPY", "1.80", FxRate.RateType.CLOSING, "2026-01-01");
        MoneyConverter converter = new MoneyConverter(table);

        for (String requested : new String[]{null, "", "  ", "YEN", "J"}) {
            ConvertedAmount out = converter.convert(
                    new BigDecimal("100"), "INR", requested, FigureKind.BALANCE, AUG_END);
            assertFalse(out.converted(), "should not convert for requested=" + requested);
            assertEquals("INR", out.currency());
        }
    }

    @Test
    void unknownFigureKindsFallBackToASingleDatedRate() {
        // FigureKind.parse defaults to BALANCE so an unclassified metric converts
        // at one rate the reader can be shown, rather than at a period average
        // that implies a classification nobody made.
        assertEquals(FigureKind.BALANCE, FigureKind.parse(null));
        assertEquals(FigureKind.BALANCE, FigureKind.parse("   "));
        assertEquals(FigureKind.BALANCE, FigureKind.parse("nonsense"));
        assertEquals(FigureKind.FLOW, FigureKind.parse("flow"));
        assertEquals(FigureKind.FLOW, FigureKind.parse(" FLOW "));
    }

    @Test
    void rejectsARateThatWouldZeroOrFlipEveryFigure() {
        assertThrows(IllegalArgumentException.class, () -> new FxRate(
                "INR", "JPY", BigDecimal.ZERO, FxRate.RateType.CLOSING, AUG_END, "test"));
        assertThrows(IllegalArgumentException.class, () -> new FxRate(
                "INR", "JPY", new BigDecimal("-1.8"), FxRate.RateType.CLOSING, AUG_END, "test"));
    }
}
