package io.orphos.eip.ingest.service;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeConnectionResolver;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.ingest.vo.EnterpriseDtos.*;
import io.orphos.eip.ingest.repository.EnterpriseGraphRepository;
import io.orphos.eip.ingest.service.TenantInfoCache;
import io.orphos.eip.ingest.vo.TenantRoutingInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Populates the Enterprise ontology graph for the current tenant from a single
 * submitted profile. Resolves ArcadeDB routing exactly like
 * {@code JiraIngestionService} (SHARED tenants share {@code orphos}; DEDICATED
 * tenants get their own database), then upserts every vertex first and links
 * every edge second, so a relationship never references a not-yet-written node.
 *
 * <p>Must run with a tenant bound via {@link TenantContextHolder} — the admin
 * controller runs inside the request's tenant context.</p>
 */
@Service
public class EnterpriseGraphService {

    private static final Logger log = LogManager.getLogger(EnterpriseGraphService.class);
    public static final String SOURCE = "ADMIN";

    private final EnterpriseGraphRepository graph;
    private final TenantInfoCache tenantInfo;
    private final ArcadeConnectionResolver resolver;

    public EnterpriseGraphService(EnterpriseGraphRepository graph, TenantInfoCache tenantInfo,
                                  ArcadeConnectionResolver resolver) {
        this.graph = graph;
        this.tenantInfo = tenantInfo;
        this.resolver = resolver;
    }

