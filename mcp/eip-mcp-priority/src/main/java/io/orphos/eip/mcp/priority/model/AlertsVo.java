package io.orphos.eip.mcp.priority.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The {@code /api/priority/alerts} response. Same {@code {"result": [ ...rows ]}} envelope as
 * {@link SlaVo}, kept as its own type because the two are independent upstream contracts that can
 * diverge.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AlertsVo(List<Map<String, Object>> result) {
}
