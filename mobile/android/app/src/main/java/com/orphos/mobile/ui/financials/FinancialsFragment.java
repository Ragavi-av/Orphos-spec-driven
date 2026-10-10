package com.orphos.mobile.ui.financials;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.orphos.mobile.MainActivity;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.FinancialsView;
import com.orphos.mobile.ui.common.HeaderActions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Financials (Figma "ORPHOS-1"): section tabs, quick actions and the Dashboard / P&L /
 * Cash flow / Expenses sections, built only from the BFF financials aggregation
 * (read-only). A figure the backend does not supply is left out, never sampled.
 */
public class FinancialsFragment extends Fragment {

    private HeaderActions header;

    @Override
    public void onResume() {
        super.onResume();
        header.refreshStatus();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_financials, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        View back = v.findViewById(R.id.fin_back);
        if (back != null) back.setOnClickListener(x -> {
            if (getActivity() instanceof MainActivity m) m.selectTab(R.id.nav_home);
        });

        // Shared header: status dot, bell and search (→ Ask Orphos) behave exactly as on Home.
        header = HeaderActions.bind(this, v, v.findViewById(R.id.fin_search));

        // Role-based work tab: Financials (CFO), Resource (CHRO) or Operations (COO).
        // The CEO's Metrics tab is CompanyMetricsFragment.
        com.orphos.mobile.core.role.Role role =
                com.orphos.mobile.core.role.Role.of(OrphosApp.get().config().userRole());
        ((TextView) v.findViewById(R.id.fin_title)).setText(role.workTitleRes());

        if (role.isFinance()) {
            setupTabs(v);
            OrphosApp.get().repo().financialsView((fv, fromMock) -> {
                if (!isAdded() || getView() == null) return;
                renderFinancials(getView(), fv, fromMock);
            });
        } else {
            hide(v, R.id.fin_status);
            // Non-finance role: hide the finance dashboard and show role widgets only.
            hide(v, R.id.fin_tabs_scroll);
            hide(v, R.id.fin_quick_scroll);
            hide(v, R.id.fin_section_dashboard);
            hide(v, R.id.fin_section_pnl);
            hide(v, R.id.fin_section_cashflow);
            hide(v, R.id.fin_section_expenses);
            v.findViewById(R.id.fin_role_widgets).setVisibility(View.VISIBLE);
            OrphosApp.get().repo().roleWidgets(role.name(), (cards, fromMock) -> {
                if (!isAdded() || getView() == null) return;
                renderRoleWidgets(getView().findViewById(R.id.fin_role_widgets), cards);
            });
        }
    }

    private void hide(View root, int id) {
        View x = root.findViewById(id);
        if (x != null) x.setVisibility(View.GONE);
    }

