package io.orphos.eip.admin.dbconnections.service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The set of services that consume ArcadeDB and can therefore be assigned a
 * connection in the DB-connections admin screen, mapped to the Docker container
 * name the operator restarts to apply a change.
 *
 * <p>Keys are {@code spring.application.name} (== the Maven module name) — the
 * same identity the bootstrap resolver
 * ({@code ConnectionRegistryEnvironmentPostProcessor}) uses to look up its
 * assignment. Values mirror the container names in
 * {@code AdminServicesController.META}.</p>
 */
public final class KnownArcadeServices {

    private KnownArcadeServices() {}

    private static final Map<String, String> SERVICE_TO_CONTAINER = build();

    private static Map<String, String> build() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("platform-core-service",      "eip-platform-core");
        m.put("admin-console-service",      "eip-admin-console");
        m.put("widgets-service",            "eip-widgets");
        m.put("platform-registry-service",  "eip-platform-registry");
        m.put("okg-ingest-service",         "eip-okg-ingest");
        m.put("okg-reasoning-service",      "eip-okg-reasoning");
        m.put("graph-proxy-service",        "eip-graph-proxy");
        m.put("nl-query-service",           "eip-nl-query");
        m.put("priority-engine-service",    "eip-priority-engine");
        m.put("briefing-service",           "eip-briefing");
        m.put("agent-orchestrator-service", "eip-agents");
        m.put("decision-log-service",       "eip-decision-log");
        m.put("notification-service",       "eip-notification");
        m.put("doc-ingest-service",         "eip-doc-ingest");
        m.put("mail-insight-service",       "eip-mail-insight");
        m.put("data-ingestion-service",     "eip-data-ingestion");
        return Map.copyOf(m);
    }

    /** Ordered service-id → container-name map (immutable). */
    public static Map<String, String> all() {
        return SERVICE_TO_CONTAINER;
    }

    public static boolean isKnown(String serviceId) {
        return SERVICE_TO_CONTAINER.containsKey(serviceId);
    }

    public static String container(String serviceId) {
        return SERVICE_TO_CONTAINER.get(serviceId);
    }
}
