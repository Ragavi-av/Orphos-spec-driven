package io.orphos.eip.nlquery.component;

import io.orphos.eip.nlquery.component.provider.ChatProvider;
import io.orphos.eip.nlquery.component.provider.ChatProviderRegistry;
import io.orphos.eip.nlquery.component.provider.ProviderAvailabilityService;
import io.orphos.eip.nlquery.constants.AiUsageTypes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a tenant's provider/model assignment for one {@link AiUsageTypes
 * usage type}, re-validating at call time that the assigned provider is still
 * available ({@link ProviderAvailabilityService}): in-house providers must be
 * offered by this build and served by the Ollama runtime, external ones still
 * tenant-enabled with a key. A stale assignment — e.g. the operator dropped a
 * model from OLLAMA_EXTRA_MODELS, the runtime no longer loads it, or the admin
 * disabled the external provider — resolves empty, so callers fall back to the
 * tenant default instead of failing.
 */
@Component
public class UsageAssignmentResolver {

    private static final Logger log = LogManager.getLogger(UsageAssignmentResolver.class);

    private final TenantAiUsageAssignmentStore assignments;
    private final ChatProviderRegistry registry;
    private final ProviderAvailabilityService availability;

    public UsageAssignmentResolver(TenantAiUsageAssignmentStore assignments,
                                   ChatProviderRegistry registry,
                                   ProviderAvailabilityService availability) {
        this.assignments = assignments;
        this.registry = registry;
        this.availability = availability;
    }

    /** A usable assignment: the provider plus the pinned model (if any). */
    public record Resolved(ChatProvider provider, Optional<String> model) {}

    public Optional<Resolved> resolve(UUID tenantId, String usageType) {
        if (tenantId == null || !AiUsageTypes.isKnown(usageType)) return Optional.empty();
        return assignments.read(tenantId, usageType).flatMap(a -> {
            ChatProvider provider = registry.find(a.providerId());
            if (provider == null || !availability.isAvailable(provider, tenantId)) {
                log.debug("Usage assignment for tenant {} type {} points at unavailable provider {} — ignoring",
                        tenantId, usageType, a.providerId());
                return Optional.empty();
            }
            return Optional.of(new Resolved(provider, a.model()));
        });
    }
}
