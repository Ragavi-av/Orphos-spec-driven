package io.orphos.eip.dataingestion.dao;

import io.orphos.eip.dataingestion.component.JobExecutionEngine;
import io.orphos.eip.dataingestion.vo.DataSourceDtos.SourceKind;
import io.orphos.eip.dataingestion.vo.ExtractRequest;

/**
 * Strategy for reading rows out of a source kind. One Spring bean per family
 * (JDBC databases, HTTP/ERP APIs); {@code JobExecutionEngine} picks the one that
 * {@link #supports(SourceKind)} the run's source and iterates the returned
 * {@link RowCursor}. The transform → target-write → progress → retry loop
 * downstream is source-agnostic, so adding a source family means adding an
 * extractor, nothing else.
 */
public interface SourceExtractor {

    boolean supports(SourceKind kind);

    /** Open a streaming cursor for the run. Caller owns closing it. */
    RowCursor open(ExtractRequest request);
}
