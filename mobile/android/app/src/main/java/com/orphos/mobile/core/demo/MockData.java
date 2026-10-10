package com.orphos.mobile.core.demo;

import com.orphos.mobile.data.model.DailyDigest;
import com.orphos.mobile.data.model.DecisionAnnotation;
import com.orphos.mobile.data.model.DecisionItem;
import com.orphos.mobile.data.model.Financials;
import com.orphos.mobile.data.model.FinancialsView;
import com.orphos.mobile.data.model.HomeSummary;
import com.orphos.mobile.data.model.MailInsightDto;
import com.orphos.mobile.data.model.NlQuery;
import com.orphos.mobile.data.model.PredictedImpact;
import com.orphos.mobile.data.model.RoleDashboard;
import com.orphos.mobile.data.model.TeamIntelligence;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Built-in sample data shaped exactly like the real Orphos responses, used in demo mode
 * or as a graceful fallback when a backend is unreachable. Names are generic (roles, not
 * individuals) per the platform's PII policy.
 */
public final class MockData {

    private MockData() { }

    // No sample Scribbles captures: Recent Captures / Saved items list only what the user
    // actually saved (the BFF when connected, LocalCaptureStore in demo mode).

    public static List<DecisionItem> decisions() {
        List<DecisionItem> list = new ArrayList<>();
        // Critical — an approval and a prep item, matching the Tasks "Critical" tab.
        list.add(decision("dec-1001", "Customer Alpha", "PENDING", "CUSTOMER",
                "CRITICAL", 0.94, "LOW", "Contract #4928-B",
                "Renewal for Customer Alpha needs approval before end of day to avoid a service lapse. AI flagged the pricing terms as favourable versus the prior cycle."));
        list.add(decision("dec-1002", "Investor Meeting", "DETECTED", "INVESTOR",
                "CRITICAL", 0.90, "MEDIUM", "Gamma Ventures • Today 4:00 PM",
                "Term-sheet discussion scheduled. AI advises preparing the updated financial model and cap table before the meeting."));
        // High — an observation and an impact analysis, matching the Tasks "High" tab.
        list.add(decision("dec-1003", "Marketing Spend", "DETECTED", "MARKETING",
                "HIGH", 0.72, "MEDIUM", "Budget Alert: 92% Reached",
                "The current burn rate exceeds the monthly forecast. AI recommends reallocating $15K from the brand awareness campaign to the lead-gen campaign."));
        list.add(decision("dec-1004", "Finance Team Attrition", "DETECTED", "PEOPLE",
                "HIGH", 0.68, "MEDIUM", "2 resignations detected",
                "Potential risk to the Q3 financial reporting timeline. AI suggests initiating recruitment for Senior Accountants immediately."));
        // Additional approval-worded items so the Tasks "Approval Required" filter shows
        // variety across approval types (yes/no, go-ahead, approve/reject).
        list.add(decision("dec-1005", "Vendor Migration", "PENDING", "OPERATIONS",
                "HIGH", 0.81, "MEDIUM", "Data-centre vendor switch",
                "The infrastructure team needs your approval to go ahead with migrating to the new vendor before the renewal deadline."));
        list.add(decision("dec-1006", "PR Statement", "PENDING", "MARKETING",
                "CRITICAL", 0.77, "MEDIUM", "Press release awaiting sign-off",
                "Legal has cleared the draft. Do you approve publishing the press statement today? A yes/no response is needed before 2 PM."));
        list.add(decision("dec-1007", "Q3 Capex Budget", "PENDING", "FINANCE",
                "HIGH", 0.79, "MEDIUM", "Data-centre upgrade • $250K",
                "Finance requests your approval of the Q3 capital expenditure for the data-centre upgrade."));
        list.add(decision("dec-1008", "Enterprise Discount", "PENDING", "SALES",
                "MEDIUM", 0.70, "LOW", "Customer Delta • 12% extension",
                "Sales needs your go-ahead to extend the enterprise discount to Customer Delta before quarter-end."));
        list.add(decision("dec-1009", "Offsite Venue", "PENDING", "PEOPLE",
                "MEDIUM", 0.66, "LOW", "Leadership offsite • venue booking",
                "Do you approve booking the proposed venue for the leadership offsite? A yes/no is needed by Friday."));
        return list;
    }

