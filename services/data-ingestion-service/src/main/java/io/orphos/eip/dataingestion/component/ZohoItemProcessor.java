package io.orphos.eip.dataingestion.component;

import io.orphos.eip.dataingestion.repository.MappingRepository;
import io.orphos.eip.dataingestion.util.TransformFunctions;
import io.orphos.eip.dataingestion.vo.MappingDtos.ColumnMapping;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;
import io.orphos.eip.dataingestion.vo.MappingDtos.TargetType;
import io.orphos.eip.dataingestion.vo.ZohoMappedRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Applies the run's column mappings/transforms to one raw Zoho row — the same conversion rules
 * {@code JobExecutionEngine.mapRow()} applies for every other source kind, scaled down to what
 * this pipeline needs (kept local rather than sharing that private method, since the two
 * pipelines are intentionally independent per the chosen design).
 *
 * <p>Returning {@code null} is Spring Batch's standard "filter this item out" signal — used here
 * exactly as the existing engine uses it: a blank/unconvertible key value skips the row rather
 * than failing the whole step.</p>
 */
@Component
@StepScope
public class ZohoItemProcessor implements ItemProcessor<Map<String, String>, ZohoMappedRecord> {

    private static final Logger log = LogManager.getLogger(ZohoItemProcessor.class);

    private final MappingView mapping;
    private final String tenantId;
    private final boolean keyed;

    public ZohoItemProcessor(MappingRepository mappings,
                             @Value("#{jobParameters['mappingId']}") String mappingId,
                             @Value("#{jobParameters['tenantId']}") String tenantId,
                             @Value("#{jobParameters['mergeStrategy']}") String mergeStrategy) {
        Map<String, Object> row = mappings.findById(mappingId);
        if (row == null) {
            throw new IllegalStateException("Mapping " + mappingId + " no longer exists");
        }
        this.mapping = mappings.toView(row);
        this.tenantId = tenantId;
        boolean upsert = "UPSERT".equalsIgnoreCase(mergeStrategy);
        boolean graphTarget = "VERTEX".equalsIgnoreCase(mapping.targetKind());
        this.keyed = upsert || graphTarget;   // graph vertices are always keyed by externalId
    }

    @Override
    public ZohoMappedRecord process(Map<String, String> sourceRow) {
        Map<String, Object> values = new LinkedHashMap<>();
        Object keyValue = null;
        for (ColumnMapping m : mapping.mappings()) {
            List<String> sourceValues = new ArrayList<>(m.sources().size());
            for (String src : m.sources()) sourceValues.add(sourceRow.get(src));
            String combined = TransformFunctions.combine(sourceValues, m.transform());
            String transformed = TransformFunctions.apply(combined, m.transform());
            boolean isKey = keyed && m.target().equalsIgnoreCase(mapping.keyTarget());
            Object converted;
            try {
                converted = TransformFunctions.convert(transformed, targetTypeOf(m));
            } catch (Exception e) {
                if (isKey) return null;   // unconvertible key — row skipped, mirrors the existing engine
                log.debug("Value for target '{}' failed to convert and was dropped to null: {}",
                        m.target(), e.getMessage());
                converted = null;
            }
            if (isKey) {
                if (converted == null || String.valueOf(converted).isBlank()) return null;
                keyValue = converted;
            }
            if (converted != null) values.put(m.target(), converted);
        }
        values.put("tenantId", tenantId);
        return new ZohoMappedRecord(keyValue, values);
    }

    private static TargetType targetTypeOf(ColumnMapping m) {
        String raw = m.targetType();
        if (raw == null || raw.isBlank()) return TargetType.STRING;
        try {
            return TargetType.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return TargetType.STRING;
        }
    }
}
