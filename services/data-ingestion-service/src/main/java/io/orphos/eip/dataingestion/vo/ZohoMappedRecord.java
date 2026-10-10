package io.orphos.eip.dataingestion.vo;

import java.util.Map;

/**
 * One Zoho row after {@code ZohoItemProcessor} applies the mapping's column transforms — the item
 * type between the processor and {@code ZohoItemWriter}. Mirrors {@code JobExecutionEngine}'s
 * private {@code MappedRow}, kept as its own type since the two pipelines are intentionally
 * independent.
 */
public record ZohoMappedRecord(Object keyValue, Map<String, Object> values) {
}