    public static DecisionItem decision(String id) {
        for (DecisionItem d : decisions()) {
            if (d.id.equals(id)) return d;
        }
        return decisions().get(0);
    }

    public static DecisionAnnotation annotation(String id) {
        DecisionItem d = decision(id);
        DecisionAnnotation a = new DecisionAnnotation();
        a.executiveSummary = d.title + ": " + d.description
                + " AI assessment indicates " + d.confidencePercent() + "% confidence with "
                + (d.risk() == null ? "manageable" : d.risk().toLowerCase()) + " residual risk.";
        a.keyImpacts = Arrays.asList(
                "Primary impact area: " + d.decisionArea,
                "Overall risk assessed as " + (d.risk() == null ? "MEDIUM" : d.risk()),
                d.impactLabel());
        a.recommendations = Arrays.asList(
                "Proceed with the proposed action given the confidence level.",
                "Set a 30-day review checkpoint to confirm expected outcomes.",
                "Notify the affected function leads before execution.");
        a.relatedConsiderations = Arrays.asList(
                "Budget alignment for the current fiscal period.",
                "Downstream dependencies on in-flight initiatives.");
        a.suggestedConnections = Arrays.asList("Linked to the current quarterly plan.");
        DecisionAnnotation.Related r = new DecisionAnnotation.Related();
        r.id = "dec-0990"; r.title = "Prior vendor evaluation"; r.status = "COMPLETED"; r.decidedAt = "2026-06-14T09:00:00Z";
        a.relatedDecisions = new ArrayList<>();
        a.relatedDecisions.add(r);
        a.method = "TEMPLATE";
        a.audienceRole = "executive";
        a.generatedAt = "2026-08-29T08:00:00Z";
        a.cached = false;
        return a;
    }

    /**
     * Sample notifications for demo mode and the offline fallback. One of each
     * criticality, so the grouped view renders all three sections rather than
     * looking broken when only one level happens to be present.
     */
    public static List<MailInsightDto> notifications() {
        List<MailInsightDto> list = new ArrayList<>();
        list.add(notification("ntf-1", "Production API latency breach",
                "p95 latency crossed the 800 ms SLA for 20 minutes.", "ALERT", "CRITICAL"));
        list.add(notification("ntf-2", "Vendor contract expires in 14 days",
                "Renewal decision needed before the notice window closes.", "WARNING", "HIGH"));
        list.add(notification("ntf-3", "Quarterly board pack published",
                "Finance circulated the Q2 pack for review.", "INFORMATION", "LOW"));
        return list;
    }

    private static MailInsightDto notification(String id, String title, String summary,
                                               String criticality, String priority) {
        MailInsightDto d = new MailInsightDto();
        d.id = id;
        d.kind = "NOTIFICATION";
        d.title = title;
        d.summary = summary;
        d.criticality = criticality;
        d.priority = priority;
        d.source = "EMAIL";
        d.status = "OPEN";
        return d;
    }

    public static DailyDigest dailyDigest() {
        DailyDigest d = new DailyDigest();
        d.generatedAt = "2026-08-29T07:30:00Z";
        d.period = "FY2027 Q2 · WEEK 34";
        d.allClear = false;
        d.source = "fallback";
        d.headline = "Customer Alpha is awaiting approval; two investors replied and marketing spend is at a critical level.";
        d.items = new ArrayList<>();
        d.items.add(digestItem(1, "alert", "al-1", "Customer Alpha waiting for approval", "Contract renewal pending sign-off.", "critical"));
        d.items.add(digestItem(2, "decision", "dec-1002", "Marketing spend at critical level", "Budget utilisation exceeded threshold.", "warning"));
        d.items.add(digestItem(3, "incident", "inc-7", "Finance team attrition to review", "Attrition trending above target.", "warning"));
        d.totalCount = d.items.size();
        d.otherCount = 0;
        return d;
    }