    /** Two-per-row cards of role widget data (label, value, delta). */
    private void renderRoleWidgets(LinearLayout container, java.util.List<String[]> cards) {
        container.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = null;
        for (int i = 0; i < cards.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(requireContext());
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                rlp.bottomMargin = Math.round(12 * d);
                row.setLayoutParams(rlp);
                container.addView(row);
            }
            row.addView(widgetCard(cards.get(i), i % 2 == 1, d));
            if (i % 2 == 0 && i == cards.size() - 1) {
                // odd count: pad the trailing slot so the last card doesn't stretch full width.
                View spacer = new View(requireContext());
                LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.MATCH_PARENT, 1f);
                sp.leftMargin = Math.round(12 * d);
                spacer.setLayoutParams(sp);
                row.addView(spacer);
            }
        }
    }

    private View widgetCard(String[] card, boolean second, float d) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.card_bg);
        box.setPadding(Math.round(16 * d), Math.round(16 * d), Math.round(16 * d), Math.round(16 * d));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        if (second) lp.leftMargin = Math.round(12 * d);
        box.setLayoutParams(lp);

        TextView label = new TextView(requireContext());
        label.setText(card[0]);
        label.setTextColor(requireContext().getColor(R.color.text_secondary));
        label.setTextSize(12f);
        box.addView(label);

        TextView value = new TextView(requireContext());
        value.setText(card[1]);
        value.setTextColor(requireContext().getColor(R.color.text));
        value.setTextSize(22f);
        value.setTypeface(value.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        vlp.topMargin = Math.round(6 * d);
        value.setLayoutParams(vlp);
        box.addView(value);

        if (card.length > 2 && card[2] != null && !card[2].isEmpty()) {
            TextView delta = new TextView(requireContext());
            delta.setText(card[2]);
            boolean down = card[2].contains("↓");
            delta.setTextColor(requireContext().getColor(down ? R.color.red : R.color.green));
            delta.setTextSize(11f);
            box.addView(delta);
        }
        return box;
    }

    /** Section tabs: each pill swaps the active pill style and the visible section. */
    private void setupTabs(View v) {
        // Ordered map of tab pill -> its section container.
        Map<Integer, Integer> tabs = new LinkedHashMap<>();
        tabs.put(R.id.fin_tab_dashboard, R.id.fin_section_dashboard);
        tabs.put(R.id.fin_tab_pnl, R.id.fin_section_pnl);
        tabs.put(R.id.fin_tab_cashflow, R.id.fin_section_cashflow);
        tabs.put(R.id.fin_tab_expenses, R.id.fin_section_expenses);

        for (Map.Entry<Integer, Integer> e : tabs.entrySet()) {
            TextView pill = v.findViewById(e.getKey());
            if (pill != null) pill.setOnClickListener(x -> selectSection(v, tabs, e.getKey()));
        }
    }

    private void selectSection(View v, Map<Integer, Integer> tabs, int selectedTab) {
        for (Map.Entry<Integer, Integer> e : tabs.entrySet()) {
            boolean active = e.getKey() == selectedTab;
            TextView pill = v.findViewById(e.getKey());
            if (pill != null) {
                pill.setBackgroundResource(active ? R.drawable.pill_active : R.drawable.pill_inactive);
                pill.setTextColor(requireContext().getColor(active ? R.color.on_primary : R.color.text_secondary));
            }
            View section = v.findViewById(e.getValue());
            if (section != null) section.setVisibility(active ? View.VISIBLE : View.GONE);
        }
    }

    // --- Finance sections: the CFO web widget board (net-cashflow, cash-inflow, cash-outflow,
    // budget, budget-variance, payroll, revenue-arr, risk-exposure), laid out as in the design ---

    /** Bar colours for the cost-centre breakdown, in order. */
    private static final int[] CATEGORY_BARS = {R.drawable.bar_fill_blue, R.drawable.bar_fill_purple,
            R.drawable.bar_fill_green, R.drawable.bar_fill_orange};

    private void renderFinancials(View root, FinancialsView fv, boolean fromMock) {
        LinearLayout dash = root.findViewById(R.id.fin_section_dashboard);
        LinearLayout pnl = root.findViewById(R.id.fin_section_pnl);
        LinearLayout cash = root.findViewById(R.id.fin_section_cashflow);
        LinearLayout exp = root.findViewById(R.id.fin_section_expenses);
        for (LinearLayout s : new LinearLayout[]{dash, pnl, cash, exp}) s.removeAllViews();

        TextView status = root.findViewById(R.id.fin_status);
        if (fv == null || fv.isEmpty()) {
            status.setText(fromMock ? R.string.data_unavailable : R.string.fin_no_data);
            status.setVisibility(View.VISIBLE);
            return;
        }
        status.setVisibility(View.GONE);
        String period = periodCaption(fv);
        FinancialsView.Amount net = fv.netCashflow;
        int netColor = net != null && net.value < 0 ? R.color.red : R.color.green;

        // Dashboard — hero net cash flow, cash in vs out, revenue & risk, IT budget consumed.
        if (net != null) {
            dash.addView(heroCard(getString(R.string.fin_net_cashflow),
                    fromMock ? null : liveBadge(), money(net), R.color.text, period, netColor));
        }
        if (fv.cashInflow != null || fv.cashOutflow != null) {
            String asOf = fv.latestMonth != null && fv.fiscalYear != null
                    ? getString(R.string.fin_as_of_fmt, fv.latestMonth, fv.fiscalYear) : fv.latestMonth;
            LinearLayout c = listCard(getString(R.string.fin_in_vs_out), asOf);
            double in = value(fv.cashInflow), out = value(fv.cashOutflow), total = in + out;
            barRow(c, getString(R.string.fin_cash_in), R.color.text_secondary, money(fv.cashInflow), R.color.green,
                    total > 0 ? in / total : -1, R.drawable.bar_fill_green);
            barRow(c, getString(R.string.fin_cash_out), R.color.text_secondary, money(fv.cashOutflow), R.color.orange,
                    total > 0 ? out / total : -1, R.drawable.bar_fill_orange);
            dash.addView(c);
        }
        if (fv.revenueArr != null || fv.riskExposure != null) {
            LinearLayout c = listCard(getString(R.string.fin_revenue_risk), null);
            capsRow(c, getString(R.string.fin_recurring_revenue), money(fv.revenueArr), R.color.green);
            if (fv.revenueArr != null && fv.riskExposure != null) divider(c);
            capsRow(c, getString(R.string.fin_risk_exposure), money(fv.riskExposure), R.color.red);
            dash.addView(c);
        }
        int consumed = fv.budgetConsumedPct();
        if (consumed >= 0) {
            dash.addView(summaryCard(getString(R.string.fin_it_budget), consumed + "%",
                    consumed > 100 ? R.color.red : consumed >= 90 ? R.color.orange : R.color.green));
        }
        FinancialsView.Overrun worst = null;
        for (FinancialsView.Overrun o : fv.overruns) {
            if (o.budget > 0 && o.spent > o.budget
                    && (worst == null || o.percentOfBudget() > worst.percentOfBudget())) worst = o;
        }
        if (worst != null) {
            dash.addView(alertCard(getString(R.string.fin_overrun_fmt, worst.name, worst.percentOfBudget(),
                    compact(worst.spent, worst.currency), compact(worst.budget, worst.currency))));
        }
        if (dash.getChildCount() == 0) dash.addView(emptyText());

        // P&L — net cash flow with its margin on receipts, then the breakdown.
        if (net != null) {
            double in = value(fv.cashInflow);
            pnl.addView(heroCard(getString(R.string.fin_net_cashflow),
                    in > 0 ? marginBadge(net.value * 100 / in) : null, money(net), R.color.text, period, null));
        }
        if (fv.cashInflow != null || fv.cashOutflow != null) {
            LinearLayout c = listCard(getString(R.string.fin_breakdown), null);
            lineRow(c, getString(R.string.fin_receipts), R.color.text_secondary, money(fv.cashInflow), R.color.text);
            lineRow(c, getString(R.string.fin_payments), R.color.text_secondary,
                    fv.cashOutflow != null ? "− " + money(fv.cashOutflow) : null, R.color.text);
            if (net != null) {
                divider(c);
                lineRow(c, getString(R.string.fin_net_cashflow), R.color.text, money(net), netColor);
            }
            pnl.addView(c);
        }
        if (fv.revenueArr != null || fv.payroll != null) {
            LinearLayout c = plainCard();
            lineRow(c, getString(R.string.fin_revenue_arr), R.color.text_secondary, money(fv.revenueArr), R.color.text);
            if (fv.revenueArr != null && fv.payroll != null) divider(c);
            lineRow(c, getString(R.string.fin_annual_payroll), R.color.text_secondary, money(fv.payroll), R.color.text);
            pnl.addView(c);
        }
        if (pnl.getChildCount() == 0) pnl.addView(emptyText());

        // Cash flow — signed net change, month by month, then total receipts / payments.
        if (net != null) {
            cash.addView(heroCard(getString(R.string.fin_cf_net_change), null, signed(net.value, net.currency),
                    netColor, period, null));
        }
        if (!fv.months.isEmpty()) {
            double max = 0;
            for (FinancialsView.Month m : fv.months) max = Math.max(max, Math.abs(m.nett));
            LinearLayout c = listCard(getString(R.string.fin_cf_by_month), null);
            for (FinancialsView.Month m : fv.months) {
                boolean down = m.nett < 0;
                barRow(c, m.label, R.color.text_secondary, signed(m.nett, m.currency),
                        down ? R.color.red : R.color.green, max > 0 ? Math.abs(m.nett) / max : -1,
                        down ? R.drawable.bar_fill_red : R.drawable.bar_fill_green);
            }
            cash.addView(c);
        }
        if (fv.cashInflow != null || fv.cashOutflow != null) {
            LinearLayout c = plainCard();
            lineRow(c, getString(R.string.fin_total_receipts), R.color.text_secondary, money(fv.cashInflow), R.color.text);
            if (fv.cashInflow != null && fv.cashOutflow != null) divider(c);
            lineRow(c, getString(R.string.fin_total_payments), R.color.text, money(fv.cashOutflow), R.color.orange);
            cash.addView(c);
        }
        if (cash.getChildCount() == 0) cash.addView(emptyText());

        // Expenses — total outflow, spend by cost centre, then payroll and budget variance.
        if (fv.cashOutflow != null) {
            exp.addView(heroCard(getString(R.string.fin_exp_total), null, money(fv.cashOutflow), R.color.text, period, null));
        }
        if (!fv.overruns.isEmpty()) {
            List<FinancialsView.Overrun> byspend = new ArrayList<>(fv.overruns);
            byspend.sort((a, b) -> Double.compare(b.spent, a.spent));
            double total = 0;
            for (FinancialsView.Overrun o : byspend) total += o.spent;
            LinearLayout c = listCard(getString(R.string.fin_by_cost_centre), null);
            int shown = Math.min(byspend.size(), CATEGORY_BARS.length);
            for (int i = 0; i < shown; i++) {
                FinancialsView.Overrun o = byspend.get(i);
                categoryRow(c, o.name, o.spent, o.currency, total, CATEGORY_BARS[i]);
            }
            if (byspend.size() > shown) {
                double rest = 0;
                for (int i = shown; i < byspend.size(); i++) rest += byspend.get(i).spent;
                categoryRow(c, getString(R.string.fin_other_fmt, byspend.size() - shown), rest,
                        byspend.get(shown).currency, total, R.drawable.bar_fill_red);
            }
            exp.addView(c);
        }
        if (fv.payroll != null || fv.budgetVariance != null) {
            LinearLayout c = plainCard();
            lineRow(c, getString(R.string.fin_annual_payroll), R.color.text_secondary, money(fv.payroll), R.color.text);
            if (fv.payroll != null && fv.budgetVariance != null) divider(c);
            lineRow(c, getString(R.string.fin_budget_variance), R.color.text_secondary, fv.budgetVariance, R.color.text);
            exp.addView(c);
        }
        if (exp.getChildCount() == 0) exp.addView(emptyText());
    }

    private String periodCaption(FinancialsView fv) {
        if (fv.fiscalYear != null && fv.latestMonth != null) {
            return getString(R.string.fin_ytd_fmt, fv.fiscalYear, fv.latestMonth);
        }
        return getString(R.string.fin_ytd);
    }

    // --- Money formatting: compact, as the design shows it ("₹1.42M", "₹600K") ---

    private static double value(FinancialsView.Amount a) {
        return a != null ? a.value : 0d;
    }

    private static String money(FinancialsView.Amount a) {
        return a != null ? compact(a.value, a.currency) : null;
    }

    /** "+ ₹0.86M" / "− ₹0.54M". */
    private static String signed(double v, String currency) {
        return (v < 0 ? "− " : "+ ") + compact(Math.abs(v), currency);
    }

    private static String compact(double v, String currency) {
        java.util.Locale loc = java.util.Locale.getDefault();
        double abs = Math.abs(v);
        String n = abs >= 1_000_000 ? String.format(loc, "%.2fM", abs / 1_000_000)
                : abs >= 1_000 ? String.format(loc, "%.0fK", abs / 1_000)
                : String.format(loc, "%.0f", abs);
        return (v < 0 ? "−" : "") + symbol(currency) + n;
    }

    private static String symbol(String currency) {
        if (currency == null || currency.isEmpty()) return "";
        try {
            return java.util.Currency.getInstance(currency).getSymbol(java.util.Locale.getDefault());
        } catch (IllegalArgumentException e) {
            return currency + " ";
        }
    }

    // --- Card building blocks ---

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        return lp;
    }

    private TextView text(CharSequence s, int colorRes, float sp, boolean bold) {
        TextView t = new TextView(requireContext());
        t.setText(s);
        t.setTextColor(requireContext().getColor(colorRes));
        t.setTextSize(sp);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout card() {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.card_bg);
        box.setPadding(dp(18), dp(18), dp(18), dp(18));
        box.setLayoutParams(cardParams());
        return box;
    }

    /** Label (+ optional badge), big value, caption, and an optional trend tile on the right. */
    private View heroCard(String label, @Nullable View badge, String value, int valueColor,
                          String caption, @Nullable Integer trendColor) {
        LinearLayout box = card();
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(android.view.Gravity.CENTER_VERTICAL);

        LinearLayout col = new LinearLayout(requireContext());
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout head = new LinearLayout(requireContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        head.addView(text(label, R.color.text_secondary, 14f, false));
        if (badge != null) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.leftMargin = dp(10);
            badge.setLayoutParams(blp);
            head.addView(badge);
        }
        col.addView(head);
        TextView v = text(value, valueColor, 32f, true);
        v.setPadding(0, dp(6), 0, dp(2));
        col.addView(v);
        if (caption != null) col.addView(text(caption, R.color.text_muted, 12f, false));
        box.addView(col);

        if (trendColor != null) {
            android.widget.FrameLayout tile = new android.widget.FrameLayout(requireContext());
            tile.setBackgroundResource(R.drawable.card_bg_2);
            tile.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
            android.widget.ImageView icon = new android.widget.ImageView(requireContext());
            icon.setImageResource(R.drawable.ic_trending);
            icon.setColorFilter(requireContext().getColor(trendColor));
            if (trendColor == R.color.red) icon.setScaleY(-1f);
            android.widget.FrameLayout.LayoutParams ilp = new android.widget.FrameLayout.LayoutParams(dp(24), dp(24));
            ilp.gravity = android.view.Gravity.CENTER;
            icon.setLayoutParams(ilp);
            tile.addView(icon);
            box.addView(tile);
        }
        return box;
    }

    private View liveBadge() {
        return text(getString(R.string.fin_live), R.color.green, 13f, false);
    }

    private View marginBadge(double pct) {
        TextView t = text(getString(R.string.fin_margin_fmt, pct), pct < 0 ? R.color.red : R.color.green, 12f, false);
        t.setLetterSpacing(0.04f);
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setColor(requireContext().getColor(pct < 0 ? R.color.red_deep : R.color.green_deep));
        t.setBackground(bg);
        return t;
    }

    /** A titled card; {@code right} is an optional link-coloured note (e.g. the period). */
    private LinearLayout listCard(String title, @Nullable String right) {
        LinearLayout box = card();
        LinearLayout head = new LinearLayout(requireContext());
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView t = text(title, R.color.text, 17f, true);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(t);
        if (right != null) head.addView(text(right, R.color.link, 13f, false));
        box.addView(head);
        return box;
    }

    /** An untitled card, for label / value pairs split by dividers. */
    private LinearLayout plainCard() {
        LinearLayout box = card();
        box.setPadding(dp(18), dp(6), dp(18), dp(18));
        return box;
    }

    /** One horizontal label / value line, skipped when the value is missing. */
    private LinearLayout line(String label, int labelColor, String value, int valueColor) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(14);
        row.setLayoutParams(rlp);
        TextView l = text(label, labelColor, 15f, false);
        l.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(l);
        row.addView(text(value, valueColor, 15f, true));
        return row;
    }

    private void lineRow(LinearLayout card, String label, int labelColor, String value, int valueColor) {
        if (value == null || value.isEmpty()) return;
        card.addView(line(label, labelColor, value, valueColor));
    }

    /** An upper-case, tracked label row (the design's "SEP 3 · OPS ACCOUNT" style). */
    private void capsRow(LinearLayout card, String label, String value, int valueColor) {
        if (value == null || value.isEmpty()) return;
        LinearLayout row = line(label.toUpperCase(java.util.Locale.getDefault()), R.color.text_secondary, value, valueColor);
        ((TextView) row.getChildAt(0)).setLetterSpacing(0.06f);
        ((TextView) row.getChildAt(0)).setTextSize(13f);
        card.addView(row);
    }

    /**
     * A label/value row with a proportion bar under it, skipped when the value is missing.
     * {@code share} in [0,1] sets the bar fill; a negative share draws no bar.
     */
    private void barRow(LinearLayout card, String label, int labelColor, String value, int valueColor,
                        double share, int barRes) {
        if (value == null || value.isEmpty()) return;
        card.addView(line(label, labelColor, value, valueColor));
        if (share < 0) return;
        LinearLayout track = new LinearLayout(requireContext());
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setBackgroundResource(R.drawable.bar_track);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(7));
        tlp.topMargin = dp(8);
        track.setLayoutParams(tlp);
        float filled = (float) Math.max(0d, Math.min(1d, share));
        View fill = new View(requireContext());
        fill.setBackgroundResource(barRes);
        fill.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, filled));
        View rest = new View(requireContext());
        rest.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f - filled));
        track.addView(fill);
        track.addView(rest);
        card.addView(track);
    }

    /** A cost-centre row: "₹1.02M · 50%" of the listed spend, with a coloured bar. */
    private void categoryRow(LinearLayout card, String name, double spent, String currency,
                             double total, int barRes) {
        double share = total > 0 ? spent / total : -1;
        String value = share >= 0
                ? getString(R.string.fin_share_fmt, compact(spent, currency), (int) Math.round(share * 100))
                : compact(spent, currency);
        barRow(card, name, R.color.text, value, R.color.text, share, barRes);
    }

    private void divider(LinearLayout card) {
        View d = new View(requireContext());
        d.setBackgroundColor(requireContext().getColor(R.color.border));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(14);
        d.setLayoutParams(lp);
        card.addView(d);
    }

    /** A single bold label / value line in its own card (the design's "Capital allocation"). */
    private View summaryCard(String label, String value, int valueColor) {
        LinearLayout box = card();
        LinearLayout row = line(label, R.color.text, value, valueColor);
        ((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin = 0;
        TextView l = (TextView) row.getChildAt(0);
        l.setTextSize(17f);
        l.setTypeface(l.getTypeface(), android.graphics.Typeface.BOLD);
        ((TextView) row.getChildAt(1)).setTextSize(17f);
        box.addView(row);
        return box;
    }

    private View alertCard(String body) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.ai_prompt_bg);
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        box.setLayoutParams(cardParams());
        TextView h = text(getString(R.string.fin_budget_alert), R.color.ai_purple, 11f, true);
        h.setLetterSpacing(0.05f);
        box.addView(h);
        TextView b = text(body, R.color.text, 14f, false);
        b.setPadding(0, dp(6), 0, 0);
        b.setLineSpacing(0f, 1.25f);
        box.addView(b);
        return box;
    }

    private View emptyText() {
        TextView t = text(getString(R.string.fin_no_data), R.color.text_muted, 13f, false);
        t.setGravity(android.view.Gravity.CENTER);
        t.setLayoutParams(cardParams());
        return t;
    }
}