    /** Upserts the whole profile for the tenant bound to the current thread. */
    public UpsertEnterpriseResponse upsert(UpsertEnterpriseRequest req) {
        UUID tenantId = TenantContextHolder.requireTenantId();

        TenantRoutingInfo routing = tenantInfo.find(tenantId)
                .filter(TenantRoutingInfo::active)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot resolve routing (or tenant inactive) for " + tenantId + " — refusing to write"));
        String db = routing.dataset();
        ArcadeDbTemplate arcade = resolver.templateFor(routing.arcadeConnectionId());

        Ctx c = new Ctx(arcade, db, tenantId);

        // ── Pass 1: vertices ─────────────────────────────────────────────────
        String companyId = req.externalId();
        CompanyDto co = req.company();
        c.vertex("EntCompany", companyId, map(
                "name", co.name(), "legalName", co.legalName(), "tradeName", co.tradeName(),
                "foundedYear", co.foundedYear(), "registrationNumber", co.registrationNumber(),
                "website", co.website(), "companyType", co.companyType(), "stockSymbol", co.stockSymbol(),
                "employeeCount", co.employeeCount(), "annualRevenue", co.annualRevenue(),
                "currency", co.currency(), "description", co.description()));

        String missionId = null, visionId = null;
        if (notBlank(req.mission())) {
            missionId = companyId + "::mission";
            c.vertex("EntMission", missionId, map("name", "Mission", "statement", req.mission()));
        }
        if (notBlank(req.vision())) {
            visionId = companyId + "::vision";
            c.vertex("EntVision", visionId, map("name", "Vision", "statement", req.vision()));
        }
        for (ValueDto v : safe(req.values())) {
            String id = notBlank(v.externalId()) ? v.externalId() : companyId + "::value::" + slug(v.name());
            c.vertex("EntCoreValue", id, map("name", v.name(), "statement", v.statement()));
            c.values.add(id);
        }
        for (FunctionalAreaDto a : safe(req.functionalAreas())) {
            c.vertex("EntFunctionalArea", a.externalId(), map("name", a.name(), "description", a.description()));
            c.areas.add(a.externalId());
        }
        for (IndustryDto i : safe(req.industries())) {
            c.vertex("EntIndustry", i.externalId(),
                    map("name", i.name(), "sectorCode", i.sectorCode(), "description", i.description()));
            c.industries.add(i.externalId());
        }
        for (LocationDto l : safe(req.locations())) {
            c.vertex("EntLocation", l.externalId(), map(
                    "name", l.name(), "addressLine", l.addressLine(), "city", l.city(),
                    "stateProvince", l.stateProvince(), "country", l.country(), "postalCode", l.postalCode(),
                    "latitude", l.latitude(), "longitude", l.longitude(), "timezone", l.timezone()));
            c.locations.add(l.externalId());
        }
        for (BranchDto b : safe(req.branches())) {
            c.vertex("EntBranch", b.externalId(), map(
                    "name", b.name(), "branchType", b.branchType(),
                    "openedDate", b.openedDate(), "description", b.description()));
            c.branches.add(b.externalId());
        }
        for (OrgUnitDto u : safe(req.orgUnits())) {
            c.vertex("EntOrgUnit", u.externalId(), map(
                    "name", u.name(), "unitType", u.unitType(), "unitCode", u.unitCode(),
                    "description", u.description()));
            c.orgUnits.add(u.externalId());
        }
        for (ExecutiveDto e : safe(req.executives())) {
            c.vertex("EntExecutive", e.externalId(), map(
                    "displayName", e.displayName(), "jobTitle", e.jobTitle(), "appointedDate", e.appointedDate()));
            c.executives.add(e.externalId());
        }
        for (ClientDto cl : safe(req.clients())) {
            c.vertex("EntClient", cl.externalId(), map(
                    "name", cl.name(), "clientTier", cl.clientTier(), "clientSince", cl.clientSince(),
                    "relationshipStatus", cl.relationshipStatus(), "description", cl.description()));
            c.clients.add(cl.externalId());
        }
        for (EngagementDto en : safe(req.engagements())) {
            c.vertex("EntEngagement", en.externalId(), map(
                    "name", en.name(), "startDate", en.startDate(), "endDate", en.endDate(),
                    "engagementStatus", en.engagementStatus(), "contractValue", en.contractValue(),
                    "currency", en.currency(), "description", en.description()));
            c.engagements.add(en.externalId());
        }

        // ── Pass 2: edges ─────────────────────────────────────────────────────
        if (missionId != null) c.edge("HAS_MISSION", "EntCompany", companyId, "EntMission", missionId, null);
        if (visionId != null) c.edge("HAS_VISION", "EntCompany", companyId, "EntVision", visionId, null);
        for (String vid : c.values) c.edge("UPHOLDS_VALUE", "EntCompany", companyId, "EntCoreValue", vid, null);
        for (String aid : c.areas) c.edge("OPERATES_IN_AREA", "EntCompany", companyId, "EntFunctionalArea", aid, null);
        for (String iid : c.industries) c.edge("SERVES_INDUSTRY", "EntCompany", companyId, "EntIndustry", iid, null);

        if (notBlank(co.headquartersLocationExternalId())) {
            c.refEdge("HEADQUARTERED_AT", "EntCompany", companyId, "EntLocation",
                    co.headquartersLocationExternalId(), c.locations, "company.headquartersLocationExternalId");
        }
        for (BranchDto b : safe(req.branches())) {
            c.edge("HAS_BRANCH", "EntCompany", companyId, "EntBranch", b.externalId(), null);
            c.refEdge("LOCATED_AT", "EntBranch", b.externalId(), "EntLocation",
                    b.locationExternalId(), c.locations, "branch " + b.externalId() + " locationExternalId");
        }
        for (OrgUnitDto u : safe(req.orgUnits())) {
            c.edge("HAS_ORG_UNIT", "EntCompany", companyId, "EntOrgUnit", u.externalId(), null);
            c.refEdge("SUB_UNIT_OF", "EntOrgUnit", u.externalId(), "EntOrgUnit",
                    u.parentExternalId(), c.orgUnits, "orgUnit " + u.externalId() + " parentExternalId");
            c.refEdge("HEADED_BY", "EntOrgUnit", u.externalId(), "EntExecutive",
                    u.headExecutiveExternalId(), c.executives, "orgUnit " + u.externalId() + " headExecutiveExternalId");
            for (String areaId : safe(u.responsibleForAreaExternalIds())) {
                c.refEdge("RESPONSIBLE_FOR", "EntOrgUnit", u.externalId(), "EntFunctionalArea",
                        areaId, c.areas, "orgUnit " + u.externalId() + " responsibleForAreaExternalIds");
            }
        }
        for (ExecutiveDto e : safe(req.executives())) {
            if (e.companyLeader()) c.edge("LED_BY", "EntCompany", companyId, "EntExecutive", e.externalId(), null);
            c.refEdge("MEMBER_OF", "EntExecutive", e.externalId(), "EntOrgUnit",
                    e.memberOfOrgUnitExternalId(), c.orgUnits, "executive " + e.externalId() + " memberOfOrgUnitExternalId");
            c.refEdge("REPORTS_TO", "EntExecutive", e.externalId(), "EntExecutive",
                    e.reportsToExternalId(), c.executives, "executive " + e.externalId() + " reportsToExternalId");
        }
        for (ClientDto cl : safe(req.clients())) {
            c.edge("HAS_CLIENT", "EntCompany", companyId, "EntClient", cl.externalId(), null);
            c.refEdge("LOCATED_AT", "EntClient", cl.externalId(), "EntLocation",
                    cl.locationExternalId(), c.locations, "client " + cl.externalId() + " locationExternalId");
            for (String indId : safe(cl.industryExternalIds())) {
                c.refEdge("CLIENT_IN_INDUSTRY", "EntClient", cl.externalId(), "EntIndustry",
                        indId, c.industries, "client " + cl.externalId() + " industryExternalIds");
            }
        }
        for (EngagementDto en : safe(req.engagements())) {
            c.edge("HAS_ENGAGEMENT", "EntCompany", companyId, "EntEngagement", en.externalId(), null);
            c.refEdge("ENGAGEMENT_WITH", "EntEngagement", en.externalId(), "EntClient",
                    en.clientExternalId(), c.clients, "engagement " + en.externalId() + " clientExternalId");
            c.refEdge("ENGAGEMENT_IN_INDUSTRY", "EntEngagement", en.externalId(), "EntIndustry",
                    en.industryExternalId(), c.industries, "engagement " + en.externalId() + " industryExternalId");
            c.refEdge("DELIVERED_BY", "EntEngagement", en.externalId(), "EntOrgUnit",
                    en.deliveredByOrgUnitExternalId(), c.orgUnits, "engagement " + en.externalId() + " deliveredByOrgUnitExternalId");
        }

        log.info("Enterprise profile upserted for tenant {}: company={}, {} vertices, {} edges, {} warnings",
                tenantId, companyId, c.verticesWritten, c.edgesWritten, c.warnings.size());
        return new UpsertEnterpriseResponse("SUCCESS", companyId, c.verticesWritten, c.edgesWritten, c.warnings);
    }