    public static NlQuery.Response ask(String question) {
        NlQuery.Response r = new NlQuery.Response();
        r.question = question;
        r.provider = "demo";
        r.timestamp = "2026-08-29T08:15:00Z";
        r.answer = "Based on the current data, the highest-leverage decision today is approving the vendor "
                + "contract (96% AI confidence, low risk). Marketing spend needs attention — it is at a "
                + "critical level this quarter. This is sample guidance shown in demo mode.";
        r.followups = Arrays.asList(
                "Show me the vendor contract analysis",
                "What is driving the marketing overspend?",
                "Which decisions are overdue?");
        return r;
    }

    // --- Voice note ---

    /**
     * Demo mode has no Whisper, so every voice note "transcribes" to this sample. The review
     * screen says so; the user's own recording is still what plays back and is saved.
     */
    public static final String VOICE_NOTE_TRANSCRIPT =
            "Quick update after the operations review. The Customer Alpha renewal is on track, but "
            + "we need sign-off on the revised pricing before Friday. Marketing spend is running "
            + "ahead of plan this quarter, so let's pause the new campaign until finance confirms "
            + "the budget. We agreed to move the vendor onboarding to next sprint. Follow-ups: send "
            + "the renewal terms to legal, and book a budget check-in with finance.";

    /** The AI's draft for {@link #VOICE_NOTE_TRANSCRIPT}, shaped like the BFF's extract response. */
    public static com.orphos.mobile.data.model.Capture voiceNoteDraft() {
        com.orphos.mobile.data.model.Capture c = new com.orphos.mobile.data.model.Capture();
        c.title = "Operations review follow-ups";
        c.content = VOICE_NOTE_TRANSCRIPT;
        c.summary = "The Customer Alpha renewal is on track pending pricing sign-off by Friday. "
                + "Marketing is over plan, so the new campaign is paused until finance confirms budget.";
        c.keyPoints = Arrays.asList(
                "Customer Alpha renewal on track; revised pricing needs sign-off before Friday",
                "Marketing spend is ahead of plan this quarter",
                "Vendor onboarding moved to next sprint");
        c.actions = Arrays.asList(
                "Send the renewal terms to legal",
                "Book a budget check-in with finance");
        c.decisions = Arrays.asList(
                "Pause the new marketing campaign until budget is confirmed",
                "Move vendor onboarding to next sprint");
        return c;
    }

    // --- Home dashboard ---

    public static HomeSummary homeSummary() {
        HomeSummary h = new HomeSummary();
        h.tasksDone = 0;
        h.tasksTotal = 4;
        h.criticalCount = 2;
        h.availability = "Busy";
        h.summary = Arrays.asList(
                new HomeSummary.Point("Customer Alpha waiting for approval", "CRITICAL"),
                new HomeSummary.Point("2 investors replied — Gamma would like to handshake and Syntax likes to negotiate", "CRITICAL"),
                new HomeSummary.Point("Marketing spend on budget critical level", "WARNING"),
                new HomeSummary.Point("To review attrition in Finance team", "WARNING"));
        return h;
    }

    // --- Business / Team Intelligence ---

    public static TeamIntelligence teamIntelligence() {
        TeamIntelligence t = new TeamIntelligence();
        t.period = "This week";
        t.productivity = Arrays.asList(
                new TeamIntelligence.Productivity("Marketing", 95),
                new TeamIntelligence.Productivity("Engineering", 92),
                new TeamIntelligence.Productivity("Sales", 88),
                new TeamIntelligence.Productivity("Support", 60));
        t.availability = Arrays.asList(
                new TeamIntelligence.Availability("Onsite", 42),
                new TeamIntelligence.Availability("PTO", 6),
                new TeamIntelligence.Availability("Illness", 3),
                new TeamIntelligence.Availability("Sabbatical", 1),
                new TeamIntelligence.Availability("Casual leave", 4),
                new TeamIntelligence.Availability("Others", 2));
        t.burnout = new ArrayList<>();
        t.burnout.add(new TeamIntelligence.Burnout(
                "A senior engineer (Engineering)",
                "Working 3 months continuously",
                "Possible burnout",
                "Appreciate their effort and encourage them to take time off."));
        return t;
    }

