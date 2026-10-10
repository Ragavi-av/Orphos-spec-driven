package io.orphos.eip.admin.controller;

import io.orphos.eip.admin.repository.BrandingRepository;
import io.orphos.eip.admin.vo.BrandingDtos;
import io.orphos.eip.admin.service.BrandingService;
import io.orphos.eip.admin.util.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Branding management for tenant admins and platform admins. A platform_admin
 * (whose JWT carries no tenant_id) acts on a specific tenant via the
 * {@code X-Tenant-Id} acting-tenant header; {@code BrandingRepository} scopes
 * every read/write to the resolved tenant. Mirrors the SCIM controller's
 * dual-role access.
 */
@RestController
@RequestMapping("/api/admin/branding")
@PreAuthorize("hasAnyRole('admin','platform_admin')")
public class AdminBrandingController {

    private final BrandingService branding;

    public AdminBrandingController(BrandingService branding) {
        this.branding = branding;
    }

    // ----- B1: profile -----
    @GetMapping("/profiles")
    public List<BrandingDtos.BrandingProfileView> profiles() { return branding.listProfiles(); }

    @PutMapping("/profiles")
    public BrandingDtos.BrandingProfileView upsertProfile(@Valid @RequestBody BrandingDtos.BrandingProfileRequest req) {
        return branding.upsertProfile(req, CurrentUser.key());
    }

    @PostMapping("/profiles/{id}/activate")
    public ResponseEntity<Void> activate(@PathVariable String id) {
        branding.activateProfile(id, CurrentUser.key());
        return ResponseEntity.noContent().build();
    }

    // ----- B2 / B5: assets -----
    @PostMapping(value = "/assets", consumes = "multipart/form-data")
    public BrandingDtos.AssetUploadResponse upload(
            @RequestParam("kind") String kind,
            @RequestParam(value = "profileId", required = false) String profileId,
            @RequestPart("file") MultipartFile file) throws IOException {
        return branding.upload(profileId, kind, file.getContentType(), file.getBytes(),
                CurrentUser.key());
    }

    @DeleteMapping("/assets/{id}")
    public ResponseEntity<Void> deleteAsset(@PathVariable String id) {
        branding.deleteAsset(id, CurrentUser.key());
        return ResponseEntity.noContent().build();
    }

    // ----- B3, B4, B6: themes -----
    @GetMapping("/themes")
    public List<BrandingDtos.ThemeView> themes() { return branding.listThemes(); }

    @PutMapping("/themes")
    public BrandingDtos.ThemeView upsertTheme(@Valid @RequestBody BrandingDtos.ThemeRequest req) {
        return branding.upsertTheme(req, CurrentUser.key());
    }

    @DeleteMapping("/themes/{id}")
    public ResponseEntity<Void> deleteTheme(@PathVariable String id) {
        branding.deleteTheme(id, CurrentUser.key());
        return ResponseEntity.noContent().build();
    }

    // ----- UI label overrides (titles & text, per language) -----
    @GetMapping("/labels")
    public List<BrandingDtos.UiLabelView> labels() { return branding.listLabels(); }

    @PutMapping("/labels")
    public BrandingDtos.LabelImportReport upsertLabels(@Valid @RequestBody BrandingDtos.UiLabelBulkRequest req) {
        return branding.upsertLabels(req.labels(), CurrentUser.key());
    }

    @PostMapping(value = "/labels/import", consumes = "multipart/form-data")
    public BrandingDtos.LabelImportReport importLabels(@RequestPart("file") MultipartFile file)
            throws IOException {
        String csv = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        return branding.importLabelsCsv(csv, CurrentUser.key());
    }

    /** Remove all overrides for a language (or every language when lang is omitted). */
    @PostMapping("/labels/restore-defaults")
    public BrandingDtos.LabelImportReport restoreLabelDefaults(
            @RequestParam(value = "lang", required = false) String lang) {
        return branding.restoreLabelDefaults(lang, CurrentUser.key());
    }

    /** Change history for label overrides, newest first. */
    @GetMapping("/labels/history")
    public List<BrandingDtos.UiLabelHistoryView> labelHistory(
            @RequestParam(value = "limit", required = false, defaultValue = "50") int limit) {
        return branding.labelHistory(Math.min(Math.max(limit, 1), 200));
    }

    @GetMapping(value = "/labels/export", produces = "text/csv")
    public ResponseEntity<String> exportLabels() {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"ui-labels.csv\"")
                .body(branding.exportLabelsCsv());
    }

    // ----- B8: validation endpoint (CSS) -----
    @PostMapping(value = "/themes/validate-css", consumes = "text/css")
    public BrandingDtos.ValidationReport validateCss(@RequestBody String css) {
        return branding.validateCss(css);
    }

    // ----- B7: preview -----
    @PostMapping("/themes/preview")
    public BrandingDtos.PreviewToken preview(@RequestBody BrandingDtos.PreviewRequest req) {
        return branding.createPreview(req);
    }

    // ----- Shared platform default branding template -----
    // A platform_admin defines the org-wide default (profile + theme + assets);
    // any tenant admin can then restore their own tenant to it. Writes are
    // platform-admin only; the restore acts on the caller's own tenant, so it
    // stays open to tenant admins (the class-level role gate).

    @GetMapping("/platform-template")
    public BrandingDtos.ActiveBrandingView platformTemplate() {
        return branding.getPlatformTemplate();
    }

    @PutMapping("/platform-template/profile")
    @PreAuthorize("hasRole('platform_admin')")
    public BrandingDtos.BrandingProfileView savePlatformTemplateProfile(
            @Valid @RequestBody BrandingDtos.BrandingProfileRequest req) {
        return branding.savePlatformTemplateProfile(req, CurrentUser.key());
    }

    @PutMapping("/platform-template/theme")
    @PreAuthorize("hasRole('platform_admin')")
    public BrandingDtos.ThemeView savePlatformTemplateTheme(
            @Valid @RequestBody BrandingDtos.ThemeRequest req) {
        return branding.savePlatformTemplateTheme(req, CurrentUser.key());
    }

    @PostMapping(value = "/platform-template/assets", consumes = "multipart/form-data")
    @PreAuthorize("hasRole('platform_admin')")
    public BrandingDtos.AssetUploadResponse uploadPlatformTemplateAsset(
            @RequestParam("kind") String kind,
            @RequestPart("file") MultipartFile file) throws IOException {
        return branding.uploadPlatformTemplateAsset(kind, file.getContentType(), file.getBytes(),
                CurrentUser.key());
    }

    @DeleteMapping("/platform-template/assets/{id}")
    @PreAuthorize("hasRole('platform_admin')")
    public ResponseEntity<Void> deletePlatformTemplateAsset(@PathVariable String id) {
        branding.deletePlatformTemplateAsset(id, CurrentUser.key());
        return ResponseEntity.noContent().build();
    }

    /**
     * Drop the caller's tenant's appearance overrides so it follows the live
     * platform default again. Returns the resulting effective branding.
     */
    @PostMapping("/restore-platform-default")
    public BrandingDtos.ActiveBrandingView restorePlatformDefault() {
        return branding.restorePlatformDefault(CurrentUser.key());
    }
}
