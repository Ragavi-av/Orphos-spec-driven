package io.orphos.eip.webbff.model;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the widget board needs fetched, parsed from the {@code /api/web/dashboard} query.
 *
 * <p>The board asks only for the sources its visible tiles read: a reader who hid every
 * finance tile should not pay for a cash-flow query on each load. Ids end up in downstream
 * paths and query strings, so every value is checked against a strict pattern here — the
 * BFF never interpolates a raw browser string into a URL.</p>
 *
 * @param sources         fixed sources to fetch, e.g. {@code sla}; empty means none
 * @param personaIds      ontology-declared widget ids, fetched from widgets-service
 * @param metricIds       data-onboarded widget ids, fetched from platform-core
 * @param displayCurrency ISO 4217 code the reader wants money in, or {@code null}
 */
public record DashboardQuery(Set<String> sources,
                             List<String> personaIds,
                             List<String> metricIds,
                             String displayCurrency) {

    /**
     * Upper bound on calls one request may fan out to. A full board is ~20 tiles; this
     * leaves headroom while stopping a crafted query from turning one request into
     * hundreds of downstream calls on the shared pool.
     */
    public static final int MAX_CALLS = 48;

    private static final Pattern WIDGET_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");

    public DashboardQuery {
        sources = Collections.unmodifiableSet(new LinkedHashSet<>(sources));
        personaIds = List.copyOf(personaIds);
        metricIds = List.copyOf(metricIds);
    }

    /**
     * Parses and validates the raw query parameters.
     *
     * @param knownSources the fixed source names the catalog can serve
     * @throws ResponseStatusException 400 on an unknown source, malformed id or currency,
     *                                 or a request above {@link #MAX_CALLS}
     */
    public static DashboardQuery parse(String sources, String persona, String metrics,
                                       String displayCurrency, Set<String> knownSources) {
        Set<String> requested = new LinkedHashSet<>(split(sources));
        for (String s : requested) {
            if (!knownSources.contains(s)) {
                throw badRequest("unknown source '" + s + "'");
            }
        }
        List<String> personaIds = ids(persona, "persona");
        List<String> metricIds = ids(metrics, "metrics");

        String currency = displayCurrency == null || displayCurrency.isBlank() ? null : displayCurrency.trim();
        if (currency != null && !CURRENCY.matcher(currency).matches()) {
            throw badRequest("displayCurrency must be an ISO 4217 code");
        }
        if (requested.size() + personaIds.size() + metricIds.size() > MAX_CALLS) {
            throw badRequest("at most " + MAX_CALLS + " sources per request");
        }
        return new DashboardQuery(requested, personaIds, metricIds, currency);
    }

    private static List<String> ids(String raw, String param) {
        List<String> out = new ArrayList<>();
        for (String id : split(raw)) {
            if (!WIDGET_ID.matcher(id).matches()) {
                throw badRequest("malformed id in '" + param + "'");
            }
            if (!out.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out;
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
