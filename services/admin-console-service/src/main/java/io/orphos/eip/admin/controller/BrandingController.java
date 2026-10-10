package io.orphos.eip.admin.controller;

import io.orphos.eip.admin.vo.BrandingDtos;
import io.orphos.eip.admin.service.BrandingService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public branding endpoints (no auth required). Used by the login page and
 * the SPA shell to fetch the current logo / theme before the user signs in.
 */
@RestController
@RequestMapping("/api/branding/public")
public class BrandingController {

    private final BrandingService branding;

    public BrandingController(BrandingService branding) {
        this.branding = branding;
    }

    @GetMapping("/active")
    public BrandingDtos.BrandingProfileView active() {
        return branding.getActiveProfile();
    }

    @GetMapping("/active-bundle")
    public BrandingDtos.ActiveBrandingView activeBundle() {
        return branding.getActiveBranding();
    }

    @GetMapping("/asset/{id}")
    public ResponseEntity<byte[]> asset(@PathVariable String id) {
        return branding.getAsset(id)
                .map(a -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, a.contentType())
                        .header(HttpHeaders.CACHE_CONTROL, "public, max-age=3600")
                        .body(a.bytes()))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/preview/{token}/theme", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<BrandingDtos.PreviewRequest> previewTheme(@PathVariable String token) {
        return branding.resolvePreview(token)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
