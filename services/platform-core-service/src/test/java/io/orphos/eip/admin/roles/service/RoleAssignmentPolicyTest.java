package io.orphos.eip.admin.roles.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoleAssignmentPolicyTest {

    private static final Map<String, String> BASES = Map.of(
            "operations_head", "cfo",
            "security_lead", "secops_admin",
            "super_ops", "admin",
            "unbased", "");

    private static boolean can(List<String> actor, String role) {
        return RoleAssignmentPolicy.canManage(actor, role, BASES);
    }

    @Test
    void adminAndPlatformAdminManageEveryTenantRole() {
        for (String role : List.of("admin", "secops_admin", "data_ai_admin", "ceo", "viewer", "guest",
                "operations_head", "super_ops")) {
            assertThat(can(List.of("admin"), role)).as("admin → " + role).isTrue();
            assertThat(can(List.of("platform_admin"), role)).as("platform_admin → " + role).isTrue();
        }
    }

    @Test
    void reservedRolesAreNeverManageable() {
        for (String role : List.of("platform_admin", "service_internal", "member", "default-roles-eip", "offline_access")) {
            assertThat(can(List.of("platform_admin"), role)).as(role).isFalse();
        }
    }

    @Test
    void secopsManagesNonAdminRolesOnly() {
        List<String> secops = List.of("secops_admin");
        assertThat(can(secops, "ceo")).isTrue();
        assertThat(can(secops, "executive")).isTrue();
        assertThat(can(secops, "viewer")).isTrue();
        assertThat(can(secops, "guest")).isTrue();
        assertThat(can(secops, "operations_head")).as("custom based on cfo").isTrue();
        assertThat(can(secops, "unbased")).as("custom with no base").isTrue();

        assertThat(can(secops, "admin")).isFalse();
        assertThat(can(secops, "secops_admin")).as("cannot mint peers").isFalse();
        assertThat(can(secops, "data_ai_admin")).isFalse();
        assertThat(can(secops, "super_ops")).as("custom based on admin").isFalse();
        assertThat(can(secops, "security_lead")).as("custom based on secops").isFalse();
    }

    @Test
    void customRoleBasedOnSecopsActsLikeSecops() {
        // The actor's role names include the derived base, as EipRoles.roleNames returns them.
        List<String> actor = List.of("security_lead", "secops_admin");
        assertThat(can(actor, "viewer")).isTrue();
        assertThat(can(actor, "admin")).isFalse();
    }

    @Test
    void everyoneElseManagesNothing() {
        for (List<String> actor : List.of(List.of("ceo"), List.of("executive"), List.of("viewer"),
                List.of("data_ai_admin"), List.of("ingestion_admin"), List.<String>of())) {
            assertThat(can(actor, "viewer")).as(actor.toString()).isFalse();
        }
    }

    @Test
    void unknownBasesRefuseCustomRolesToSecops() {
        assertThat(RoleAssignmentPolicy.canManage(List.of("secops_admin"), "operations_head", null)).isFalse();
        assertThat(RoleAssignmentPolicy.canManage(List.of("secops_admin"), "viewer", null)).isTrue();
        assertThat(RoleAssignmentPolicy.canManage(List.of("admin"), "operations_head", null)).isTrue();
    }

    @Test
    void secopsCannotPromoteToAdmin() {
        assertThat(RoleAssignmentPolicy.unmanageableChanges(List.of("secops_admin"),
                List.of("viewer"), List.of("viewer", "admin"), BASES)).containsExactly("admin");
    }

    @Test
    void secopsCannotDemoteAnAdmin() {
        assertThat(RoleAssignmentPolicy.unmanageableChanges(List.of("secops_admin"),
                List.of("admin", "ceo"), List.of("ceo"), BASES)).containsExactly("admin");
    }

    @Test
    void unchangedRolesDoNotCountAgainstTheActor() {
        // Secops may change a user's C-suite role even if the user also holds admin,
        // as long as admin itself is left untouched.
        assertThat(RoleAssignmentPolicy.unmanageableChanges(List.of("secops_admin"),
                List.of("admin", "cto"), List.of("admin", "cfo"), BASES)).isEmpty();
    }

    @Test
    void bookkeepingRolesAreIgnoredInDiffs() {
        assertThat(RoleAssignmentPolicy.unmanageableChanges(List.of("secops_admin"),
                List.of("guest", "default-roles-eip"), List.of("viewer"), BASES)).isEmpty();
    }

    @Test
    void secopsCannotActOnAUserHoldingAdmin() {
        assertThat(RoleAssignmentPolicy.unmanageableHeld(List.of("secops_admin"),
                List.of("admin"), BASES)).containsExactly("admin");
        assertThat(RoleAssignmentPolicy.unmanageableHeld(List.of("secops_admin"),
                List.of("viewer", "operations_head"), BASES)).isEmpty();
    }
}
