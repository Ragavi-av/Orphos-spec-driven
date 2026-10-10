package io.orphos.eip.admin.roles.dto;

import java.util.List;

/** DTOs for the role-management admin API (Authentication &amp; Authorization → User Management). */
public final class RoleDtos {
    private RoleDtos() {}

    /**
     * A role as shown in the management table. {@code alias} is the display name
     * used across the system (falls back to the built-in label). {@code custom}
     * marks admin-created roles; {@code deletable} is true only for a custom role
     * with no users assigned.
     */
    public record RoleView(
            String key,
            String alias,
            String color,
            boolean custom,
            boolean isDefault,
            int userCount,
            boolean deletable
    ) {}

    public record CreateRoleRequest(String key, String alias, String color, String basedOn) {}

    public record CopyRoleRequest(String key, String alias, String color) {}

    public record UpdateRoleRequest(String alias, String color) {}

    /** Result of "reset to defaults": which custom roles were removed vs kept (still in use). */
    public record ResetResult(List<String> removed, List<String> kept) {}
}
