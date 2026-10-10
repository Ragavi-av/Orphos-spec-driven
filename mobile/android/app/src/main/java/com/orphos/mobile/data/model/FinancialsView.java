package com.orphos.mobile.data.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The CFO Financials screen's figures, parsed from GET /api/mobile/financials — the same
 * sources as the CFO's web widget board: net-cashflow, cash-inflow, cash-outflow, budget,
 * budget-variance, payroll, revenue-arr and risk-exposure. Amounts are kept raw (with their
 * currency) so the screen can format them compactly; every field is null when the backend
 * did not supply it, so the screen can hide it instead of inventing one.
 */
public class FinancialsView {

    /** A money figure in its own currency (a widget may be FX-converted independently). */
    public static class Amount {
        public final double value;
        public final String currency;

        public Amount(double value, String currency) {
            this.value = value;
            this.currency = currency;
        }
    }

    /** One month of the cashflow summary. */
    public static class Month {
        public String label;
        public double inflow, outflow, nett;
        public String currency;
    }

    /** A cost centre from the briefing budget lines (the budget / budget-variance widgets). */
    public static class Overrun {
        public String name;
        public double spent, budget;
        public String currency;

        /** Spend as a percentage of budget, or -1 without a budget. */
        public int percentOfBudget() {
            return budget > 0 ? (int) Math.round(spent * 100 / budget) : -1;
        }
    }

    public String fiscalYear;
    /** Latest month in the cashflow summary, e.g. "Aug". */
    public String latestMonth;

    /** net-cashflow / cash-inflow / cash-outflow widgets. */
    public Amount netCashflow, cashInflow, cashOutflow;
    /** revenue-arr, payroll and risk-exposure widgets. */
    public Amount revenueArr, payroll, riskExposure;
    /** budget-variance widget, already rendered in its own unit. */
    public String budgetVariance;

    public final List<Month> months = new ArrayList<>();
    public final List<Overrun> overruns = new ArrayList<>();

    /** The "IT Budget Consumed" widget: spent / budget across the cost centres, or -1. */
    public int budgetConsumedPct() {
        double spent = 0, budget = 0;
        for (Overrun o : overruns) { spent += o.spent; budget += o.budget; }
        return budget > 0 ? (int) Math.round(spent * 100 / budget) : -1;
    }

    public boolean isEmpty() {
        return netCashflow == null && cashInflow == null && cashOutflow == null
                && revenueArr == null && payroll == null && riskExposure == null
                && budgetVariance == null && months.isEmpty() && overruns.isEmpty();
    }
}
