package io.orphos.eip.common.access;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AccessPolicyTest {

    private static final Map<String, Map<String, String>> NONE = Map.of();

    @Test
    void dashboardSectionsDefaultToAllowForRealRoles() {
        assertThat(AccessPolicy.effectiveAccess(NONE, "viewer", "ask-orphos", "user-dashboard")).isEqualTo("allow");
        assertThat(AccessPolicy.effectiveAccess(NONE, "operations_head", "ask-orphos", "user-dashboard"))
                .as("custom roles keep the dashboard by default").isEqualTo("allow");
    }

    @Test
    void guestAndBookkeepingRolesDefaultToDenyEverywhere() {
        for (String role : List.of("guest", "default-roles-eip", "offline_access", "uma_authorization")) {
            assertThat(AccessPolicy.effectiveAccess(NONE, role, "ask-orphos", "user-dashboard")).as(role).isEqualTo("deny");
        }
        // Every token carries these, so allow-wins over them must not open the dashboard.
        assertThat(AccessPolicy.anyRoleAllows(NONE,
                List.of("guest", "default-roles-eip", "offline_access"), "ask-orphos", "user-dashboard")).isFalse();
    }

    @Test
    void guestCanStillBeGrantedExplicitly() {
        Map<String, Map<String, String>> grants = Map.of("guest", Map.of("doc-search", "allow"));
        assertThat(AccessPolicy.effectiveAccess(grants, "guest", "doc-search", "user-dashboard")).isEqualTo("allow");
    }

    @Test
    void explicitResourceGrantBeatsGroupGrantBeatsDefault() {
        Map<String, Map<String, String>> grants = Map.of("viewer",
                Map.of("user-dashboard", "deny", "ask-orphos", "allow"));
        assertThat(AccessPolicy.effectiveAccess(grants, "viewer", "ask-orphos", "user-dashboard")).isEqualTo("allow");
        assertThat(AccessPolicy.effectiveAccess(grants, "viewer", "doc-search", "user-dashboard")).isEqualTo("deny");
    }

    @Test
    void lockedResourcesAreDeniedToEveryoneButPlatformAdmin() {
        Map<String, Map<String, String>> grants = Map.of("admin", Map.of("tenants", "allow"));
        assertThat(AccessPolicy.effectiveAccess(grants, "admin", "tenants", "security-ops")).isEqualTo("deny");
        assertThat(AccessPolicy.effectiveAccess(grants, "platform_admin", "tenants", "security-ops")).isEqualTo("allow");
    }

    @Test
    void adminCannotBeLockedOutOfAccessControl() {
        Map<String, Map<String, String>> grants = Map.of("admin", Map.of("access-control", "deny"));
        assertThat(AccessPolicy.effectiveAccess(grants, "admin", "access-control", "auth-authz")).isEqualTo("allow");
    }

    @Test
    void scopedAdminsDefaultToTheirOwnArea() {
        assertThat(AccessPolicy.roleDefault("secops_admin", "auth-authz")).isEqualTo("allow");
        assertThat(AccessPolicy.roleDefault("secops_admin", "data")).isEqualTo("deny");
        assertThat(AccessPolicy.roleDefault("data_ai_admin", "ai")).isEqualTo("allow");
        assertThat(AccessPolicy.roleDefault("ingestion_admin", "documents")).isEqualTo("allow");
    }
}