    // --- Financials ---

    public static Financials financials(String currency) {
        Financials f = new Financials();
        f.period = "FY2027 Q2";
        f.currency = currency;
        f.creditScore = 780;
        f.metrics = Arrays.asList(
                new Financials.Metric("cashFlow", "Cash flow", 1_250_000, "+8% QoQ", true),
                new Financials.Metric("income", "Income", 3_400_000, "+12% QoQ", true),
                new Financials.Metric("expenses", "Expenses", 2_150_000, "+5% QoQ", false),
                new Financials.Metric("pnl", "Profit & Loss", 1_250_000, "+18% QoQ", true),
                new Financials.Metric("forecast", "Next-quarter forecast", 3_800_000, "+11%", true),
                new Financials.Metric("capital", "Capital allocated", 2_600_000, "of 3.0M budget", true));
        return f;
    }

    /** Demo mode only: sample CFO widget-board figures (cashflow, budget lines, persona metrics). */
    public static FinancialsView financialsView() {
        String cur = "INR";
        FinancialsView v = new FinancialsView();
        v.fiscalYear = "2027";
        v.latestMonth = "Aug";
        v.cashInflow = new FinancialsView.Amount(5_800_000, cur);
        v.cashOutflow = new FinancialsView.Amount(4_940_000, cur);
        v.netCashflow = new FinancialsView.Amount(860_000, cur);
        v.revenueArr = new FinancialsView.Amount(7_200_000, cur);
        v.payroll = new FinancialsView.Amount(4_080_000, cur);
        v.riskExposure = new FinancialsView.Amount(1_100_000, cur);
        v.budgetVariance = "4";
        String[] names = {"Apr", "May", "Jun", "Jul", "Aug"};
        double[][] flows = {{1_100_000, 980_000}, {1_150_000, 1_020_000}, {1_050_000, 1_090_000},
                {1_200_000, 950_000}, {1_300_000, 900_000}};
        for (int i = 0; i < names.length; i++) {
            FinancialsView.Month m = new FinancialsView.Month();
            m.label = names[i] + " 2027";
            m.inflow = flows[i][0];
            m.outflow = flows[i][1];
            m.nett = m.inflow - m.outflow;
            m.currency = cur;
            v.months.add(m);
        }
        Object[][] centres = {{"Salaries & benefits", 1_020_000d, 950_000d}, {"Marketing", 410_000d, 360_000d},
                {"Infrastructure", 360_000d, 300_000d}, {"Operations", 240_000d, 220_000d}};
        for (Object[] c : centres) {
            FinancialsView.Overrun o = new FinancialsView.Overrun();
            o.name = (String) c[0];
            o.spent = (Double) c[1];
            o.budget = (Double) c[2];
            o.currency = cur;
            v.overruns.add(o);
        }
        return v;
    }

    // --- People dashboard (CHRO "People" tab) ---

