package io.orphos.eip.common.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.orphos.eip.common.access.DataDomains.*;
import static org.assertj.core.api.Assertions.assertThat;

class PersonaDomainPolicyTest {

    private static PersonaDomainPolicy.Scope scope(String... roles) {
        return PersonaDomainPolicy.forRoles(List.of(roles), List.of(roles), null);
    }

    @Test
    void eachPersonaSeesItsOwnDomains() {
        assertThat(scope("viewer").domains()).containsExactlyInAnyOrder(ENTERPRISE);
        assertThat(scope("executive").domains()).containsExactlyInAnyOrder(ENTERPRISE, STRATEGY);
        assertThat(scope("ceo").domains()).containsExactlyInAnyOrder(ENTERPRISE, STRATEGY, FINANCE, COMMERCIAL, RISK);
        assertThat(scope("cfo").domains()).containsExactlyInAnyOrder(ENTERPRISE, STRATEGY, FINANCE, COMMERCIAL, RISK);
        assertThat(scope("cto").domains()).containsExactlyInAnyOrder(ENTERPRISE, STRATEGY, IT);
        assertThat(scope("chro").domains()).containsExactlyInAnyOrder(ENTERPRISE, STRATEGY, HR);
        assertThat(scope("coo").domains()).containsExactlyInAnyOrder(ENTERPRISE, STRATEGY, OPERATIONS, COMMERCIAL);
        assertThat(scope("guest").domains()).isEmpty();
    }

    @Test
    void ceoDoesNotSeeTechnicalOrPeopleDetail() {
        PersonaDomainPolicy.Scope ceo = scope("ceo");
        assertThat(ceo.allows(IT, false, false)).isFalse();
        assertThat(ceo.allows(HR, false, false)).isFalse();
        assertThat(ceo.allows(OPERATIONS, false, false)).isFalse();
    }

    @Test
    void onlyTheCeoGetsCriticalItemsFromEveryDomain() {
        assertThat(scope("ceo").allows(IT, true, false)).isTrue();
        assertThat(scope("cfo").allows(IT, true, false)).isFalse();
        assertThat(scope("viewer").allows(FINANCE, true, false)).isFalse();
    }

    @Test
    void ownItemsAreAlwaysVisible() {
        assertThat(scope("cto").allows(FINANCE, false, true)).isTrue();
        assertThat(scope("viewer").allows(HR, false, true)).isTrue();
    }

    @Test
    void viewerAndCeoGetDifferentScopes() {
        assertThat(scope("viewer").allows(FINANCE, false, false)).isFalse();
        assertThat(scope("ceo").allows(FINANCE, false, false)).isTrue();
        assertThat(scope("viewer").cacheKey()).isNotEqualTo(scope("ceo").cacheKey());
    }

    @Test
    void adminSeesEveryBusinessDomain() {
        assertThat(scope("admin").domains()).containsAll(DataDomains.ALL);
    }

    @Test
    void platformAdminAndInternalCallersAreUnrestricted() {
        assertThat(scope("platform_admin").unrestricted()).isTrue();
        assertThat(scope("service_internal").unrestricted()).isTrue();
    }

    @Test
    void customRoleTakesItsBaseScope() {
        PersonaDomainPolicy.Scope s = PersonaDomainPolicy.forRoles(
                List.of("operations_head"), List.of("operations_head", "cfo", "member"), null);
        assertThat(s.domains()).contains(FINANCE, ENTERPRISE).doesNotContain(IT);
    }

    @Test
    void scopedAdminsAndUnbasedCustomRolesSeeGeneralItemsOnly() {
        assertThat(scope("secops_admin").domains()).containsExactly(ENTERPRISE);
        assertThat(scope("some_custom_role").domains()).containsExactly(ENTERPRISE);
    }

    @Test
    void tenantGrantsWidenOrNarrowAScope() {
        Map<String, Map<String, String>> grants = Map.of(
                "viewer", Map.of("data-domain:finance", "allow"),
                "cto", Map.of("data-domain:strategy", "deny"));
        assertThat(PersonaDomainPolicy.forRoles(List.of("viewer"), List.of("viewer"), grants).domains())
                .containsExactlyInAnyOrder(ENTERPRISE, FINANCE);
        assertThat(PersonaDomainPolicy.forRoles(List.of("cto"), List.of("cto"), grants).domains())
                .doesNotContain(STRATEGY).contains(IT);
    }

    @Test
    void bookkeepingRolesAddNothing() {
        assertThat(scope("viewer", "offline_access", "default-roles-eip").domains()).containsExactly(ENTERPRISE);
    }

    @Test
    void unlabelledItemsCountAsGeneral() {
        assertThat(scope("viewer").allows(null, false, false)).isTrue();
    }

    @Test
    void criticalMeansCriticalPriorityOrAlert() {
        assertThat(PersonaDomainPolicy.isCritical("CRITICAL", "WARNING")).isTrue();
        assertThat(PersonaDomainPolicy.isCritical("HIGH", "ALERT")).isTrue();
        assertThat(PersonaDomainPolicy.isCritical("HIGH", "WARNING")).isFalse();
    }

    @Test
    void labelsNormalise() {
        assertThat(DataDomains.normalize("FINANCE")).isEqualTo(FINANCE);
        assertThat(DataDomains.normalize("Security")).isEqualTo(IT);
        assertThat(DataDomains.normalize("SALES")).isEqualTo(COMMERCIAL);
        assertThat(DataDomains.normalize("Human Resources")).isEqualTo(HR);
        assertThat(DataDomains.normalize("mystery")).isNull();
        assertThat(DataDomains.normalize("")).isNull();
    }

    @Test
    void textClassification() {
        assertThat(DataDomains.classify("Q3 budget variance and cash flow forecast")).isEqualTo(FINANCE);
        assertThat(DataDomains.classify("Interview schedule for senior engineer candidate",
                "Please confirm the hiring panel")).isEqualTo(HR);
        assertThat(DataDomains.classify("Production outage on payments API", "deploy rollback")).isEqualTo(IT);
        assertThat(DataDomains.classify("Renewal proposal for Customer Alpha contract")).isEqualTo(COMMERCIAL);
        assertThat(DataDomains.classify("Vendor shipment delayed at warehouse")).isEqualTo(OPERATIONS);
        assertThat(DataDomains.classify("Board strategy offsite agenda")).isEqualTo(STRATEGY);
        assertThat(DataDomains.classify("Lunch on Friday?")).isEqualTo(ENTERPRISE);
        assertThat(DataDomains.classify(null)).isEqualTo(ENTERPRISE);
    }

    @Test
    void ambiguousWordsDoNotMisfile() {
        // "leadership", "Slack" and "system" used to trip the commercial / IT keywords.
        assertThat(DataDomains.classify("Leadership lunch in the Slack channel")).isEqualTo(ENTERPRISE);
        assertThat(DataDomains.classify("Please leave feedback on the new system")).isEqualTo(ENTERPRISE);
    }

    @Test
    void labelWinsOverText() {
        assertThat(DataDomains.of("HR", "Budget sign-off for Q3")).isEqualTo(HR);
        assertThat(DataDomains.of("", "Budget sign-off for Q3")).isEqualTo(FINANCE);
    }

    @Test
    void everyDomainIsKnown() {
        assertThat(Set.copyOf(DataDomains.ALL)).allMatch(DataDomains::isKnown);
        assertThat(DataDomains.isKnown("payroll")).isFalse();
    }
}
