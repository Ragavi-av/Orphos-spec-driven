package io.orphos.eip.mcp.priority.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * The {@code /api/priority/sla} response. priority-engine returns SLA metrics as
 * {@code {"result": [ ...rows ]}} where each row is built from ArcadeDB query output, so the rows
 * stay open maps rather than a fixed shape.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SlaVo(List<Map<String, Object>> result) {
}
