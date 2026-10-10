package io.orphos.eip.dataingestion.repository;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.core.arcadedb.ArcadeDbTemplate;
import io.orphos.eip.dataingestion.component.IngestAuditSchemaBootstrap;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Writes the classification level + tags onto an {@code IngestDataSource} row
 * (requirement 8.6). Deliberately a separate, single-purpose repository — the
 * data-source CRUD lives in {@code connection/DataSourceRepository} and stays
 * untouched; this only owns the two classification properties added by
 * {@code IngestAuditSchemaBootstrap}.
 */
@Repository
public class DataClassificationRepository {

    private final ArcadeDbTemplate arcade;

    public DataClassificationRepository(ArcadeDbTemplate arcade) {
        this.arcade = arcade;
    }

    public void updateClassification(String dataSourceId, String level, List<String> tags) {
        arcade.commandNoResult(
                "UPDATE IngestDataSource SET classification = :classification, " +
                "classificationTags = :tags, updatedAt = :now " +
                "WHERE tenantId = :tenantId AND id = :id",
                Map.of("classification", level,
                        "tags", tags,
                        "now", Instant.now().toString(),
                        "tenantId", TenantContextHolder.requireTenantId().toString(),
                        "id", dataSourceId));
    }
}
