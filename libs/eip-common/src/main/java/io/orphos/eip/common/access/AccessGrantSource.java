package io.orphos.eip.common.access;

import java.util.Map;

/**
 * Supplies the RBAC access map for the current tenant to {@link AccessEnforcementFilter}.
 *
 * <p>Implementations differ per service: the service that owns the store
 * (platform-core) reads it directly; other services fetch it from platform-core's
 * internal endpoint. The shape is a nested map
 * {@code {role → {resourceKey → "allow"|"deny"}}}.</p>
 *
 * <p><b>Availability contract:</b> return a (possibly empty) map when the policy
 * was resolved — an empty map legitimately means "no grants configured". Return
 * {@code null} when the map could NOT be resolved (policy service down, no tenant
 * in scope, fetch error). The filter treats null as "unavailable" and applies the
 * configured fail mode (closed by default; see AccessEnforcementFilter).</p>
 */
@FunctionalInterface
public interface AccessGrantSource {
    Map<String, Map<String, String>> grantsForCurrentTenant();
}
