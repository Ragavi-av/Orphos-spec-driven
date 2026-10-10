package io.orphos.eip.admin.service;

import io.orphos.eip.admin.component.OnboardingWidgetsClient;
import io.orphos.eip.admin.component.PersonaWidgetPolicy;
import io.orphos.eip.admin.vo.DashboardLayoutDtos.CatalogItem;
import io.orphos.eip.admin.vo.DashboardLayoutDtos.LayoutRequest;
import io.orphos.eip.admin.vo.DashboardLayoutDtos.LayoutView;
import io.orphos.eip.admin.repository.DashboardLayoutRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Resolves dashboard layouts with the precedence:
 *   1. User override (DashboardLayoutUser for current tenant + userId)
 *   2. Tenant default (DashboardLayoutDefault for current tenant)
 *   3. Persona default (derived from the tenant's persona vocabulary)
 *   4. System default (constants below)
 *
 * <p>Tenant scoping is handled inside the repository via TenantContextHolder.
 * The service whitelists widget ids against {@link #CATALOG} so a stale or
 * malicious client cannot push unknown ids into the store.</p>
 *
 * <h2>Relevance picks the tier; permission filters the result</h2>
 * The persona tier is only a DEFAULT — it decides what a CFO sees before anyone
 * customises anything, and a user is free to override it. Permission is different:
 * {@link PersonaWidgetPolicy#permitted} is applied to whichever tier won, so a user
 * override or a tenant default that names {@code payroll} still does not show it to
 * a CTO. Ordering it that way is the whole point — otherwise "customise your
 * dashboard" would double as "opt out of access control".
 *
 * <p>Writes are filtered too ({@link #sanitise}): a persona cannot persist a widget
 * it may not see, so a later role change cannot resurrect it from a saved layout.</p>
 *
 * <p>For tenants with no persona vocabulary every persona call is a no-op and
 * resolution behaves exactly as it did before — see {@code PersonaPolicy}.</p>
 */
@Service
public class DashboardLayoutService {

    // Server-side widget catalog — mirrors the client-side CATALOG. Kept here
    // so the SPA cannot push unknown ids and so admin tools can list the
    // canonical widget set without depending on the SPA bundle.
    public static final List<CatalogItem> CATALOG = List.of(
            new CatalogItem("uptime",            "System Uptime",          "Overall system availability vs SLA target", "#2563eb"),
            new CatalogItem("budget",            "IT Budget Consumed",     "Budget consumed vs plan this period",       "#d97706"),
            new CatalogItem("incidents",         "Active Incidents",       "Open and in-progress incidents",             "#dc2626"),
            new CatalogItem("rag-red",           "Projects at Risk",       "Projects flagged red in RAG status",         "#dc2626"),
            new CatalogItem("pending-decisions", "Pending Decisions",      "Decisions awaiting executive approval",      "#d97706"),
            new CatalogItem("alerts",            "Active Alerts",          "Critical and high-severity platform alerts", "#dc2626"),
            new CatalogItem("sla-health",        "SLA Health",             "SLA compliance ratio across all systems",    "#16a34a"),
            new CatalogItem("system-health",     "IT System Health",       "Degraded or critical IT systems count",      "#7c3aed"),
            new CatalogItem("budget-variance",   "Budget Variance",        "Cost centres running over or under budget",  "#0891b2"),
            // HR + Finance widgets (e.g. Zenitus tenant) — backed by /api/zenitus/*.
            new CatalogItem("headcount",         "Total Headcount",        "Active employees across the organisation",   "#0891b2"),
            new CatalogItem("headcount-dept",    "Headcount by Dept",      "Employee distribution across departments",   "#7c3aed"),
            new CatalogItem("payroll",           "Annual Payroll",         "Total annual CTC across all staff",          "#d97706"),
            new CatalogItem("net-cashflow",      "Net Cash Flow",          "Net cash position for the current fiscal year", "#16a34a"),
            new CatalogItem("cash-inflow",       "Cash Inflow",            "Total receipts for the current fiscal year", "#2563eb"),
            new CatalogItem("cash-outflow",      "Cash Outflow",           "Total payments for the current fiscal year", "#dc2626"),
            // Ontology-declared widgets. Values come from GovMetricObservation via
            // /api/dashboard/persona-metrics/{key} rather than a bespoke feed, which
            // is what let these five be added without new backend endpoints. They
            // close the domain gaps that left the board IT-weighted: before them
            // nothing rendered Commercial, Contracts, Risk, Talent or Objectives.
            new CatalogItem("pipeline-value",     "Pipeline Value",         "Weighted open pipeline for the current quarter", "#2563eb"),
            new CatalogItem("revenue-arr",        "Annual Recurring Revenue","Contracted ARR across active clients",          "#16a34a"),
            new CatalogItem("risk-exposure",      "Risk Exposure",          "Total residual financial exposure on the risk register", "#dc2626"),
            new CatalogItem("attrition",          "Attrition Rate",         "Rolling attrition rate across the organisation", "#d97706"),
            new CatalogItem("objective-progress", "Objective Progress",     "Key results at or ahead of target",              "#7c3aed")
    );

    private static final List<String> SYSTEM_DEFAULT_VISIBLE =
            List.of("uptime", "budget", "incidents", "rag-red");
    private static final List<String> SYSTEM_DEFAULT_ORDER =
            CATALOG.stream().map(CatalogItem::id).toList();

    private final DashboardLayoutRepository repo;
    private final OnboardingWidgetsClient onboardingWidgets;
    private final PersonaWidgetPolicy personaPolicy;

    public DashboardLayoutService(DashboardLayoutRepository repo, OnboardingWidgetsClient onboardingWidgets,
                                  PersonaWidgetPolicy personaPolicy) {
        this.repo = repo;
        this.onboardingWidgets = onboardingWidgets;
        this.personaPolicy = personaPolicy;
    }

    /** Widget ids allowed in a saved layout: the static catalog plus the current
     *  tenant's dynamic (onboarded) widgets, so data-driven widgets aren't stripped. */
    private Set<String> knownWidgetIds() {
        Set<String> ids = CATALOG.stream().map(CatalogItem::id).collect(Collectors.toCollection(LinkedHashSet::new));
        try {
            ids.addAll(onboardingWidgets.dynamicWidgetIds());
        } catch (Exception ignored) {
            // No tenant context or no model yet — static catalog is the floor.
        }
        return ids;
    }

    /**
     * The widget catalog — deliberately NOT persona-filtered.
     *
     * <p>Filtering it looks tempting (why offer a CTO the payroll widget?) but the
     * same endpoint feeds the admin "Dashboard Defaults" tab, and the Admin persona
     * holds no business domain, so it is permitted zero widgets. A filtered catalog
     * would therefore hand admins an empty curation picker and make the tenant
     * default uneditable. The board itself is filtered on read and on write, so an
     * over-generous picker is cosmetic, whereas an empty admin picker is a
     * functional break.</p>
     *
     * <p>If the picker should stop offering unavailable widgets, the fix is a
     * separate admin catalog endpoint, not filtering this one.</p>
     */
    public List<CatalogItem> catalog() {
        return CATALOG;
    }

    // ----- Reads -----

    public LayoutView resolveForUser(String userId) {
        Set<String> roles = personaPolicy.currentRoles();
        Optional<Map<String, Object>> user = repo.findUserLayout(userId);
        if (user.isPresent()) {
            return filtered(toView(user.get(), "user"), roles);
        }
        Optional<Map<String, Object>> tenant = repo.findTenantDefault();
        if (tenant.isPresent()) {
            return filtered(toView(tenant.get(), "tenant"), roles);
        }
        LayoutView persona = personaDefault(roles);
        if (persona != null) return persona;
        return filtered(systemDefault(), roles);
    }

    /**
     * The persona tier: a layout seeded from what this caller's persona watches.
     *
     * @return null when the tenant has no persona vocabulary, the caller has no
     *         persona, or the persona watches nothing (Admin, Viewer, Guest) — in
     *         which case resolution falls through to the system default rather than
     *         handing back an empty board
     */
    private LayoutView personaDefault(Set<String> roles) {
        List<String> visible = personaPolicy.defaultWidgetsFor(roles);
        if (visible.isEmpty()) return null;
        // Order: the persona's own widgets first (in catalog order so the board is
        // stable), then everything else it may see, so the picker still lists them.
        List<String> order = new ArrayList<>();
        for (CatalogItem c : CATALOG) {
            if (visible.contains(c.id())) order.add(c.id());
        }
        for (String id : personaPolicy.permitted(SYSTEM_DEFAULT_ORDER, roles)) {
            if (!order.contains(id)) order.add(id);
        }
        List<String> visibleInOrder = order.stream().filter(visible::contains).collect(Collectors.toList());
        return new LayoutView(visibleInOrder, order, "persona", Instant.EPOCH);
    }

    /** Applies permission to an already-resolved layout, preserving its source label. */
    private LayoutView filtered(LayoutView view, Set<String> roles) {
        List<String> visible = personaPolicy.permitted(view.visibleIds(), roles);
        List<String> order = personaPolicy.permitted(view.order(), roles);
        if (visible.size() == view.visibleIds().size() && order.size() == view.order().size()) {
            return view;
        }
        return new LayoutView(visible, order, view.source(), view.updatedAt());
    }

    public LayoutView getTenantDefault() {
        return repo.findTenantDefault()
                .map(r -> toView(r, "tenant"))
                .orElseGet(this::systemDefault);
    }

    // ----- Writes -----

    public LayoutView saveUserLayout(String userId, LayoutRequest req) {
        // Persona-filtered: this is the user's own board, and the payload comes from
        // a client we do not trust to have filtered it.
        Sanitised s = sanitise(req, true);
        repo.upsertUserLayout(userId, s.visibleIds, s.order);
        return resolveForUser(userId);
    }

    public LayoutView resetUserLayout(String userId) {
        repo.deleteUserLayout(userId);
        return resolveForUser(userId);
    }

    /**
     * NOT persona-filtered, on purpose. An admin curates this default on behalf of
     * every persona in the tenant, and the Admin persona itself is permitted zero
     * widgets — filtering here would silently save an empty default for everyone.
     * Each viewer's copy is filtered when it is read, which is where it belongs.
     */
    public LayoutView saveTenantDefault(LayoutRequest req, String actorKey) {
        Sanitised s = sanitise(req, false);
        repo.upsertTenantDefault(s.visibleIds, s.order, actorKey);
        return getTenantDefault();
    }

    public void deleteTenantDefault() {
        repo.deleteTenantDefault();
    }

    // ----- Helpers -----

    private LayoutView systemDefault() {
        return new LayoutView(SYSTEM_DEFAULT_VISIBLE, SYSTEM_DEFAULT_ORDER, "system", Instant.EPOCH);
    }

    /**
     * Drop unknown ids, dedupe, and ensure {@code visibleIds} is a subset of
     * {@code order} — and drop anything the saving persona may not see.
     *
     * <p>Filtering on WRITE as well as read matters: without it a CTO could persist
     * {@code payroll} into their own layout (the client controls the payload), and
     * although the read path would hide it today, the row would sit there waiting
     * for a future role change or a policy edit to surface it. Refusing to store it
     * keeps the persisted state consistent with the policy that was in force.</p>
     */
    private Sanitised sanitise(LayoutRequest req, boolean applyPersonaFilter) {
        Set<String> known = knownWidgetIds();
        if (applyPersonaFilter) {
            known = new LinkedHashSet<>(
                    personaPolicy.permitted(new ArrayList<>(known), personaPolicy.currentRoles()));
        }
        final Set<String> allowed = known;

        List<String> order = new ArrayList<>(new LinkedHashSet<>(
                req.order() == null ? List.<String>of() : req.order()
        )).stream().filter(allowed::contains).collect(Collectors.toCollection(ArrayList::new));

        // Backfill any catalog ids missing from the saved order so the picker
        // always has a deterministic position for every widget — but never backfill
        // one the saver may not see, or the filter above would be undone here.
        for (CatalogItem c : CATALOG) {
            if (!order.contains(c.id()) && allowed.contains(c.id())) {
                order.add(c.id());
            }
        }

        List<String> visibleIds = new ArrayList<>(new LinkedHashSet<>(
                req.visibleIds() == null ? List.<String>of() : req.visibleIds()
        )).stream().filter(allowed::contains).collect(Collectors.toCollection(ArrayList::new));

        return new Sanitised(visibleIds, order);
    }

    @SuppressWarnings("unchecked")
    private static LayoutView toView(Map<String, Object> row, String source) {
        List<String> visibleIds = (List<String>) row.getOrDefault("visibleIds", List.of());
        // DB column is `widgetOrder` (renamed away from `order` to dodge the
        // SQL reserved word). Falls back to legacy `order` for any rows that
        // may have been written before the rename.
        Object rawOrder = row.get("widgetOrder");
        if (rawOrder == null) rawOrder = row.get("order");
        List<String> order = rawOrder instanceof List ? (List<String>) rawOrder : List.of();
        Instant updatedAt = parseInstant(row.get("updatedAt"));
        return new LayoutView(
                visibleIds == null ? List.of() : visibleIds,
                order,
                source,
                updatedAt
        );
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return Instant.EPOCH;
        try { return Instant.parse(Objects.toString(o)); } catch (Exception e) { return Instant.EPOCH; }
    }

    private record Sanitised(List<String> visibleIds, List<String> order) {}
}
