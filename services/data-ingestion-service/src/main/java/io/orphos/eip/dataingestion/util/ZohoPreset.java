package io.orphos.eip.dataingestion.util;

import io.orphos.eip.dataingestion.component.ErpTokenProvider;
import io.orphos.eip.dataingestion.component.RestMetadataDiscoverer;
import io.orphos.eip.dataingestion.vo.ErpConfig;
import java.util.ArrayList;
import java.util.List;

/**
 * Zoho-specific defaults layered on top of the generic {@link ErpConfig} model,
 * so a {@code ZOHO_ERP} source works without the admin hunting down a spec:
 * OAuth accounts/token URL per data center, Zoho CRM v2 pagination, and a starter
 * set of standard CRM module endpoints. Field lists are intentionally left empty —
 * {@code RestMetadataDiscoverer} live-samples each module to infer its columns,
 * because Zoho module fields are tenant-customisable.
 */
public final class ZohoPreset {

    private ZohoPreset() {}

    /** Standard CRM modules seeded when the source has no explicit object list. */
    private static final List<String> DEFAULT_MODULES = List.of(
            "Leads", "Accounts", "Contacts", "Deals", "Tasks",
            "Products", "Vendors", "Quotes", "Sales_Orders", "Purchase_Orders", "Invoices");

    /** Zoho REST API base for a data center, e.g. com → https://www.zohoapis.com */
    public static String apiBase(String dataCenter) {
        String dc = (dataCenter == null || dataCenter.isBlank()) ? "com" : dataCenter.trim();
        return "https://www.zohoapis." + dc;
    }

    /**
     * Return a normalized copy of {@code cfg} with Zoho defaults filled in wherever
     * the incoming config left a blank. Never overwrites values the admin supplied.
     */
    public static ErpConfig applyDefaults(ErpConfig cfg) {
        ErpConfig in = cfg == null ? ErpConfig.parse(null) : cfg;

        String dc = blank(in.dataCenter()) ? "com" : in.dataCenter().trim();
        String scope = blank(in.scope())
                ? "ZohoCRM.modules.ALL,ZohoCRM.settings.ALL"
                : in.scope();

        ErpConfig.Pagination pg = in.pagination();
        ErpConfig.Pagination pagination = new ErpConfig.Pagination(
                pg != null && !blank(pg.style()) ? pg.style() : "PAGE",
                pg != null && !blank(pg.pageParam()) ? pg.pageParam() : "page",
                pg != null && !blank(pg.sizeParam()) ? pg.sizeParam() : "per_page",
                pg != null && pg.pageSize() != null ? pg.pageSize() : 200,
                pg != null && pg.startPage() != null ? pg.startPage() : 1,
                pg != null && !blank(pg.moreFlagPath()) ? pg.moreFlagPath() : "info.more_records",
                pg != null ? pg.cursorParam() : null,
                pg != null ? pg.cursorPath() : null);

        List<ErpConfig.ErpObject> objects = new ArrayList<>(in.objectsOrEmpty());
        if (objects.isEmpty()) {
            for (String module : DEFAULT_MODULES) {
                objects.add(new ErpConfig.ErpObject(module, "/crm/v2/" + module, "GET", "data", List.of()));
            }
        }

        return new ErpConfig(
                ErpConfig.VENDOR_ZOHO,
                dc,
                in.tokenUrl(),                 // blank → ErpTokenProvider derives accounts.zoho.<dc>
                in.clientId(),
                scope,
                pagination,
                in.incrementalParam(),         // optional; Zoho incremental is header-based (future)
                objects);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