    public static RoleDashboard peopleDashboard() {
        RoleDashboard.Tone N = RoleDashboard.Tone.NEUTRAL, M = RoleDashboard.Tone.MUTED,
                G = RoleDashboard.Tone.GOOD, A = RoleDashboard.Tone.WARN, R = RoleDashboard.Tone.BAD,
                B = RoleDashboard.Tone.INFO, P = RoleDashboard.Tone.AI;
        RoleDashboard d = new RoleDashboard();
        d.title = "People";
        d.searchHint = "Search employee ID, team, role…";

        // Dashboard
        RoleDashboard.Block headcount = RoleDashboard.Block.hero("Headcount",
                RoleDashboard.Element.bigValue("142", "+6 this month", G, 32f),
                RoleDashboard.Element.tiles(3, new RoleDashboard.Stat("Full-time", "128", N),
                        new RoleDashboard.Stat("Contract", "14", N), new RoleDashboard.Stat("Locations", "3", N)),
                RoleDashboard.Element.caption("Updated 9:02 AM today · 4 joining, 1 leaving this week"));
        headcount.live = true;
        d.tabs.add(new RoleDashboard.Tab("Dashboard")
                .add(headcount)
                .add(RoleDashboard.Block.card("Attrition", "Last 12 months", B,
                        RoleDashboard.Element.bigValue("11.2%", "+2.1 pts vs FY25", A, 26f),
                        RoleDashboard.Element.segments(Arrays.asList(
                                new RoleDashboard.Segment(72, A), new RoleDashboard.Segment(28, M)),
                                "Voluntary 8.1%", "Involuntary 3.1%"),
                        RoleDashboard.Element.rowDiv("Regrettable exits", "5 of 16", R, true),
                        RoleDashboard.Element.row("Retention rate", "88.8%", G, true)))
                .add(RoleDashboard.Block.card("HR budget", "FY26 · Q3", B,
                        RoleDashboard.Element.row("Spent of ₹14.2Cr", "₹9.6Cr", N, true),
                        RoleDashboard.Element.progress(68, B, -1, "68% utilised", "Plan to date 64%", null),
                        RoleDashboard.Element.tiles(2, new RoleDashboard.Stat("Hiring cost", "₹1.1Cr", N),
                                new RoleDashboard.Stat("Variance", "+₹0.57Cr", A))))
                .add(RoleDashboard.Block.card("Benefits", null, null,
                        RoleDashboard.Element.meter("Health insurance enrolled", "139 / 142", N, 98, G),
                        RoleDashboard.Element.meter("Wellness allowance used", "₹6.2L of ₹10L", N, 62, G),
                        RoleDashboard.Element.meter("Tax-saving (NPS, meal, LTA)", "87 opted in", N, 61, G),
                        RoleDashboard.Element.meter("Learning stipend used", "₹3.1L of ₹7L", N, 44, G),
                        RoleDashboard.Element.rowDiv("Statutory (PF, ESI, Gratuity)", "Filed · Sep", G, false)))
                .add(RoleDashboard.Block.card("Upcoming", null, null,
                        RoleDashboard.Element.eventRow("OCT 1 · NEW JOINERS", "4 people", G, null, false),
                        RoleDashboard.Element.eventRow("OCT 5 · PAYROLL RUN", "₹9.8M", N, "Links to CFO", true),
                        RoleDashboard.Element.eventRow("OCT 15 · Q3 REVIEW CYCLE", "38 pending", A, null, true)))
                .add(RoleDashboard.Block.alert(RoleDashboard.Block.AlertStyle.AI, "AI ALERT",
                        "Engineering attrition is 2× last quarter. 3 people are showing flight-risk signals: "
                                + "no promotion in 2+ years and falling engagement scores.")));

        // Talent
        d.tabs.add(new RoleDashboard.Tab("Talent")
                .add(RoleDashboard.Block.statCards(2,
                        new RoleDashboard.Stat("Headcount", "142", N, "+6 this month", G),
                        new RoleDashboard.Stat("Available today", "131", N, "11 on leave", M),
                        new RoleDashboard.Stat("Bench / unallocated", "9", A, "6.3% of workforce", M)))
                .add(RoleDashboard.Block.card("Capacity by department", "Allocated", M,
                        RoleDashboard.Element.meter("Engineering · 58", "94%", A, 94, A),
                        RoleDashboard.Element.meter("Sales · 26", "88%", G, 88, G),
                        RoleDashboard.Element.meter("Operations · 22", "81%", G, 81, G),
                        RoleDashboard.Element.meter("Customer Success · 20", "76%", B, 76, B),
                        RoleDashboard.Element.meter("G&A · 16", "70%", B, 70, B)))
                .add(RoleDashboard.Block.card("Location", null, null,
                        RoleDashboard.Element.segments(Arrays.asList(new RoleDashboard.Segment(52, B),
                                new RoleDashboard.Segment(31, G), new RoleDashboard.Segment(17, P)),
                                "Bengaluru 74", "Pune 44", "Remote 24")))
                .add(RoleDashboard.Block.card("Diversity & inclusion", null, null,
                        RoleDashboard.Element.tiles(3, new RoleDashboard.Stat("Women", "38%", N),
                                new RoleDashboard.Stat("Women in leads", "29%", N),
                                new RoleDashboard.Stat("Non-binary", "2%", N),
                                new RoleDashboard.Stat("Differently abled", "3%", N),
                                new RoleDashboard.Stat("Inclusion score", "7.8", N))))
                .add(RoleDashboard.Block.card("Skills coverage", "vs. required", M,
                        RoleDashboard.Element.pillRow("Cloud / DevOps", "Gap · 3", R, false),
                        RoleDashboard.Element.pillRow("Data & analytics", "Gap · 2", A, true),
                        RoleDashboard.Element.pillRow("Enterprise sales", "Covered", G, true),
                        RoleDashboard.Element.pillRow("Frontend", "Covered", G, true),
                        RoleDashboard.Element.rowDiv("Revenue per employee", "₹38.4L", N, true))));

        // Attrition & Retention
        d.tabs.add(new RoleDashboard.Tab("Attrition & Retention")
                .add(RoleDashboard.Block.card("Overall attrition · 12 mo", "+2.1 pts", A,
                        RoleDashboard.Element.bigValue("11.2%", null, null, 30f),
                        RoleDashboard.Element.columns(A, new RoleDashboard.Bar("Q2 25", 30),
                                new RoleDashboard.Bar("Q3 25", 36), new RoleDashboard.Bar("Q4 25", 34),
                                new RoleDashboard.Bar("Q1 26", 40), new RoleDashboard.Bar("Q2 26", 48),
                                new RoleDashboard.Bar("Q3 26", 62))))
                .add(RoleDashboard.Block.statCards(2,
                        new RoleDashboard.Stat("Voluntary", "8.1%", N, "12 exits", M),
                        new RoleDashboard.Stat("Involuntary", "3.1%", N, "4 exits", M),
                        new RoleDashboard.Stat("Retention rate", "88.8%", G, "Target 90%", M),
                        new RoleDashboard.Stat("Regrettable", "5", R, "31% of exits", M)))
                .add(RoleDashboard.Block.card("Reasons for leaving", null, null,
                        RoleDashboard.Element.meter("Compensation", "6", N, 38, A),
                        RoleDashboard.Element.meter("Career growth", "4", N, 25, A),
                        RoleDashboard.Element.meter("Manager / team", "3", N, 19, A),
                        RoleDashboard.Element.meter("Relocation", "2", N, 12, A),
                        RoleDashboard.Element.meter("Performance", "1", N, 6, A)))
                .add(RoleDashboard.Block.card("By department", null, null,
                        RoleDashboard.Element.row("Engineering", "15.5%", R, true),
                        RoleDashboard.Element.rowDiv("Sales", "11.5%", A, true),
                        RoleDashboard.Element.rowDiv("Customer Success", "10.0%", A, true),
                        RoleDashboard.Element.rowDiv("Operations", "4.5%", G, true),
                        RoleDashboard.Element.rowDiv("G&A", "6.3%", G, true)))
                .add(RoleDashboard.Block.card("Critical talent loss", "3 at risk", R,
                        RoleDashboard.Element.row("Key roles lost · 12 mo", "3", N, true),
                        RoleDashboard.Element.row("Est. replacement cost", "₹42L", N, true),
                        RoleDashboard.Element.row("Avg. time to backfill", "54 days", N, true))));

        // HR Finance
        d.tabs.add(new RoleDashboard.Tab("HR Finance")
                .add(RoleDashboard.Block.hero("HR spend · FY26 to date",
                        RoleDashboard.Element.bigValue("₹9.6Cr", "of ₹14.2Cr", M, 30f),
                        RoleDashboard.Element.progress(68, B, 64, "Actual 68%", "Plan 64% · ", "₹0.57Cr over")))
                .add(RoleDashboard.Block.card("Budget vs. actual", "₹ lakh", M,
                        RoleDashboard.Element.tableRow(true, M, "Line item", "Plan", "Actual"),
                        RoleDashboard.Element.tableRow(false, A, "Payroll", "720", "738"),
                        RoleDashboard.Element.tableRow(false, G, "Employee benefits", "96", "91"),
                        RoleDashboard.Element.tableRow(false, G, "Wellness", "14", "9"),
                        RoleDashboard.Element.tableRow(false, R, "Hiring & agencies", "98", "112"),
                        RoleDashboard.Element.tableRow(false, G, "Training", "22", "17"),
                        RoleDashboard.Element.tableRow(false, G, "New office setup", "52", "48")))
                .add(RoleDashboard.Block.card("Payroll & statutory", null, null,
                        RoleDashboard.Element.row("Sep payroll", "₹9.8M", N, true),
                        RoleDashboard.Element.rowDiv("PF · ESI · PT", "Paid · Sep 15", G, false),
                        RoleDashboard.Element.rowDiv("TDS deposit", "Due Oct 7", A, false),
                        RoleDashboard.Element.rowDiv("Gratuity provision", "₹18.2L", N, true)))
                .add(RoleDashboard.Block.card("New office · Pune", "In progress", B,
                        RoleDashboard.Element.progress(61, B, -1, "₹48L spent of ₹79L", "Fit-out, deposits, IT", null)))
                .add(RoleDashboard.Block.alert(RoleDashboard.Block.AlertStyle.DANGER, "LEAKAGE · ₹3.4L",
                        "2 exited employees still on payroll, 5 unused software seats, and 3 duplicate "
                                + "reimbursement claims.")));
        d.tabs.get(3).blocks.get(3).trailingPill = true; // "In progress" status pill

        // Quick actions
        RoleDashboard.Action roles = new RoleDashboard.Action("person_add", "Open Roles",
                "11 open · 9 of 14 offers accepted", "Create new role");
        roles.stats.addAll(Arrays.asList(new RoleDashboard.Stat("Open", "11", N),
                new RoleDashboard.Stat("In final round", "4", G), new RoleDashboard.Stat("Avg. days open", "38", A)));
        roles.rows.addAll(Arrays.asList(
                new RoleDashboard.ActionRow("Senior Backend Engineer", "Engineering · Bengaluru · 3 openings", "52 days", R),
                new RoleDashboard.ActionRow("DevOps Engineer", "Engineering · Remote", "Final round", G),
                new RoleDashboard.ActionRow("Account Executive", "Sales · Pune · 2 openings", "Screening", B),
                new RoleDashboard.ActionRow("Data Analyst", "Operations · Bengaluru", "Offer sent", G),
                new RoleDashboard.ActionRow("CS Manager", "Customer Success · Pune", "41 days", A)));
        d.actions.add(roles);

        RoleDashboard.Action reviews = new RoleDashboard.Action("star", "Performance Reviews",
                "Q3 cycle closes Oct 15", "Send reminders");
        reviews.stats.addAll(Arrays.asList(new RoleDashboard.Stat("Completed", "104", G),
                new RoleDashboard.Stat("Pending", "38", A), new RoleDashboard.Stat("Avg. rating", "3.7", N)));
        reviews.rows.addAll(Arrays.asList(
                new RoleDashboard.ActionRow("Engineering", "58 employees · 6 managers", "19 pending", A),
                new RoleDashboard.ActionRow("Sales", "26 employees · 3 managers", "8 pending", A),
                new RoleDashboard.ActionRow("Operations", "22 employees · 2 managers", "Done", G),
                new RoleDashboard.ActionRow("Customer Success", "20 employees · 2 managers", "11 pending", A),
                new RoleDashboard.ActionRow("G&A", "16 employees · 2 managers", "Done", G)));
        d.actions.add(reviews);

        RoleDashboard.Action leave = new RoleDashboard.Action("event", "Leave & Attendance",
                "Today · Sep 28", "Review requests");
        leave.stats.addAll(Arrays.asList(new RoleDashboard.Stat("Present", "131", G),
                new RoleDashboard.Stat("On leave", "11", N), new RoleDashboard.Stat("Requests", "6", A)));
        leave.rows.addAll(Arrays.asList(
                new RoleDashboard.ActionRow("Backend engineer · Engineering", "Casual leave · Oct 2–3", "Pending", A),
                new RoleDashboard.ActionRow("Account executive · Sales", "Sick leave · Today", "Approved", G),
                new RoleDashboard.ActionRow("Data analyst · Operations", "Earned leave · Oct 7–11", "Pending", A),
                new RoleDashboard.ActionRow("QA engineer · Engineering", "Work from home · Sep 30", "Approved", G),
                new RoleDashboard.ActionRow("CS manager · Customer Success", "Earned leave · Oct 14–18", "Pending", A)));
        d.actions.add(leave);

        RoleDashboard.Action compliance = new RoleDashboard.Action("verified_user", "HR Compliance",
                "2 items need attention", "View all filings");
        compliance.stats.addAll(Arrays.asList(new RoleDashboard.Stat("On track", "9", G),
                new RoleDashboard.Stat("Due soon", "2", A), new RoleDashboard.Stat("Overdue", "0", N)));
        compliance.rows.addAll(Arrays.asList(
                new RoleDashboard.ActionRow("PF & ESI returns", "September · filed Sep 15", "Filed", G),
                new RoleDashboard.ActionRow("TDS deposit", "September · due Oct 7", "Due soon", A),
                new RoleDashboard.ActionRow("POSH annual report", "FY26 · due Oct 31", "Due soon", A),
                new RoleDashboard.ActionRow("Shops & Establishment", "Pune office registration", "In progress", B),
                new RoleDashboard.ActionRow("Background checks", "New joiners Oct 1", "Cleared", G)));
        d.actions.add(compliance);

        RoleDashboard.Action settings = new RoleDashboard.Action("settings", "HR Settings",
                "Policies, approvals and access", "Save changes");
        settings.stats.addAll(Arrays.asList(new RoleDashboard.Stat("Policies", "14", N),
                new RoleDashboard.Stat("Admins", "3", N), new RoleDashboard.Stat("Workflows", "6", N)));
        settings.rows.addAll(Arrays.asList(
                new RoleDashboard.ActionRow("Leave policy", "Updated Apr 2026", "Active", G),
                new RoleDashboard.ActionRow("Approval chains", "Leave, expenses, offers", "Active", G),
                new RoleDashboard.ActionRow("Payroll cut-off", "25th of each month", "Active", G),
                new RoleDashboard.ActionRow("Benefits catalogue", "4 plans · FY26", "Review", A),
                new RoleDashboard.ActionRow("Access & roles", "3 admins · 11 managers", "Active", G)));
        d.actions.add(settings);
        return d;
    }

    // --- builders ---

    private static DecisionItem decision(String id, String title, String status, String area,
                                         String priority, double confidence, String risk,
                                         String impact, String description) {
        DecisionItem d = new DecisionItem();
        d.id = id;
        d.title = title;
        d.status = status;
        d.decisionArea = area;
        d.domain = area;
        d.priorityLevel = priority;
        d.confidenceScore = confidence;
        d.strategicImportance = impact;
        d.impactSummary = impact;
        d.description = description;
        d.rationale = description;
        d.implementationStatus = "NOT_STARTED";
        d.createdAt = "2026-08-28T09:00:00Z";
        PredictedImpact pi = new PredictedImpact();
        pi.overallRisk = risk;
        pi.confidence = confidence;
        pi.method = "HEURISTIC";
        pi.summary = impact;
        d.predictedImpact = pi;
        return d;
    }

    private static DailyDigest.DigestItem digestItem(int ref, String kind, String id, String title,
                                                     String detail, String severity) {
        DailyDigest.DigestItem it = new DailyDigest.DigestItem();
        it.ref = ref; it.kind = kind; it.id = id; it.title = title; it.detail = detail; it.severity = severity;
        return it;
    }
}