    /** Reads back a count-per-type summary for the current tenant — quick verification after a load. */
    public EnterpriseSummary summary() {
        UUID tenantId = TenantContextHolder.requireTenantId();
        TenantRoutingInfo routing = tenantInfo.find(tenantId)
                .filter(TenantRoutingInfo::active)
                .orElseThrow(() -> new IllegalStateException(
                        "Cannot resolve routing (or tenant inactive) for " + tenantId));
        String db = routing.dataset();
        ArcadeDbTemplate arcade = resolver.templateFor(routing.arcadeConnectionId());

        List<Map<String, Object>> companies = graph.findCompanies(arcade, db, tenantId);
        String companyExternalId = companies.isEmpty() ? null : String.valueOf(companies.get(0).get("externalId"));
        String companyName = companies.isEmpty() ? null : String.valueOf(companies.get(0).get("name"));

        return new EnterpriseSummary(
                companyExternalId, companyName,
                graph.countOfType(arcade, db, tenantId, "EntFunctionalArea"),
                graph.countOfType(arcade, db, tenantId, "EntIndustry"),
                graph.countOfType(arcade, db, tenantId, "EntClient"),
                graph.countOfType(arcade, db, tenantId, "EntEngagement"),
                graph.countOfType(arcade, db, tenantId, "EntExecutive"),
                graph.countOfType(arcade, db, tenantId, "EntOrgUnit"),
                graph.countOfType(arcade, db, tenantId, "EntBranch"),
                graph.countOfType(arcade, db, tenantId, "EntLocation"));
    }

    // ── internal accumulator ──────────────────────────────────────────────────

    /** Per-request write context: holds the routed template, tallies, and the sets of ids seen in pass 1. */
    private final class Ctx {
        final ArcadeDbTemplate arcade;
        final String db;
        final UUID tenantId;
        int verticesWritten = 0;
        int edgesWritten = 0;
        final List<String> warnings = new java.util.ArrayList<>();
        final Set<String> values = new HashSet<>();
        final Set<String> areas = new HashSet<>();
        final Set<String> industries = new HashSet<>();
        final Set<String> locations = new HashSet<>();
        final Set<String> branches = new HashSet<>();
        final Set<String> orgUnits = new HashSet<>();
        final Set<String> executives = new HashSet<>();
        final Set<String> clients = new HashSet<>();
        final Set<String> engagements = new HashSet<>();

        Ctx(ArcadeDbTemplate arcade, String db, UUID tenantId) {
            this.arcade = arcade;
            this.db = db;
            this.tenantId = tenantId;
        }

        void vertex(String type, String externalId, Map<String, Object> props) {
            graph.upsertVertex(arcade, db, tenantId, type, SOURCE, externalId, props);
            verticesWritten++;
        }

        void edge(String edgeType, String fromType, String fromId, String toType, String toId, String ignored) {
            if (graph.linkOnce(arcade, db, tenantId, edgeType, fromType, fromId, toType, toId)) edgesWritten++;
        }

        /**
         * Edge whose target id is caller-supplied and optional: null → skip
         * silently; present-but-unknown → skip with a warning (dangling reference);
         * present-and-known → create.
         */
        void refEdge(String edgeType, String fromType, String fromId, String toType, String toId,
                     Set<String> known, String fieldDesc) {
            if (!notBlank(toId)) return;
            if (!known.contains(toId)) {
                warnings.add("Skipped " + edgeType + ": " + fieldDesc + " references unknown id '" + toId + "'");
                return;
            }
            if (graph.linkOnce(arcade, db, tenantId, edgeType, fromType, fromId, toType, toId)) edgesWritten++;
        }
    }

    // ── small helpers ──────────────────────────────────────────────────────────

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Builds a property map from alternating key/value args, preserving order. Values may be null. */
    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static String slug(String s) {
        return s == null ? "" : s.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }
}
