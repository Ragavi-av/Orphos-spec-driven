package io.orphos.eip.registry.controller;

import io.orphos.eip.registry.vo.PlatformServiceDto;
import io.orphos.eip.registry.vo.RegisterServiceRequest;
import io.orphos.eip.registry.service.PlatformServiceRegistry;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Registry API for platform <em>services</em> (tier-model bases such as the
 * nl-query AI gateway). Sibling to {@link PluginRegistryController}, which
 * serves Module-Federation UI plugins.
 */
@RestController
@RequestMapping("/api/platform/services")
public class PlatformServiceController {

    private final PlatformServiceRegistry registry;

    public PlatformServiceController(PlatformServiceRegistry registry) {
        this.registry = registry;
    }

    /**
     * Live view of registered services + their capabilities and heartbeat
     * status. Readable by any authenticated user (agents discover bases here;
     * the admin console renders the tier/health view).
     */
    @GetMapping
    public List<PlatformServiceDto> list() {
        return registry.findAll();
    }

    /**
     * Service self-registration — called by a base at startup, or by an admin.
     * Requires an internal service token (or platform-admin), mirroring the
     * plugin register endpoint.
     */
    @PostMapping("/register")
    @PreAuthorize("hasAuthority('ROLE_service_internal') or hasAuthority('ROLE_platform_admin')")
    public PlatformServiceDto register(@Valid @RequestBody RegisterServiceRequest req) {
        return registry.register(req);
    }
}
