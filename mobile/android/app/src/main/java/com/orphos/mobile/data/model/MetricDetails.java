package com.orphos.mobile.data.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The records behind the Metrics tab's tiles, from /api/mobile/metrics {@code details}:
 * objectives and key results, open risks, projects in flight, the pipeline, over-budget
 * cost centres and monthly cash flow. A section the caller's persona may not see is simply
 * absent server-side, so here it is an empty list.
 */
public class MetricDetails {

    public List<Objective> objectives = new ArrayList<>();
    public List<Risk> risks = new ArrayList<>();
    public List<Project> projects = new ArrayList<>();
    public List<Opportunity> pipeline = new ArrayList<>();
    public List<CostCentre> overBudget = new ArrayList<>();
    /** All over-budget cost centres, not just the ones listed. -1 when unknown. */
    public int overBudgetCount = -1;
    public List<CashMonth> cashflow = new ArrayList<>();
    public String cashCurrency;
    public String fiscalYear;

    public static class Objective {
        public String name;
        public int progress;
        public List<KeyResult> keyResults = new ArrayList<>();
    }

    public static class KeyResult {
        public String name;
        public int progress;
    }

    public static class Risk {
        public String name, category, status, currency, dueDate;
        public int severity;
        public double exposure = Double.NaN;
    }

    public static class Project {
        public String name, status, owner, currency, endDate;
        public int progress = -1;
        public double budget = Double.NaN, spent = Double.NaN;

        public boolean troubled() {
            return "AT_RISK".equals(status) || "DELAYED".equals(status);
        }
    }

    public static class Opportunity {
        public String name, stage, currency, closeDate;
        public double probability = Double.NaN, dealValue = Double.NaN, weightedValue = Double.NaN;
    }

    public static class CostCentre {
        public String name, owner, currency, period;
        public double budget = Double.NaN, spent = Double.NaN, variance = Double.NaN;
    }

    public static class CashMonth {
        public String label;
        public double inflow, outflow, nett;
    }
}
