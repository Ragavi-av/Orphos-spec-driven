package io.orphos.eip.admin.vo;

import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.List;

public final class DashboardLayoutDtos {

    private DashboardLayoutDtos() {}

    /** Inbound payload for upserting a layout (user override or tenant default). */
    public record LayoutRequest(
            @NotNull List<String> visibleIds,
            @NotNull List<String> order
    ) {}

    /** Outbound view returned by both /me and /default reads. */
    public record LayoutView(
            List<String> visibleIds,
            List<String> order,
            String source,   // "user" | "tenant" | "persona" | "system"
            Instant updatedAt
    ) {}

    /** Server-side widget catalog item — used so the SPA cannot push unknown ids. */
    public record CatalogItem(
            String id,
            String label,
            String description,
            String color
    ) {}
}
