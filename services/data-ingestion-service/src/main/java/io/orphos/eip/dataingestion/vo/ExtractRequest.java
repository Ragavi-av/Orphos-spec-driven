package io.orphos.eip.dataingestion.vo;

import io.orphos.eip.dataingestion.dao.SourceExtractor;
import io.orphos.eip.dataingestion.vo.MappingDtos.MappingView;

import java.util.Map;

/**
 * Everything a {@link SourceExtractor} needs to open a read cursor for one run.
 *
 * @param source      the stored {@code IngestDataSource} row
 * @param mapping     the mapping being executed (source table/object + column mappings)
 * @param incremental INCREMENTAL load mode → filter/track by the mapping's incremental column
 * @param watermark   the job's current high-water mark ("" on the first incremental run)
 */
public record ExtractRequest(
        Map<String, Object> source,
        MappingView mapping,
        boolean incremental,
        String watermark) {}
