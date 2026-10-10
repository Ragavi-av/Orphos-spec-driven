package io.orphos.eip.common.access;

import io.orphos.eip.common.access.PersonaPolicy.Classification;
import io.orphos.eip.common.access.PersonaPolicy.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirrors the assertions that {@code scripts/ontology/VerifyPersonaAccess.java}
 * makes against the live graph, but as pure logic with no database — so the
 * closure maths is pinned down here and the script only has to prove the graph
 * actually holds the edges it expects.
 *
 * <p>Fixture matches the loaded Zenitus vocabulary (see
 * {@code scripts/zenitus-governance-profile.json}) in the parts that matter:
 * the persona chain, the dual-parent IT Spend domain, and the four
 * sensitivity ranks.</p>
 */
class PersonaPolicyTest {

    // Sensitivity ranks: public 0, internal 1, confidential 2, restricted 3.
    private static final int PUBLIC = 0, INTERNAL = 1, CONFIDENTIAL = 2, RESTRICTED = 3;

    private static Snapshot zenitus() {
        Map<String, Integer> ceilings = Map.of(
                "guest", PUBLIC,
                "viewer", INTERNAL,
                "executive", INTERNAL,
                "ceo", CONFIDENTIAL,
                "cfo", CONFIDENTIAL,
                "cto", CONFIDENTIAL,
                "chro", CONFIDENTIAL,
                "admin", CONFIDENTIAL);

        // guest → viewer → executive → {ceo, cfo, cto, chro}; admin beside the chain.
        Map<String, Set<String>> inherits = Map.of(
                "viewer", Set.of("guest"),
                "executive", Set.of("viewer"),
                "ceo", Set.of("executive"),
                "cfo", Set.of("executive"),
                "cto", Set.of("executive"),
                "chro", Set.of("executive"),
                "admin", Set.of("viewer"));

        Map<String, Set<String>> grants = Map.of(
                "viewer", Set.of("dom-enterprise"),
                "executive", Set.of("dom-strategy"),
                "ceo", Set.of("dom-finance", "dom-hr", "dom-it", "dom-commercial", "dom-risk"),
                "cfo", Set.of("dom-finance", "dom-payroll", "dom-commercial", "dom-risk"),
                "cto", Set.of("dom-it", "dom-portfolio"),
                "chro", Set.of("dom-hr"),
                "admin", Set.of("dom-governance"));

        // child → parents. dom-it-spend has TWO parents on purpose.
        // Map.ofEntries, not Map.of — the latter caps at 10 pairs.
        Map<String, Set<String>> subDomains = Map.ofEntries(
                Map.entry("dom-budget", Set.of("dom-finance")),
                Map.entry("dom-treasury", Set.of("dom-finance")),
                Map.entry("dom-gl", Set.of("dom-finance")),
                Map.entry("dom-it-spend", Set.of("dom-it", "dom-finance")),
                Map.entry("dom-headcount", Set.of("dom-hr")),
                Map.entry("dom-payroll", Set.of("dom-hr")),
                Map.entry("dom-talent", Set.of("dom-hr")),
                Map.entry("dom-it-ops", Set.of("dom-it")),
                Map.entry("dom-it-security", Set.of("dom-it")),
                Map.entry("dom-pipeline", Set.of("dom-commercial")),
                Map.entry("dom-contracts", Set.of("dom-commercial")),
                Map.entry("dom-portfolio", Set.of("dom-strategy")));

        List<Classification> classifications = List.of(
                new Classification("HrPayrollRun", "dom-payroll", CONFIDENTIAL),
                new Classification("BudgetItem", "dom-it-spend", CONFIDENTIAL),
                new Classification("FinCashFlowEntry", "dom-treasury", CONFIDENTIAL),
                new Classification("FinGLAccount", "dom-gl", INTERNAL),
                new Classification("Incident", "dom-it-ops", INTERNAL),
                new Classification("ItopsVulnerability", "dom-it-security", CONFIDENTIAL),
                new Classification("Decision", "dom-strategy", CONFIDENTIAL),
                new Classification("StrObjective", "dom-strategy", INTERNAL),
                new Classification("EntCompany", "dom-enterprise", PUBLIC),
                new Classification("GovAccessGrant", "dom-governance", CONFIDENTIAL),
                new Classification("SecretPayslip", "dom-payroll", RESTRICTED));

        return PersonaPolicy.buildSnapshot(ceilings, inherits, grants, subDomains, classifications);
    }

