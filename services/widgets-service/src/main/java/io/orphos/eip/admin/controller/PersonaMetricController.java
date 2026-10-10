package io.orphos.eip.admin.controller;

import io.orphos.eip.admin.service.PersonaMetricService;
import io.orphos.eip.admin.service.PersonaMetricService.MetricValue;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Widget values sourced from the ontology's metric store, for widgets declared as
 * {@code gov:Metric} → {@code gov:Widget} bindings.
 *
 * <p>Distinct from platform-core's {@code /api/dashboard/metrics/{id}}, which serves
 * data-onboarded (dynamic) widgets. This path serves ontology-declared metrics and
 * applies the persona rule to each value.</p>
 *
 * <p>A refused widget returns <b>200 with {@code permitted: false}</b> rather than
 * 403. The dashboard renders many tiles concurrently; a 403 would be
 * indistinguishable from an auth failure and would trip generic error handling and
 * token-refresh logic in the SPA. Returning a normal response with an explicit flag
 * lets the board show "not available for your role" on that tile while the rest of
 * the board loads — and the number itself is still withheld, which is the part that
 * matters.</p>
 */
@RestController
@RequestMapping("/api/dashboard/persona-metrics")
public class PersonaMetricController {

    private final PersonaMetricService service;

    public PersonaMetricController(PersonaMetricService service) {
        this.service = service;
    }

    /**
     * @param displayCurrency ISO code the reader wants the figure in (their country
     *                        pick in the SPA). Optional, and advisory: when no rate
     *                        covers the observation's period the reported currency
     *                        comes back instead, with {@code fx} null. A tile is
     *                        never blanked because a rate is missing.
     */
    @GetMapping("/{widgetKey}")
    public MetricValue metric(@PathVariable String widgetKey,
                              @RequestParam(required = false) String displayCurrency) {
        return service.latest(widgetKey, displayCurrency);
    }

    /** Widget keys this tenant has metric bindings for — lets the SPA skip the rest. */
    @GetMapping
    public ResponseEntity<List<String>> servable() {
        return service.servableKeys()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(List.of()));
    }
}
