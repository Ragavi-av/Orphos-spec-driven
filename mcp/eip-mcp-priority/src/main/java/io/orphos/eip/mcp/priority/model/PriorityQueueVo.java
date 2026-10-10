package io.orphos.eip.mcp.priority.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The {@code /api/priority/queue} response: the EPQ-scored queue plus the item count and the
 * scoring version that produced it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PriorityQueueVo(

        List<PriorityItemVo> queue,

        int count,

        /** EPQ scoring version, e.g. "EPQ-v1". */
        String source) {
}