    // ── closures ───────────────────────────────────────────────────────────────

    @Test
    void personaInheritsDomainsThroughTheWholeChain() {
        Snapshot s = zenitus();
        // ceo → executive → viewer, so the Viewer grant arrives 2 hops up.
        assertTrue(s.personaByRole().get("ceo").domains().contains("dom-enterprise"));
        assertTrue(s.personaByRole().get("ceo").domains().contains("dom-strategy"));
        // ...and cfo gets it by the same route.
        assertTrue(s.personaByRole().get("cfo").domains().contains("dom-enterprise"));
    }

    @Test
    void grantOnParentDomainReachesDescendants() {
        Snapshot s = zenitus();
        Set<String> cfo = s.personaByRole().get("cfo").domains();
        assertTrue(cfo.containsAll(Set.of("dom-budget", "dom-treasury", "dom-gl")),
                "granting dom-finance must reach its children");
    }

    @Test
    void dualParentDomainIsReachableFromEitherParent() {
        Snapshot s = zenitus();
        assertTrue(s.personaByRole().get("cto").domains().contains("dom-it-spend"),
                "CTO reaches IT Spend through dom-it");
        assertTrue(s.personaByRole().get("cfo").domains().contains("dom-it-spend"),
                "CFO reaches the same domain through dom-finance");
    }

    @Test
    void ctoDoesNotReachTheRestOfTheLedger() {
        Set<String> cto = zenitus().personaByRole().get("cto").domains();
        assertFalse(cto.contains("dom-finance"));
        assertFalse(cto.contains("dom-treasury"));
        assertFalse(cto.contains("dom-gl"));
        assertFalse(cto.contains("dom-payroll"));
    }

    @Test
    void adminHoldsNoBusinessDomainBeyondViewer() {
        Set<String> admin = zenitus().personaByRole().get("admin").domains();
        assertEquals(Set.of("dom-governance", "dom-enterprise"), admin,
                "administering the platform must not confer reading the company's numbers");
    }

    @Test
    void inheritanceCyclesDoNotHang() {
        // A malformed vocabulary must not spin: a ↔ b is a data error, not a crash.
        Snapshot s = PersonaPolicy.buildSnapshot(
                Map.of("a", INTERNAL, "b", INTERNAL),
                Map.of("a", Set.of("b"), "b", Set.of("a")),
                Map.of("a", Set.of("dom-x")),
                Map.of(),
                List.of(new Classification("T", "dom-x", INTERNAL)));
        assertTrue(PersonaPolicy.roleAllows(s, "b", "T"), "b inherits a's grant across the cycle");
    }

    @Test
    void domainCyclesDoNotHang() {
        Snapshot s = PersonaPolicy.buildSnapshot(
                Map.of("r", INTERNAL),
                Map.of(),
                Map.of("r", Set.of("d1")),
                Map.of("d1", Set.of("d2"), "d2", Set.of("d1")),
                List.of(new Classification("T", "d2", INTERNAL)));
        assertTrue(PersonaPolicy.roleAllows(s, "r", "T"));
    }

    // ── the two axes are independent ───────────────────────────────────────────

    @Test
    void sensitivityCeilingBitesEvenWhenTheDomainIsHeld() {
        Snapshot s = zenitus();
        // Executive HOLDS dom-strategy but its ceiling is Internal...
        assertTrue(s.personaByRole().get("executive").domains().contains("dom-strategy"));
        // ...so a Confidential record in that domain is still denied.
        assertFalse(PersonaPolicy.roleAllows(s, "executive", "Decision"));
        // The same persona reads an Internal record in the same domain.
        assertTrue(PersonaPolicy.roleAllows(s, "executive", "StrObjective"));
        // And a persona with the higher ceiling reads it.
        assertTrue(PersonaPolicy.roleAllows(s, "ceo", "Decision"));
    }

    @Test
    void domainGateBitesEvenWhenSensitivityIsFine() {
        Snapshot s = zenitus();
        // Internal record, and cto's ceiling is Confidential — but not its domain.
        assertFalse(PersonaPolicy.roleAllows(s, "cto", "FinGLAccount"));
        assertTrue(PersonaPolicy.roleAllows(s, "cfo", "FinGLAccount"));
    }

