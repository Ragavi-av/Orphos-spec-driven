package io.orphos.eip.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class EipRolesTest {

    @Test
    void platformAdminImpliesEveryTenantRole() {
        assertThat(EipRoles.expand(List.of(EipRoles.PLATFORM_ADMIN)))
                .containsAll(EipRoles.TENANT_BUILT_IN);
    }

    @Test
    void scopedAdminsImplyNothingElse() {
        assertThat(EipRoles.expand(List.of(EipRoles.SECOPS_ADMIN))).containsExactly(EipRoles.SECOPS_ADMIN);
        assertThat(EipRoles.expand(List.of(EipRoles.DATA_AI_ADMIN))).containsExactly(EipRoles.DATA_AI_ADMIN);
    }

    @Test
    void cSuiteImpliesExecutiveViewerGuestButNotAdmin() {
        assertThat(EipRoles.expand(List.of(EipRoles.CFO)))
                .containsExactlyInAnyOrder(EipRoles.CFO, EipRoles.EXECUTIVE, EipRoles.VIEWER, EipRoles.GUEST);
    }

    @Test
    void customRolesPassThroughUnexpanded() {
        assertThat(EipRoles.expand(List.of("operations_head"))).containsExactly("operations_head");
    }

    @Test
    void springHierarchyMatchesExpand() {
        // The Spring RoleHierarchy and EipRoles.expand are built from the same edges;
        // this pins that they can never disagree.
        RoleHierarchyImpl spring = RoleHierarchyImpl.fromHierarchy(EipRoles.springHierarchy());
        for (String role : List.of(EipRoles.PLATFORM_ADMIN, EipRoles.ADMIN, EipRoles.CEO,
                EipRoles.EXECUTIVE, EipRoles.VIEWER, EipRoles.SECOPS_ADMIN)) {
            Set<String> viaSpring = spring
                    .getReachableGrantedAuthorities(List.of(new SimpleGrantedAuthority("ROLE_" + role)))
                    .stream().map(GrantedAuthority::getAuthority).map(a -> a.substring(5))
                    .collect(Collectors.toSet());
            assertThat(viaSpring).as(role).isEqualTo(EipRoles.expand(List.of(role)));
        }
    }

    @Test
    void membershipExcludesGuestAndBookkeepingRoles() {
        assertThat(EipRoles.confersMembership(EipRoles.GUEST)).isFalse();
        assertThat(EipRoles.confersMembership("default-roles-eip")).isFalse();
        assertThat(EipRoles.confersMembership("offline_access")).isFalse();
        assertThat(EipRoles.confersMembership(EipRoles.SERVICE_INTERNAL)).isFalse();
        assertThat(EipRoles.confersMembership(EipRoles.VIEWER)).isTrue();
        assertThat(EipRoles.confersMembership("operations_head")).isTrue();
        assertThat(EipRoles.confersMembership(EipRoles.INGESTION_ADMIN)).isTrue();
    }

    @Test
    void assignedRolesSkipDerivedAuthorities() {
        var auth = new TestingAuthenticationToken("u", "p", List.of(
                new SimpleGrantedAuthority("ROLE_operations_head"),
                EipDerivedAuthority.role(EipRoles.CFO),
                EipDerivedAuthority.role(EipRoles.MEMBER)));
        assertThat(EipRoles.assignedRoles(auth)).containsExactly("operations_head");
        assertThat(EipRoles.roleNames(auth)).contains("operations_head", EipRoles.CFO, EipRoles.MEMBER);
        assertThat(EipRoles.hasRole(auth, EipRoles.EXECUTIVE)).as("derived base is honoured").isTrue();
    }

    @Test
    void personaIsStrongestAssignedRole() {
        assertThat(EipRoles.persona(List.of("viewer", "cfo"))).isEqualTo("cfo");
        assertThat(EipRoles.persona(List.of("CEO"))).isEqualTo("ceo");
        assertThat(EipRoles.persona(List.of("operations_head"))).isEqualTo("guest");
        assertThat(EipRoles.persona(null)).isEqualTo("guest");
    }
}
