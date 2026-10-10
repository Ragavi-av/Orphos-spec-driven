package io.orphos.eip.admin.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.orphos.eip.admin.component.AssetValidator;
import io.orphos.eip.admin.component.CssSanitizer;
import io.orphos.eip.admin.service.AuditService;
import io.orphos.eip.admin.util.Countries;
import io.orphos.eip.admin.util.LabelCsv;
import io.orphos.eip.admin.vo.BrandingDtos;
import io.orphos.eip.admin.repository.BrandingRepository;
import io.orphos.eip.admin.util.Ids;
import io.orphos.eip.common.tenant.TenantContext;
import io.orphos.eip.common.tenant.TenantContextHolder;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;

/**
 * Orchestrates B1..B8 around BrandingRepository + sanitisers.
 *
 * <p>Preview tokens are kept in-process (Caffeine) - they are short-lived
 * (5 min) one-time keys binding a non-persisted draft to a token an
 * unauthenticated preview iframe can use to fetch the draft theme.</p>
 */
@Service
public class BrandingService {

    private final BrandingRepository repo;
    private final CssSanitizer cssSanitizer;
    private final AssetValidator assetValidator;
    private final AuditService audit;

    private final Cache<String, BrandingDtos.PreviewRequest> previewCache =
            Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofMinutes(5))
                    .maximumSize(200)
                    .build();

    public BrandingService(BrandingRepository repo, CssSanitizer cssSanitizer,
                           AssetValidator assetValidator, AuditService audit) {
        this.repo = repo;
        this.cssSanitizer = cssSanitizer;
        this.assetValidator = assetValidator;
        this.audit = audit;
    }

    // ----- B1: profile CRUD -----
    public List<BrandingDtos.BrandingProfileView> listProfiles() {
        return repo.listProfiles().stream().map(BrandingService::toProfileView).toList();
    }

    /** The effective profile — see {@link #getActiveBranding()}. */
    public BrandingDtos.BrandingProfileView getActiveProfile() {
        return resolveEffective().profile();
    }

    /**
     * The appearance the current tenant actually renders with:
     * Platform Default → Tenant Override. Nothing is copied into a tenant when
     * it is created, so a brand-new tenant resolves entirely to the platform
     * default, and a later platform-default change reaches every field the
     * tenant has not overridden on its next read.
     */
    public BrandingDtos.ActiveBrandingView getActiveBranding() {
        Effective e = resolveEffective();
        // Label overrides apply even without a saved profile — a tenant may
        // rename UI text before ever touching the profile form. Without a
        // tenant (pre-login) there are no overrides to apply.
        Map<String, Map<String, String>> labels =
                TenantContextHolder.current().isPresent() ? labelsByLanguage() : Map.of();
        return new BrandingDtos.ActiveBrandingView(e.profile(), e.theme(), labels,
                e.overriddenFields().isEmpty() ? SOURCE_PLATFORM_DEFAULT : SOURCE_CUSTOM,
                e.overriddenFields());
    }

    public BrandingDtos.BrandingProfileView upsertProfile(BrandingDtos.BrandingProfileRequest req,
                                                          String actorKey) {
        Map<String, Object> p = new HashMap<>();
        p.put("name", req.name());
        p.put("productName", req.productName() == null ? "" : req.productName());
        p.put("tagline", req.tagline() == null ? "" : req.tagline());
        // The primary country is the single locale setting: language, zone,
        // currency and notation are DERIVED from it below rather than trusted
        // from the request, so a caller cannot persist a combination the country
        // does not imply (Japan with rupee tiles). Unknown codes normalise to ""
        // — see Countries.normalize.
        String country = Countries.normalize(req.country());
        Countries.Country resolved = Countries.byCode(country).orElse(null);
        p.put("country", country);
        p.put("activeThemeId", req.activeThemeId() == null ? "" : req.activeThemeId());
        p.put("logoAssetId", req.logoAssetId() == null ? "" : req.logoAssetId());
        p.put("backgroundAssetId", req.backgroundAssetId() == null ? "" : req.backgroundAssetId());
        p.put("faviconAssetId", req.faviconAssetId() == null ? "" : req.faviconAssetId());
        p.put("fontFamily", req.fontFamily() == null ? "" : req.fontFamily());
        // Normalise to the supported range; anything else (incl. null) becomes
        // 0, the persisted "unset" marker that toProfileView maps back to null.
        p.put("fontSize", normalizeFontSize(req.fontSize()));
        // With a country, all four come from the catalog. Without one, the
        // request's own values still apply — that is the platform-default
        // template, and every profile saved before the country existed.
        //
        // They are persisted alongside the country rather than resolved on read
        // so the rest of the platform keeps reading the plain fields it always
        // has; only this method knows the catalog.
        if (resolved != null) {
            p.put("defaultLanguage", resolved.language());
            p.put("timezone", resolved.timezone());
            p.put("currency", resolved.currency());
            p.put("numberLocale", resolved.numberLocale());
        } else {
            p.put("defaultLanguage", req.defaultLanguage() == null ? "" : req.defaultLanguage());
            // Blank / unknown zone ids are persisted as "" so the SPA falls back
            // to the viewer's browser time zone.
            p.put("timezone", normalizeTimezone(req.timezone()));
            // Blank / non-ISO codes are persisted as "" so the SPA falls back to
            // the platform default currency.
            p.put("currency", normalizeCurrency(req.currency()));
            // No country, no workspace notation to impose: the SPA formats in
            // whatever language the reader has.
            p.put("numberLocale", "");
        }
        // Header status-dot thresholds. Absent/blank falls back to the system
        // defaults so the dot always has a sensible band set to colour against.
        p.put("statusGreen", req.statusGreen() == null ? 15 : req.statusGreen());
        p.put("statusBlue",  req.statusBlue()  == null ? 30 : req.statusBlue());
        p.put("statusAmber", req.statusAmber() == null ? 50 : req.statusAmber());
        // A theme / asset reference must belong to the scope being saved. The
        // tenant form is populated from the EFFECTIVE branding, so it carries
        // the platform default's ids while the tenant inherits; persisting them
        // would pin today's default instead of following it.
        dropForeignReferences(p);
        if (!inPlatformTemplateScope()) {
            inheritWhereEqualToPlatformDefault(p);
        }
        // Reuse the existing profile rather than spawning a new row on every save:
        // the admin UI edits a single active profile, so resolve its id (the
        // active one, falling back to a same-named profile). Without this, each
        // save INSERTed a fresh row and left the tenant with duplicate profiles.
        repo.findActiveProfile()
                .or(() -> repo.findProfileByName(req.name()))
                .map(m -> Objects.toString(m.get("id"), null))
                .ifPresent(existingId -> p.put("id", existingId));
        // Persisted profiles must be active so getActiveProfile() (the load path)
        // can find them on the next page load — previously they were always
        // active=false, so saved branding silently reverted on reload.
        p.put("active", true);
        String id = repo.saveProfile(p);
        repo.setActive(id);
        audit.record(actorKey, "BRANDING_PROFILE_UPSERT", req.name(),
                Map.of("themeId", p.get("activeThemeId")));
        return repo.findProfile(id).map(BrandingService::toProfileView)
                .orElseThrow(() -> new IllegalStateException("profile not persisted"));
    }

    public void activateProfile(String profileId, String actorKey) {
        if (repo.findProfile(profileId).isEmpty()) {
            throw new IllegalArgumentException("unknown profile");
        }
        repo.setActive(profileId);
        audit.record(actorKey, "BRANDING_PROFILE_ACTIVATE", profileId, Map.of());
    }

    // ----- B2 + B5: asset upload -----
    public BrandingDtos.AssetUploadResponse upload(String profileId, String kind,
                                                   String contentType, byte[] bytes,
                                                   String actorKey) {
        assetValidator.validate(kind, contentType, bytes);
        String id = repo.insertAsset(profileId, kind, contentType, bytes.length, bytes);
        audit.record(actorKey, "BRANDING_ASSET_UPLOAD", id,
                Map.of("kind", kind, "size", bytes.length, "mime", contentType));
        return new BrandingDtos.AssetUploadResponse(id, contentType, bytes.length);
    }

    public Optional<Asset> getAsset(String id) {
        return repo.findAsset(id).map(row -> {
            String b64 = Objects.toString(row.get("data"), "");
            byte[] bytes = Base64.getDecoder().decode(b64);
            return new Asset(Objects.toString(row.get("contentType"), "application/octet-stream"), bytes);
        });
    }

    public void deleteAsset(String id, String actorKey) {
        repo.deleteAsset(id);
        audit.record(actorKey, "BRANDING_ASSET_DELETE", id, Map.of());
    }

    // ----- B3 + B4 + B6: theme + colors + custom CSS -----
    public List<BrandingDtos.ThemeView> listThemes() {
        return repo.listThemes().stream().map(BrandingService::toThemeView).toList();
    }

    public BrandingDtos.ThemeView upsertTheme(BrandingDtos.ThemeRequest req, String actorKey) {
        CssSanitizer.Result san = cssSanitizer.sanitize(req.customCss());
        if (!san.ok()) {
            throw new IllegalArgumentException("CSS rejected: " + san.issues());
        }
        Map<String, Object> t = new HashMap<>();
        t.put("name", req.name());
        t.put("mode", req.mode() == null ? "light" : req.mode());
        t.put("colors", req.colors());
        t.put("sanitizedCss", san.sanitized());
        String id = repo.saveTheme(t);
        audit.record(actorKey, "BRANDING_THEME_UPSERT", id,
                Map.of("name", req.name(), "issues", san.issues()));
        return repo.findTheme(id).map(BrandingService::toThemeView)
                .orElseThrow(() -> new IllegalStateException("theme not persisted"));
    }

    public BrandingDtos.ValidationReport validateCss(String css) {
        CssSanitizer.Result san = cssSanitizer.sanitize(css);
        return new BrandingDtos.ValidationReport(san.ok(),
                san.issues().isBlank() ? List.of() : List.of(san.issues().split("; ")),
                Map.of("sanitizedLength", san.sanitized().length()));
    }

    public void deleteTheme(String id, String actorKey) {
        repo.deleteTheme(id);
        audit.record(actorKey, "BRANDING_THEME_DELETE", id, Map.of());
    }

    // ----- UI label overrides -----
    public List<BrandingDtos.UiLabelView> listLabels() {
        return repo.listLabels().stream().map(BrandingService::toLabelView).toList();
    }

    /** Overrides grouped by language, as consumed by the SPA's i18n layer. */
    public Map<String, Map<String, String>> labelsByLanguage() {
        Map<String, Map<String, String>> byLang = new TreeMap<>();
        for (Map<String, Object> row : repo.listLabels()) {
            String lang = Objects.toString(row.getOrDefault("lang", ""), "");
            String key = Objects.toString(row.getOrDefault("labelKey", ""), "");
            String value = Objects.toString(row.getOrDefault("value", ""), "");
            if (lang.isBlank() || key.isBlank()) continue;
            byLang.computeIfAbsent(lang, l -> new TreeMap<>()).put(key, value);
        }
        return byLang;
    }

    /**
     * Bulk upsert. A blank value removes the override so the SPA falls back to
     * its built-in default — the same contract the CSV import uses. Every
     * effective change lands one UiLabelHistory row (old → new, actor, source).
     */
    public BrandingDtos.LabelImportReport upsertLabels(List<BrandingDtos.UiLabelEntry> entries,
                                                       String actorKey) {
        return applyLabelEntries(entries, actorKey, "manual");
    }

    private BrandingDtos.LabelImportReport applyLabelEntries(List<BrandingDtos.UiLabelEntry> entries,
                                                             String actorKey, String source) {
        int upserted = 0, removed = 0, skipped = 0;
        for (BrandingDtos.UiLabelEntry e : entries == null ? List.<BrandingDtos.UiLabelEntry>of() : entries) {
            String oldValue = repo.findLabelValue(e.lang(), e.labelKey());
            if (e.value() == null || e.value().isBlank()) {
                if (repo.deleteLabel(e.lang(), e.labelKey())) {
                    repo.insertLabelHistory(e.lang(), e.labelKey(), oldValue, "", actorKey, source);
                    removed++;
                } else {
                    skipped++;
                }
            } else if (e.value().equals(oldValue)) {
                skipped++;   // no-op — don't pollute history with identical writes
            } else {
                repo.upsertLabel(e.lang(), e.labelKey(), e.value());
                repo.insertLabelHistory(e.lang(), e.labelKey(), oldValue, e.value(), actorKey, source);
                upserted++;
            }
        }
        audit.record(actorKey, "BRANDING_LABELS_UPSERT", source,
                Map.of("upserted", upserted, "removed", removed, "skipped", skipped));
        return new BrandingDtos.LabelImportReport(upserted, removed, skipped, List.of());
    }

    /**
     * Remove every override for {@code lang} (or ALL languages when blank) so
     * the SPA falls back to the built-in defaults. Each removal is recorded in
     * the history, so a restore is itself revertable from there.
     */
    public BrandingDtos.LabelImportReport restoreLabelDefaults(String lang, String actorKey) {
        int removed = 0;
        for (Map<String, Object> row : repo.listLabels()) {
            String rowLang = Objects.toString(row.getOrDefault("lang", ""), "");
            String key = Objects.toString(row.getOrDefault("labelKey", ""), "");
            if (rowLang.isBlank() || key.isBlank()) continue;
            if (lang != null && !lang.isBlank() && !rowLang.equals(lang)) continue;
            String oldValue = Objects.toString(row.getOrDefault("value", ""), "");
            if (repo.deleteLabel(rowLang, key)) {
                repo.insertLabelHistory(rowLang, key, oldValue, "", actorKey, "restore");
                removed++;
            }
        }
        audit.record(actorKey, "BRANDING_LABELS_RESTORE_DEFAULTS",
                lang == null || lang.isBlank() ? "all" : lang, Map.of("removed", removed));
        return new BrandingDtos.LabelImportReport(0, removed, 0, List.of());
    }

    public List<BrandingDtos.UiLabelHistoryView> labelHistory(int limit) {
        return repo.listLabelHistory(limit).stream()
                .map(r -> new BrandingDtos.UiLabelHistoryView(
                        Objects.toString(r.getOrDefault("labelKey", "")),
                        Objects.toString(r.getOrDefault("lang", "")),
                        Objects.toString(r.getOrDefault("oldValue", "")),
                        Objects.toString(r.getOrDefault("newValue", "")),
                        Objects.toString(r.getOrDefault("changedBy", "")),
                        Objects.toString(r.getOrDefault("source", "")),
                        parseInstant(r.get("changedAt"))))
                .toList();
    }

    private static final java.util.regex.Pattern LABEL_KEY =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]{1,200}$");
    private static final java.util.regex.Pattern LABEL_LANG =
            java.util.regex.Pattern.compile("^[a-z]{2}(-[A-Za-z0-9]{2,8})?$");

    /**
     * CSV bulk import. Expected columns: {@code key,language,value} (a
     * {@code default} column from the exported template is ignored). Rows with
     * a blank value remove the override; invalid rows are reported, valid ones
     * still apply.
     */
    public BrandingDtos.LabelImportReport importLabelsCsv(String csv, String actorKey) {
        LabelCsv.ParseResult parsed = LabelCsv.parse(csv);
        List<String> errors = new ArrayList<>(parsed.errors());
        int upserted = 0, removed = 0, skipped = 0;
        for (LabelCsv.Row row : parsed.rows()) {
            if (!LABEL_KEY.matcher(row.key()).matches()) {
                errors.add("Line " + row.lineNumber() + ": invalid key '" + row.key() + "'");
                continue;
            }
            if (!LABEL_LANG.matcher(row.language()).matches()) {
                errors.add("Line " + row.lineNumber() + ": invalid language '" + row.language() + "'");
                continue;
            }
            String oldValue = repo.findLabelValue(row.language(), row.key());
            if (row.value() == null || row.value().isBlank()) {
                if (repo.deleteLabel(row.language(), row.key())) {
                    repo.insertLabelHistory(row.language(), row.key(), oldValue, "", actorKey, "csv");
                    removed++;
                } else {
                    skipped++;
                }
            } else if (row.value().length() > 2000) {
                errors.add("Line " + row.lineNumber() + ": value exceeds 2000 characters");
            } else if (row.value().equals(oldValue)) {
                skipped++;   // unchanged — keep history meaningful
            } else {
                repo.upsertLabel(row.language(), row.key(), row.value());
                repo.insertLabelHistory(row.language(), row.key(), oldValue, row.value(), actorKey, "csv");
                upserted++;
            }
        }
        audit.record(actorKey, "BRANDING_LABELS_IMPORT_CSV", "csv",
                Map.of("upserted", upserted, "removed", removed,
                        "skipped", skipped, "errors", errors.size()));
        return new BrandingDtos.LabelImportReport(upserted, removed, skipped, errors);
    }

    /** Current overrides as CSV (key,language,value) — a backup/restore companion to the import. */
    public String exportLabelsCsv() {
        List<LabelCsv.Row> rows = repo.listLabels().stream()
                .map(r -> new LabelCsv.Row(0,
                        Objects.toString(r.getOrDefault("labelKey", ""), ""),
                        Objects.toString(r.getOrDefault("lang", ""), ""),
                        Objects.toString(r.getOrDefault("value", ""), "")))
                .toList();
        return LabelCsv.write(rows);
    }

    // ----- B7: preview -----
    public BrandingDtos.PreviewToken createPreview(BrandingDtos.PreviewRequest req) {
        String token = Ids.newId();
        previewCache.put(token, req);
        return new BrandingDtos.PreviewToken(token, Instant.now().plus(Duration.ofMinutes(5)));
    }

    public Optional<BrandingDtos.PreviewRequest> resolvePreview(String token) {
        return Optional.ofNullable(previewCache.getIfPresent(token));
    }

    // ----- Shared platform default branding template -----
    //
    // The template is ordinary branding (profile + theme + assets) stored under
    // a reserved pseudo-tenant. Real tenants get random v4 UUIDs, so the
    // all-zero id below never collides with one, and the template's rows sit
    // beside real tenants' rows without breaking the (tenantId, name) UNIQUE
    // indexes. Every template read/write runs inside this context so the
    // tenant-scoped repository transparently targets the reserved tenant.
    private static final UUID PLATFORM_TEMPLATE_TENANT =
            UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final TenantContext PLATFORM_TEMPLATE_CTX =
            TenantContext.of(PLATFORM_TEMPLATE_TENANT, "platform-template");

    private <T> T inPlatformScope(Supplier<T> work) {
        return TenantContextHolder.callWith(PLATFORM_TEMPLATE_CTX, work);
    }

    private static String assetKey(String kind) {
        return switch (kind == null ? "" : kind) {
            case "logo"       -> "logoAssetId";
            case "background" -> "backgroundAssetId";
            case "favicon"    -> "faviconAssetId";
            default           -> null;
        };
    }

    /** The current platform default template (active profile + its theme). */
    public BrandingDtos.ActiveBrandingView getPlatformTemplate() {
        return inPlatformScope(() -> {
            BrandingDtos.BrandingProfileView profile = ownActiveProfile();
            return new BrandingDtos.ActiveBrandingView(profile, ownTheme(profile), Map.of(),
                    SOURCE_PLATFORM_DEFAULT, List.of());
        });
    }

    public BrandingDtos.BrandingProfileView savePlatformTemplateProfile(
            BrandingDtos.BrandingProfileRequest req, String actorKey) {
        return inPlatformScope(() -> upsertProfile(req, actorKey));
    }

    /**
     * Save the template's theme and keep the template profile pointed at it, so
     * the template always resolves exactly one theme (there is no theme picker
     * on the platform-default form).
     */
    public BrandingDtos.ThemeView savePlatformTemplateTheme(
            BrandingDtos.ThemeRequest req, String actorKey) {
        return inPlatformScope(() -> {
            BrandingDtos.ThemeView theme = upsertTheme(req, actorKey);
            Map<String, Object> prof = templateProfileRow();
            prof.put("activeThemeId", theme.id());
            prof.put("active", true);
            repo.setActive(repo.saveProfile(prof));
            return theme;
        });
    }

    public BrandingDtos.AssetUploadResponse uploadPlatformTemplateAsset(
            String kind, String contentType, byte[] bytes, String actorKey) {
        return inPlatformScope(() -> {
            BrandingDtos.AssetUploadResponse res = upload(null, kind, contentType, bytes, actorKey);
            String key = assetKey(kind);
            if (key != null) {
                Map<String, Object> prof = templateProfileRow();
                prof.put(key, res.id());
                prof.put("active", true);
                repo.setActive(repo.saveProfile(prof));
            }
            return res;
        });
    }

    public void deletePlatformTemplateAsset(String id, String actorKey) {
        inPlatformScope(() -> {
            deleteAsset(id, actorKey);
            repo.findActiveProfile().ifPresent(prof -> {
                boolean changed = false;
                for (String key : ASSET_KEYS) {
                    if (id.equals(Objects.toString(prof.get(key), ""))) { prof.put(key, ""); changed = true; }
                }
                if (changed) { prof.put("active", true); repo.setActive(repo.saveProfile(prof)); }
            });
            return null;
        });
    }

    /** The template's active profile row, or a fresh seed if none exists yet. */
    private Map<String, Object> templateProfileRow() {
        return repo.findActiveProfile().orElseGet(() -> {
            Map<String, Object> seed = new HashMap<>();
            seed.put("name", "platform-default");
            seed.put("active", true);
            return seed;
        });
    }

    /**
     * Reset the CURRENT tenant (the request's acting tenant) to the platform
     * default by deleting its appearance overrides — profile, themes and asset
     * binaries. Nothing is copied: the tenant then resolves through
     * {@link #getActiveBranding()} to the live platform default, so later
     * platform-default edits keep reaching it until it customises again.
     * UI label overrides are left alone; they have their own restore.
     */
    public BrandingDtos.ActiveBrandingView restorePlatformDefault(String actorKey) {
        if (inPlatformTemplateScope()) {
            throw new IllegalStateException("The platform default cannot be reset to itself.");
        }
        String tenantId = TenantContextHolder.requireTenantId().toString();
        List<String> removed = resolveEffective().overriddenFields();
        repo.deleteAllAppearance();
        audit.record(actorKey, "BRANDING_RESTORE_PLATFORM_DEFAULT", tenantId,
                Map.of("overridesRemoved", removed));
        return getActiveBranding();
    }

    // ----- Effective branding: Platform Default -> Tenant Override -----
    //
    // A tenant stores only what it overrides; a blank field (0 for the numeric
    // ones, no row at all for a brand-new tenant) means "inherit". The merge
    // happens on every read, never by copying, which is what lets a
    // platform-default change reach every tenant still using it.

    static final String SOURCE_PLATFORM_DEFAULT = "platform-default";
    static final String SOURCE_CUSTOM = "custom";

    private static final List<String> ASSET_KEYS =
            List.of("logoAssetId", "backgroundAssetId", "faviconAssetId");

    private record Effective(BrandingDtos.BrandingProfileView profile,
                             BrandingDtos.ThemeView theme,
                             List<String> overriddenFields) {}

    private static boolean inPlatformTemplateScope() {
        return TenantContextHolder.current()
                .map(c -> PLATFORM_TEMPLATE_TENANT.equals(c.tenantId()))
                .orElse(false);
    }

    /** The current scope's own active profile, with nothing inherited. */
    private BrandingDtos.BrandingProfileView ownActiveProfile() {
        return repo.findActiveProfile().map(BrandingService::toProfileView).orElse(null);
    }

    /** The current scope's own theme that {@code profile} points at, or null. */
    private BrandingDtos.ThemeView ownTheme(BrandingDtos.BrandingProfileView profile) {
        if (profile == null || isBlank(profile.activeThemeId())) return null;
        return repo.findTheme(profile.activeThemeId()).map(BrandingService::toThemeView).orElse(null);
    }

    private Effective resolveEffective() {
        // No tenant resolved (the login page before sign-in, a host this
        // service cannot map): show the platform default rather than failing.
        if (TenantContextHolder.current().isEmpty()) {
            BrandingDtos.ActiveBrandingView platform = getPlatformTemplate();
            return new Effective(mergeOverDefault(null, platform.profile()), platform.theme(), List.of());
        }
        BrandingDtos.BrandingProfileView own = ownActiveProfile();
        BrandingDtos.ThemeView ownTheme = ownTheme(own);
        if (inPlatformTemplateScope()) {
            return new Effective(own, ownTheme, List.of());
        }
        BrandingDtos.ActiveBrandingView platform = getPlatformTemplate();
        return new Effective(
                mergeOverDefault(own, platform.profile()),
                ownTheme != null ? ownTheme : platform.theme(),
                overriddenFields(own, ownTheme));
    }

    /** Tenant fields win where set; every blank one falls through to {@code base}. */
    static BrandingDtos.BrandingProfileView mergeOverDefault(BrandingDtos.BrandingProfileView own,
                                                            BrandingDtos.BrandingProfileView base) {
        if (base == null) return own;
        if (own == null) {
            // No tenant row yet: the default itself, but with no id (there is
            // no tenant profile to address), a tenant-neutral name, and no
            // tenant theme selected.
            return new BrandingDtos.BrandingProfileView(
                    null, "default", base.productName(), base.tagline(), base.country(),
                    base.defaultLanguage(), "", base.logoAssetId(), base.backgroundAssetId(),
                    base.faviconAssetId(), base.fontFamily(), base.fontSize(), base.timezone(),
                    base.currency(), base.numberLocale(), base.updatedAt(),
                    base.statusGreen(), base.statusBlue(), base.statusAmber());
        }
        // Country, zone, currency and notation must agree (see upsertProfile),
        // so they are inherited as one group rather than field by field.
        BrandingDtos.BrandingProfileView locale = hasOwnLocale(own) ? own : base;
        return new BrandingDtos.BrandingProfileView(
                own.id(), own.name(),
                pick(own.productName(), base.productName()),
                pick(own.tagline(), base.tagline()),
                locale.country(),
                pick(own.defaultLanguage(), base.defaultLanguage()),
                own.activeThemeId(),
                pick(own.logoAssetId(), base.logoAssetId()),
                pick(own.backgroundAssetId(), base.backgroundAssetId()),
                pick(own.faviconAssetId(), base.faviconAssetId()),
                pick(own.fontFamily(), base.fontFamily()),
                own.fontSize() != null ? own.fontSize() : base.fontSize(),
                locale.timezone(), locale.currency(), locale.numberLocale(),
                own.updatedAt(),
                own.statusGreen() != null ? own.statusGreen() : base.statusGreen(),
                own.statusBlue()  != null ? own.statusBlue()  : base.statusBlue(),
                own.statusAmber() != null ? own.statusAmber() : base.statusAmber());
    }

    /** What the tenant has customised; empty means it is fully on the platform default. */
    static List<String> overriddenFields(BrandingDtos.BrandingProfileView own,
                                         BrandingDtos.ThemeView ownTheme) {
        List<String> out = new ArrayList<>();
        if (own != null) {
            if (!isBlank(own.productName())) out.add("productName");
            if (!isBlank(own.tagline())) out.add("tagline");
            if (hasOwnLocale(own)) out.add("country");
            else if (!isBlank(own.defaultLanguage())) out.add("defaultLanguage");
            if (!isBlank(own.logoAssetId())) out.add("logo");
            if (!isBlank(own.backgroundAssetId())) out.add("background");
            if (!isBlank(own.faviconAssetId())) out.add("favicon");
            if (!isBlank(own.fontFamily())) out.add("fontFamily");
            if (own.fontSize() != null) out.add("fontSize");
            if (own.statusGreen() != null || own.statusBlue() != null || own.statusAmber() != null) {
                out.add("statusThresholds");
            }
        }
        if (ownTheme != null) out.add("theme");
        return out;
    }

    private static boolean hasOwnLocale(BrandingDtos.BrandingProfileView p) {
        return !isBlank(p.country()) || !isBlank(p.timezone()) || !isBlank(p.currency());
    }

    /**
     * Blank every theme / asset reference the current scope does not own, so a
     * form populated from the effective branding cannot pin the platform
     * default's ids (or reference another tenant's rows) as an override.
     */
    private void dropForeignReferences(Map<String, Object> p) {
        for (String key : ASSET_KEYS) {
            String id = Objects.toString(p.get(key), "");
            if (!id.isBlank() && !repo.ownsAsset(id)) p.put(key, "");
        }
        String themeId = Objects.toString(p.get("activeThemeId"), "");
        if (!themeId.isBlank() && repo.findTheme(themeId).isEmpty()) p.put("activeThemeId", "");
    }

    /**
     * A tenant value identical to the platform default is not an override:
     * store it blank so it keeps following the default. Without this, saving
     * the tenant form once — it is pre-filled with the effective values —
     * would freeze every field the admin never touched.
     */
    private void inheritWhereEqualToPlatformDefault(Map<String, Object> p) {
        BrandingDtos.BrandingProfileView base = getPlatformTemplate().profile();
        if (base == null) return;
        blankIfEqual(p, "productName", base.productName());
        blankIfEqual(p, "tagline", base.tagline());
        blankIfEqual(p, "fontFamily", base.fontFamily());
        if (Objects.equals(p.get("country"), base.country())
                && Objects.equals(p.get("timezone"), base.timezone())
                && Objects.equals(p.get("currency"), base.currency())) {
            p.put("country", "");
            p.put("timezone", "");
            p.put("currency", "");
            p.put("numberLocale", "");
        }
        blankIfEqual(p, "defaultLanguage", base.defaultLanguage());
        // 0 is the persisted "unset" marker for the numeric fields (toProfileView → null).
        zeroIfEqual(p, "fontSize", base.fontSize());
        zeroIfEqual(p, "statusGreen", base.statusGreen());
        zeroIfEqual(p, "statusBlue", base.statusBlue());
        zeroIfEqual(p, "statusAmber", base.statusAmber());
    }

    private static void blankIfEqual(Map<String, Object> p, String key, String platformValue) {
        if (!isBlank(platformValue) && platformValue.equals(p.get(key))) p.put(key, "");
    }

    private static void zeroIfEqual(Map<String, Object> p, String key, Integer platformValue) {
        if (platformValue != null && platformValue.equals(p.get(key))) p.put(key, 0);
    }

    private static String pick(String own, String base) {
        return isBlank(own) ? base : own;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ----- View mappers -----
    @SuppressWarnings("unchecked")
    private static BrandingDtos.BrandingProfileView toProfileView(Map<String, Object> r) {
        // Stored as 0 when unset; expose null so the SPA falls back to its default.
        Integer fontSize = null;
        if (r.get("fontSize") instanceof Number n && n.intValue() > 0) {
            fontSize = n.intValue();
        }
        return new BrandingDtos.BrandingProfileView(
                Objects.toString(r.get("id")),
                Objects.toString(r.getOrDefault("name", "")),
                Objects.toString(r.getOrDefault("productName", "")),
                Objects.toString(r.getOrDefault("tagline", "")),
                Objects.toString(r.getOrDefault("country", "")),
                Objects.toString(r.getOrDefault("defaultLanguage", "")),
                Objects.toString(r.getOrDefault("activeThemeId", "")),
                Objects.toString(r.getOrDefault("logoAssetId", "")),
                Objects.toString(r.getOrDefault("backgroundAssetId", "")),
                Objects.toString(r.getOrDefault("faviconAssetId", "")),
                Objects.toString(r.getOrDefault("fontFamily", "")),
                fontSize,
                Objects.toString(r.getOrDefault("timezone", "")),
                Objects.toString(r.getOrDefault("currency", "")),
                // Re-derived from the country rather than read back, so a row
                // written before this field existed still answers correctly:
                // the country is the authority and the column is its cache.
                Countries.byCode(Objects.toString(r.getOrDefault("country", "")))
                        .map(Countries.Country::numberLocale).orElse(""),
                parseInstant(r.get("updatedAt")),
                intOrNull(r.get("statusGreen")),
                intOrNull(r.get("statusBlue")),
                intOrNull(r.get("statusAmber")));
    }

    /** A positive Integer from a stored numeric cell, else null (SPA uses its default). */
    private static Integer intOrNull(Object o) {
        if (o instanceof Number n && n.intValue() > 0) return n.intValue();
        return null;
    }

    /** Base font size in px, clamped to the supported range; 0 means "unset". */
    private static int normalizeFontSize(Integer fontSize) {
        if (fontSize == null) return 0;
        return (fontSize >= 10 && fontSize <= 24) ? fontSize : 0;
    }

    /**
     * Validate a tenant time zone against the IANA database. Blank or unknown
     * ids are stored as "" (unset), letting the SPA fall back to the viewer's
     * browser time zone rather than persisting a value that no {@link ZoneId}
     * could resolve.
     */
    private static String normalizeTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) return "";
        String tz = timezone.trim();
        try {
            return ZoneId.of(tz).getId();
        } catch (DateTimeException e) {
            return "";
        }
    }

    /**
     * Validate a tenant currency against ISO 4217. Blank or unrecognised codes
     * are stored as "" (unset), letting the SPA fall back to the platform
     * default rather than persisting a code no formatter could resolve.
     *
     * <p>Uppercased on the way in so "inr" and "INR" are the same stored value —
     * the display layer looks the code up in a symbol table, which is
     * case-sensitive.</p>
     */
    private static String normalizeCurrency(String currency) {
        if (currency == null || currency.isBlank()) return "";
        String code = currency.trim().toUpperCase(Locale.ROOT);
        try {
            return Currency.getInstance(code).getCurrencyCode();
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private static BrandingDtos.ThemeView toThemeView(Map<String, Object> r) {
        Map<String, Object> c = (Map<String, Object>) r.getOrDefault("colors", Map.of());
        return new BrandingDtos.ThemeView(
                Objects.toString(r.get("id")),
                Objects.toString(r.getOrDefault("name", "")),
                Objects.toString(r.getOrDefault("mode", "light")),
                new BrandingDtos.ColorScheme(
                        Objects.toString(c.getOrDefault("primary", "#000000")),
                        Objects.toString(c.getOrDefault("secondary", "#000000")),
                        Objects.toString(c.getOrDefault("accent", "#000000")),
                        Objects.toString(c.getOrDefault("background", "#ffffff")),
                        Objects.toString(c.getOrDefault("text", "#111111"))),
                Objects.toString(r.getOrDefault("sanitizedCss", "")),
                parseInstant(r.get("updatedAt")));
    }

    private static BrandingDtos.UiLabelView toLabelView(Map<String, Object> r) {
        return new BrandingDtos.UiLabelView(
                Objects.toString(r.getOrDefault("labelKey", "")),
                Objects.toString(r.getOrDefault("lang", "")),
                Objects.toString(r.getOrDefault("value", "")),
                parseInstant(r.get("updatedAt")));
    }

    private static Instant parseInstant(Object o) {
        if (o == null) return Instant.EPOCH;
        try { return Instant.parse(o.toString()); } catch (Exception e) { return Instant.EPOCH; }
    }

    public record Asset(String contentType, byte[] bytes) {}
}