    // ── decisions on real types ────────────────────────────────────────────────

    @Test
    void payrollIsVisibleToFinanceAndPeopleRolesOnly() {
        Snapshot s = zenitus();
        assertTrue(PersonaPolicy.roleAllows(s, "cfo", "HrPayrollRun"));
        assertTrue(PersonaPolicy.roleAllows(s, "chro", "HrPayrollRun"));
        assertTrue(PersonaPolicy.roleAllows(s, "ceo", "HrPayrollRun"));
        assertFalse(PersonaPolicy.roleAllows(s, "cto", "HrPayrollRun"));
        assertFalse(PersonaPolicy.roleAllows(s, "admin", "HrPayrollRun"));
        assertFalse(PersonaPolicy.roleAllows(s, "viewer", "HrPayrollRun"));
    }

    @Test
    void restrictedDataIsReadableByNobody() {
        Snapshot s = zenitus();
        for (String role : s.personaByRole().keySet()) {
            assertFalse(PersonaPolicy.roleAllows(s, role, "SecretPayslip"),
                    role + " must not read Restricted data");
        }
        assertTrue(PersonaPolicy.readableByNobody(s, "SecretPayslip"),
                "so the RAG corpus builder can refuse to index it at all");
        assertFalse(PersonaPolicy.readableByNobody(s, "HrPayrollRun"));
    }

    @Test
    void allowWinsAcrossMultipleRoles() {
        Snapshot s = zenitus();
        assertTrue(PersonaPolicy.allows(s, List.of("cto", "cfo"), "FinCashFlowEntry"),
                "a user acting with both roles sees the union");
        assertFalse(PersonaPolicy.allows(s, List.of("cto", "viewer"), "FinCashFlowEntry"));
    }

    @Test
    void unclassifiedTypeIsDeniedInsideAnEnforcedTenant() {
        Snapshot s = zenitus();
        assertFalse(PersonaPolicy.roleAllows(s, "ceo", "SomeTypeNobodyClassified"),
                "data no rule can reason about must not be readable");
    }

    @Test
    void unknownRoleIsDeniedInsideAnEnforcedTenant() {
        assertFalse(PersonaPolicy.roleAllows(zenitus(), "not_a_persona", "Incident"));
    }

    // ── rollout safety ─────────────────────────────────────────────────────────

    @Test
    void unprovisionedTenantIsCompletelyUnenforced() {
        assertFalse(PersonaPolicy.enforced(Snapshot.EMPTY));
        // Every call must pass, including for types and roles nobody declared —
        // otherwise shipping this blinds every tenant that has not been migrated.
        assertTrue(PersonaPolicy.roleAllows(Snapshot.EMPTY, "cto", "HrPayrollRun"));
        assertTrue(PersonaPolicy.allows(Snapshot.EMPTY, List.of("anything"), "AnyType"));
        assertFalse(PersonaPolicy.readableByNobody(Snapshot.EMPTY, "AnyType"));
        assertEquals(List.of("A", "B"),
                PersonaPolicy.readableTypes(Snapshot.EMPTY, List.of("x"), List.of("A", "B")));
    }

    @Test
    void callerWithNoRolesIsUnenforced() {
        Snapshot s = zenitus();
        // Background jobs and internal callers have no principal; filtering them
        // would break corpus building and service-to-service work.
        assertTrue(PersonaPolicy.allows(s, List.of(), "HrPayrollRun"));
        assertTrue(PersonaPolicy.allows(s, null, "HrPayrollRun"));
    }

    @Test
    void readableTypesPreservesOrderAndDropsDenied() {
        Snapshot s = zenitus();
        List<String> candidates = List.of("Incident", "HrPayrollRun", "BudgetItem", "FinGLAccount");
        assertEquals(List.of("Incident", "BudgetItem"),
                PersonaPolicy.readableTypes(s, List.of("cto"), candidates));
        assertEquals(List.of("HrPayrollRun", "BudgetItem", "FinGLAccount"),
                PersonaPolicy.readableTypes(s, List.of("cfo"), candidates));
    }
}
