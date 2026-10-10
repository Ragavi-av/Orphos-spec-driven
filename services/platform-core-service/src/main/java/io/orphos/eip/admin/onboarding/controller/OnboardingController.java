package io.orphos.eip.admin.onboarding.controller;

import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.AnalyzeResult;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.CommitRequest;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.CommitResult;
import io.orphos.eip.admin.onboarding.dto.OnboardingDtos.WidgetProposal;
import io.orphos.eip.admin.onboarding.service.OnboardingService;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Admin data-onboarding API (Administration → Data &amp; AI → upload).
 *
 * <p>Secured by the {@code /api/admin/**} rules in SecurityConfig. Operates on
 * the current tenant resolved by TenantResolutionFilter (the acting tenant for
 * platform admins, or the JWT tenant otherwise).</p>
 */
@RestController
@RequestMapping("/api/admin/onboarding")
public class OnboardingController {

    private final OnboardingService service;

    public OnboardingController(OnboardingService service) {
        this.service = service;
    }

    /** Parse + infer + preview. No writes — returns the model for admin review. */
    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AnalyzeResult analyze(@RequestParam("files") List<MultipartFile> files) throws IOException {
        UUID tenantId = TenantContextHolder.requireTenantId();
        return service.analyze(tenantId, files);
    }

    /** Commit the reviewed plan: load data, persist registry + ontology, set dashboard default. */
    @PostMapping("/commit")
    public CommitResult commit(@RequestBody CommitRequest req) {
        UUID tenantId = TenantContextHolder.requireTenantId();
        return service.commit(tenantId, req);
    }

    /** What's already onboarded for this tenant (entities + live row counts + widgets). */
    @GetMapping("/state")
    public Map<String, Object> state() {
        return service.currentState(TenantContextHolder.requireTenantId());
    }

    // ── Manage committed widgets (add / edit / delete) without re-uploading ──

    /** The tenant's committed widgets. */
    @GetMapping("/widgets")
    public List<WidgetProposal> widgets() {
        return service.listWidgets(TenantContextHolder.requireTenantId());
    }

    /** Create a new widget (blank id in the body is fine — one is generated). */
    @PostMapping("/widgets")
    public WidgetProposal createWidget(@RequestBody WidgetProposal widget) {
        return service.saveWidget(TenantContextHolder.requireTenantId(), widget);
    }

    /** Update an existing widget; the path id wins over any id in the body. */
    @PutMapping("/widgets/{id}")
    public WidgetProposal updateWidget(@PathVariable String id, @RequestBody WidgetProposal widget) {
        WidgetProposal w = new WidgetProposal(id, widget.label(), widget.type(), widget.entity(),
                widget.column(), widget.groupBy(), widget.dateColumn(), widget.color(), widget.defaultVisible());
        return service.saveWidget(TenantContextHolder.requireTenantId(), w);
    }

    /** Delete a widget by id. */
    @DeleteMapping("/widgets/{id}")
    public Map<String, Object> deleteWidget(@PathVariable String id) {
        boolean removed = service.deleteWidget(TenantContextHolder.requireTenantId(), id);
        return Map.of("removed", removed);
    }
}
