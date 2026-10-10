package io.orphos.eip.decision.controller;

import io.orphos.eip.common.tenant.TenantContextHolder;
import io.orphos.eip.decision.repository.DecisionRepository.DecisionFilter;
import io.orphos.eip.decision.service.DecisionSearchService;
import io.orphos.eip.decision.service.DecisionSearchService.SearchRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Decision_Search_Engine endpoint (Req 6).
 *
 * <p>Body: {@code {query, mode: KEYWORD|SEMANTIC|HYBRID, limit, filters: {
 * statuses, domains, areas, priorities, detectionSources, dateFrom, dateTo,
 * person, impactArea}}}. Response: ranked results with relevance, snippet and
 * matchedField (for client-side highlighting) plus facet counts.</p>
 */
@RestController
@RequestMapping("/api/decisions/search")
public class DecisionSearchController {

    private final DecisionSearchService search;

    public DecisionSearchController(DecisionSearchService search) {
        this.search = search;
    }

    @PostMapping
    public ResponseEntity<Object> search(@RequestBody(required = false) Map<String, Object> body) {
        String tenantId = TenantContextHolder.requireTenantId().toString();
        Map<String, Object> req = body == null ? Map.of() : body;
        Map<String, Object> filters = asMap(req.get("filters"));
        DecisionFilter filter = new DecisionFilter(
            null,
            asStringList(filters.get("statuses")),
            asStringList(filters.get("domains")),
            asStringList(filters.get("areas")),
            asStringList(filters.get("priorities")),
            asStringList(filters.get("detectionSources")),
            asString(filters.get("dateFrom")),
            asString(filters.get("dateTo")),
            asString(filters.get("person")),
            asString(filters.get("impactArea")));
        SearchRequest searchRequest = new SearchRequest(
            asString(req.get("query")),
            asString(req.get("mode")),
            filter,
            req.get("limit") instanceof Number n ? n.intValue() : 0);
        return ResponseEntity.ok(search.search(tenantId, searchRequest));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }

    private static List<String> asStringList(Object o) {
        if (!(o instanceof List<?> list) || list.isEmpty()) return null;
        return list.stream().map(String::valueOf).filter(s -> !s.isBlank()).toList();
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
