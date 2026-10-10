package io.orphos.eip.admin.dataflow;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Read-only "Ontology Sources / data flow" view: every source system across the
 * mesh (databases, document sources, ingested files, email, communication
 * channels, ontology graph) with the ontology entities it produces (where a real
 * linkage exists) and when it was last refreshed. Aggregated server-side by
 * {@link DataFlowAggregator}.
 *
 * <p>Scoped to the Data group admin roles (Ontology Sources tab). Fine-grained
 * gating on the {@code ontology} resource is applied by the shared access-map
 * filter.</p>
 */
@RestController
@RequestMapping("/api/admin/data-flow")
@PreAuthorize("hasAnyRole('admin','platform_admin','data_ai_admin')")
public class DataFlowController {

    private final DataFlowAggregator aggregator;

    public DataFlowController(DataFlowAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @GetMapping
    public Map<String, Object> dataFlow() {
        return aggregator.aggregate();
    }
}
