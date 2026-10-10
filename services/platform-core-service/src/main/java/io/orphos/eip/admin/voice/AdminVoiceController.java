package io.orphos.eip.admin.voice;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin → Voice's view of the voice module. The lifecycle belongs to
 * {@link VoiceModuleReconciler}; this only reports it, and lets the tab ask for
 * an immediate pass right after a toggle instead of waiting for the next tick.
 */
@RestController
@RequestMapping("/api/admin/voice")
@PreAuthorize("hasRole('platform_admin')")
public class AdminVoiceController {

    private final VoiceModuleReconciler reconciler;

    public AdminVoiceController(VoiceModuleReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @GetMapping("/module")
    public VoiceModuleReconciler.State module() {
        return reconciler.inspect();
    }

    @PostMapping("/module/reconcile")
    public VoiceModuleReconciler.State reconcile() {
        return reconciler.reconcile();
    }
}
