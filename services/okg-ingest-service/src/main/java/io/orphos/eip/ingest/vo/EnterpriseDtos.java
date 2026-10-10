package io.orphos.eip.ingest.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

/**
 * Request/response shapes for the Enterprise ontology admin API.
 *
 * <p>The whole enterprise profile is submitted in one payload. Every entity
 * carries a caller-chosen {@code externalId} that is stable across submissions —
 * it is the upsert key, and it is what relationship fields reference (e.g. an
 * executive's {@code reportsToExternalId} names another executive's
 * {@code externalId}). Referencing an id that isn't present in the same payload
 * (or already in the graph) simply skips that one edge rather than failing the
 * whole load.</p>
 *
 * <p>Dates are ISO-8601 strings ({@code yyyy-MM-dd}); the graph stores them as
 * strings, consistent with the rest of the OKG graph.</p>
 */
public final class EnterpriseDtos {

    private EnterpriseDtos() {}

    /** The full profile. Only {@code company} is required; every collection may be null/empty. */
    public record UpsertEnterpriseRequest(
            @NotBlank String externalId,      // stable id of the company itself
            @Valid CompanyDto company,
            String mission,                   // convenience: mission statement text
            String vision,                    // convenience: vision statement text
            List<ValueDto> values,
            List<FunctionalAreaDto> functionalAreas,
            List<IndustryDto> industries,
            List<LocationDto> locations,
            List<BranchDto> branches,
            List<OrgUnitDto> orgUnits,
            List<ExecutiveDto> executives,
            List<ClientDto> clients,
            List<EngagementDto> engagements) {}

    public record CompanyDto(
            @NotBlank String name,
            String legalName,
            String tradeName,
            Integer foundedYear,
            String registrationNumber,
            String website,
            String companyType,              // PRIVATE | PUBLIC | GOVERNMENT | NONPROFIT
            String stockSymbol,
            Integer employeeCount,
            Double annualRevenue,
            String currency,
            String description,
            String headquartersLocationExternalId) {}

    /** A guiding principle. {@code externalId} defaults to a slug of {@code name} if blank in the service. */
    public record ValueDto(String externalId, @NotBlank String name, String statement) {}

    public record FunctionalAreaDto(@NotBlank String externalId, @NotBlank String name, String description) {}

    public record IndustryDto(@NotBlank String externalId, @NotBlank String name, String sectorCode, String description) {}

    public record LocationDto(
            @NotBlank String externalId,
            String name,
            String addressLine,
            String city,
            String stateProvince,
            String country,
            String postalCode,
            Double latitude,
            Double longitude,
            String timezone) {}

    public record BranchDto(
            @NotBlank String externalId,
            @NotBlank String name,
            String branchType,               // HEADQUARTERS | REGIONAL | SALES | RND | MANUFACTURING
            String openedDate,
            String description,
            String locationExternalId) {}     // → LOCATED_AT

    public record OrgUnitDto(
            @NotBlank String externalId,
            @NotBlank String name,
            String unitType,                  // DIVISION | DEPARTMENT | TEAM
            String unitCode,
            String description,
            String parentExternalId,          // → SUB_UNIT_OF (this unit → parent)
            String headExecutiveExternalId,   // → HEADED_BY
            List<String> responsibleForAreaExternalIds) {} // → RESPONSIBLE_FOR

    public record ExecutiveDto(
            @NotBlank String externalId,
            @NotBlank String displayName,
            String jobTitle,
            String appointedDate,
            boolean companyLeader,            // → Company LED_BY (C-suite / board)
            String memberOfOrgUnitExternalId, // → MEMBER_OF
            String reportsToExternalId) {}     // → REPORTS_TO (another executive)

    public record ClientDto(
            @NotBlank String externalId,
            @NotBlank String name,
            String clientTier,                // STRATEGIC | KEY | STANDARD
            String clientSince,
            String relationshipStatus,        // ACTIVE | PROSPECT | CHURNED
            String description,
            String locationExternalId,        // → LOCATED_AT
            List<String> industryExternalIds) {} // → CLIENT_IN_INDUSTRY

    public record EngagementDto(
            @NotBlank String externalId,
            @NotBlank String name,
            String startDate,
            String endDate,
            String engagementStatus,          // PROPOSED | ACTIVE | COMPLETED | CANCELLED
            Double contractValue,
            String currency,
            String description,
            String clientExternalId,          // → ENGAGEMENT_WITH
            String industryExternalId,        // → ENGAGEMENT_IN_INDUSTRY
            String deliveredByOrgUnitExternalId) {} // → DELIVERED_BY

    // ── Responses ───────────────────────────────────────────────────────────

    /** Per-load summary: how many vertices/edges were written, and counts now in the graph. */
    public record UpsertEnterpriseResponse(
            String status,
            String companyExternalId,
            int verticesWritten,
            int edgesWritten,
            List<String> warnings) {}

    /** Read-back verification view: the company plus a count per entity type. */
    public record EnterpriseSummary(
            String companyExternalId,
            String companyName,
            long functionalAreas,
            long industries,
            long clients,
            long engagements,
            long executives,
            long orgUnits,
            long branches,
            long locations) {}
}
