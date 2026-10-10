package io.orphos.eip.dataingestion.component;

import io.orphos.eip.dataingestion.repository.MappingRepository;
import io.orphos.eip.dataingestion.repository.TargetWriter;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;
import io.orphos.eip.dataingestion.vo.ZohoMappedRecord;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes one Spring Batch chunk of mapped Zoho rows to their ArcadeDB target — document rows via
 * the existing {@link TargetWriter} (same class the non-batch pipeline uses, so it keeps the
 * resilience/tenant-routing behaviour already built into it), or a batch of graph vertices via
 * {@link GraphTargetWriter} when the mapping targets a VERTEX.
 *
 * <p><b>Known limitation:</b> unlike {@code JobExecutionEngine}, this pipeline does not support
 * edge mappings — only vertex upserts. Zoho's seeded CRM presets don't define edges by default;
 * add edge support here if a Zoho mapping later needs it.</p>
 *
 * <p>Note on "chunk" semantics: Spring Batch's chunk here is a checkpoint/batching boundary, not a
 * real ACID transaction the way it would be against a JDBC target — ArcadeDB writes go through
 * {@code TargetWriter}/{@code ArcadeDbTemplate}'s own circuit-breaker/retry, not a
 * {@code PlatformTransactionManager}-enlisted resource.</p>
 */
@Component
@StepScope
public class ZohoItemWriter implements ItemWriter<ZohoMappedRecord> {

    private final TargetWriter targetWriter;
    private final GraphTargetWriter graphWriter;
    private final MappingView mapping;
    private final boolean upsert;
    private final boolean graphTarget;
    private boolean ensureTypeDone;

    public ZohoItemWriter(TargetWriter targetWriter, GraphTargetWriter graphWriter, MappingRepository mappings,
                          @Value("#{jobParameters['mappingId']}") String mappingId,
                          @Value("#{jobParameters['mergeStrategy']}") String mergeStrategy) {
        this.targetWriter = targetWriter;
        this.graphWriter = graphWriter;
        Map<String, Object> row = mappings.findById(mappingId);
        if (row == null) {
            throw new IllegalStateException("Mapping " + mappingId + " no longer exists");
        }
        this.mapping = mappings.toView(row);
        this.upsert = "UPSERT".equalsIgnoreCase(mergeStrategy);
        this.graphTarget = "VERTEX".equalsIgnoreCase(mapping.targetKind());
        if (!graphTarget) {
            // Idempotent (CREATE ... IF NOT EXISTS) — same call the non-batch pipeline makes
            // before its row loop; done once here since this @StepScope bean is built once
            // per step execution.
            targetWriter.ensureType(mapping.targetClass(), upsert ? mapping.keyTarget() : "");
        }
    }

    @Override
    public void write(Chunk<? extends ZohoMappedRecord> chunk) {
        if (graphTarget) {
            List<GraphTargetWriter.Vertex> batch = new ArrayList<>(chunk.size());
            for (ZohoMappedRecord r : chunk) {
                batch.add(new GraphTargetWriter.Vertex(String.valueOf(r.keyValue()), withoutTenant(r.values())));
            }
            graphWriter.upsertVertices(mapping.targetClass(), !ensureTypeDone, batch);
            ensureTypeDone = true;
            return;
        }
        for (ZohoMappedRecord r : chunk) {
            if (upsert) {
                targetWriter.upsertRow(mapping.targetClass(), mapping.keyTarget(), r.keyValue(), r.values());
            } else {
                targetWriter.insertRow(mapping.targetClass(), r.values());
            }
        }
    }

    /** Mapped values minus the synthetic {@code tenantId} — okg-ingest sets that itself. */
    private static Map<String, Object> withoutTenant(Map<String, Object> values) {
        Map<String, Object> copy = new LinkedHashMap<>(values);
        copy.remove("tenantId");
        return copy;
    }
}
