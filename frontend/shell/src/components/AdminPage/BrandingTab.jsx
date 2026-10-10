import { useEffect, useState, useCallback } from 'react';
import {
  Box, Typography, Paper, Button, TextField, Alert, CircularProgress, Divider, Chip,
  FormControl, InputLabel, Select, MenuItem, Radio, Tooltip, IconButton,
  Dialog, DialogTitle, DialogContent, DialogActions,
} from '@mui/material';
import FileDownloadIcon         from '@mui/icons-material/FileDownload';
import UploadFileIcon           from '@mui/icons-material/UploadFile';
import SettingsBackupRestoreIcon from '@mui/icons-material/SettingsBackupRestore';
import HistoryIcon              from '@mui/icons-material/History';
import { SUPPORTED_LANGUAGES, getBaseCatalog } from 'eip-shell-context/i18n';
import { SUPPORTED_COUNTRIES, countryByCode } from 'eip-shell-context/country';
import { formatMoneyIn } from 'eip-shell-context/money';
import { useTranslation } from 'react-i18next';
import axios from 'eip-shell-context/axiosAuth';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { notifyBrandingChanged } from 'eip-shell-context/BrandingProvider';
import { apiErrorMessage } from 'eip-shell-context/apiError';
import { useToast } from 'eip-shell-context/ToastProvider';

// Built-in theme defaults — seed the editor on load and restore it when the
// operator clicks "Restore to default" in the Theme editor section. A fresh
// clone is handed out each time so nested `colors` edits never mutate this.
// These must stay a COHERENT SET, not five independently plausible colours.
// A bulk re-hue left this as mode:'light' with a navy background and `text`
// set to the same navy as `background` — restoring the default produced
// invisible body text, and the light mode silently opted the tenant out of the
// Orphos theme (tenantThemeOptions treats mode:'light' as the legacy palette).
// Values mirror orphosTheme: canvas, white body text, blue primary.
const DEFAULT_THEME = {
  name: 'default', mode: 'dark',
  colors: { primary: '#2F6FF0', secondary: '#8DBCFF', accent: '#3DDC97',
            background: '#0B1533', text: '#FFFFFF' },
  customCss: '',
};
const freshDefaultTheme = () => ({ ...DEFAULT_THEME, colors: { ...DEFAULT_THEME.colors } });

// The built-in font stack the SPA uses when the tenant hasn't chosen one.
const DEFAULT_FONT_STACK = '"Inter", "Noto Sans JP", "Roboto", sans-serif';

// Quick-pick suggestions for the (free-text) font field. These are only
// shortcuts — the operator can type ANY font-family value; each suggestion is a
// full stack ending in a generic family so it degrades gracefully when the
// primary face isn't installed. '' = fall back to the built-in default.
const FONT_SUGGESTIONS = [
  { label: 'Default (Inter)', value: '' },
  { label: 'System UI',       value: 'system-ui, -apple-system, "Segoe UI", Roboto, sans-serif' },
  { label: 'Roboto',          value: '"Roboto", "Noto Sans JP", sans-serif' },
  { label: 'Arial',           value: 'Arial, Helvetica, sans-serif' },
  { label: 'Helvetica',       value: '"Helvetica Neue", Helvetica, Arial, sans-serif' },
  { label: 'Verdana',         value: 'Verdana, Geneva, sans-serif' },
  { label: 'Tahoma',          value: 'Tahoma, Geneva, sans-serif' },
  { label: 'Georgia',         value: 'Georgia, "Times New Roman", serif' },
  { label: 'Times',           value: '"Times New Roman", Times, serif' },
  { label: 'Courier',         value: '"Courier New", Courier, monospace' },
  { label: 'Monospace',       value: '"JetBrains Mono", "Courier New", monospace' },
];
// 0 = default; the rest are absolute base sizes in px (matches the backend's
// supported 10–24 range).
const FONT_SIZE_OPTIONS = [0, 11, 12, 13, 14, 15, 16, 18, 20, 22, 24];

/** Current "GMT±H:MM" offset for a zone (uses today's date, so it honours DST). */
function gmtOffset(tz) {
  try {
    const parts = new Intl.DateTimeFormat('en-US', { timeZone: tz, timeZoneName: 'shortOffset' })
      .formatToParts(new Date());
    return parts.find(p => p.type === 'timeZoneName')?.value || 'GMT';
  } catch { return 'GMT'; }
}

/**
 * International-standard display label for a zone, e.g. "Kolkata/Asia — GMT+5:30"
 * or "New York/America — GMT-4". The canonical IANA id ("Asia/Kolkata") is still
 * what gets stored; this is display only.
 */
function tzLabel(tz) {
  if (!tz) return '';
  const off = gmtOffset(tz);
  if (tz === 'UTC') return `UTC — ${off}`;
  const parts = tz.split('/');
  const region = parts[0].replace(/_/g, ' ');
  const city = parts.slice(1).join(' / ').replace(/_/g, ' ');
  return city ? `${city}/${region} — ${off}` : `${tz} — ${off}`;
}

// A representative amount for the country preview. Large enough to cross the
// crore step for India and the 万 step for Japan, so the two countries produce
// visibly different notation rather than two lines that look the same.
const SAMPLE_AMOUNT = 58600000;

/**
 * What the chosen country resolves to — the four settings, plus a worked
 * example of each notation.
 *
 * The example is the point: "en-IN" tells an admin nothing, "₹5.86 Cr" tells
 * them exactly what every tile on their board is about to look like. It is a
 * PREVIEW of an unsaved choice, so it formats through formatMoneyIn with an
 * explicit locale rather than formatMoney, which would answer for the country
 * currently in force instead of the one being considered.
 */
function ResolvedCountry({ code }) {
  const { t } = useTranslation();
  const country = countryByCode(code);
  if (!country) return null;

  const language = SUPPORTED_LANGUAGES.find(l => l.code === country.language);
  const now = new Date();
  const clock = (() => {
    try {
      return now.toLocaleString(country.numberLocale,
        { timeZone: country.timezone, dateStyle: 'medium', timeStyle: 'short' });
    } catch { return ''; }
  })();

  const rows = [
    [t('admin.screens.branding.resolvedLanguage', 'Default language'),
      `${language?.nativeLabel || country.language} (${language?.label || country.language})`],
    [t('admin.screens.branding.timezone', 'Time zone'), tzLabel(country.timezone)],
    [t('admin.screens.branding.currency', 'Reporting currency'),
      `${country.currency} — ${formatMoneyIn(country.numberLocale, SAMPLE_AMOUNT, country.currency)}`],
    [t('admin.screens.branding.resolvedFormat', 'Date & number format'),
      clock ? `${country.numberLocale} — ${clock}` : country.numberLocale],
  ];

  return (
    <Box sx={{
      display: 'grid', gridTemplateColumns: 'max-content 1fr', columnGap: 2, rowGap: 0.5,
      mb: 1.5, p: 1.5, borderRadius: 1, bgcolor: 'rgba(47, 111, 240, 0.08)',
      border: '1px solid rgba(47, 111, 240, 0.28)',
    }}>
      {rows.map(([label, value]) => (
        <Box key={label} sx={{ display: 'contents' }}>
          <Typography fontSize={12} color='#C3D3F2'>{label}</Typography>
          <Typography fontSize={12} fontWeight={600}>{value}</Typography>
        </Box>
      ))}
    </Box>
  );
}

// Where each palette colour shows up in the product — surfaced as a caption plus
// a live sample beneath the colour picker so operators know what they're changing.
const COLOR_USAGE = {
  primary:    'Buttons, links & active highlights',
  secondary:  'Headers, side panels & secondary surfaces',
  accent:     'Badges, success states & callouts',
  background: 'Page & dashboard background',
  text:       'Body text & labels',
};

// Asset kinds → profile field, plus the limits the server enforces
// (AssetValidator / admin.branding.* in admin-console application.yml),
// checked on pick so a bad file is rejected before Save.
const ASSET_KINDS = ['logo', 'background', 'favicon'];
const ASSET_KEY = { logo: 'logoAssetId', background: 'backgroundAssetId', favicon: 'faviconAssetId' };
const ASSET_MIME = ['image/png', 'image/jpeg', 'image/svg+xml', 'image/webp'];
const MAX_LOGO_BYTES = 1024 * 1024;           // logo + favicon
const MAX_BACKGROUND_BYTES = 4 * 1024 * 1024;
const humanBytes = b => (b < 1024 * 1024 ? `${Math.round(b / 1024)} KB` : `${(b / (1024 * 1024)).toFixed(1)} MB`);

/** Display names for the backend's `overriddenFields` keys (tenant status banner). */
const FIELD_LABELS = {
  productName:      'product name',
  tagline:          'tagline',
  country:          'primary country',
  defaultLanguage:  'default language',
  logo:             'logo',
  background:       'background',
  favicon:          'favicon',
  fontFamily:       'font',
  fontSize:         'font size',
  statusThresholds: 'status thresholds',
  theme:            'theme colours',
};

/** Small live preview showing where a palette colour appears in the UI. */
function ColorSample({ role, value, textColor }) {
  const chip = (bg, fg, label) => (
    <Box sx={{ bgcolor: bg, color: fg, fontSize: 11, px: 1, py: 0.5,
      borderRadius: 1, textAlign: 'center', fontWeight: 600, whiteSpace: 'nowrap' }}>{label}</Box>
  );
  switch (role) {
    case 'primary':    return chip(value, '#FFFFFF', 'Primary button');
    case 'secondary':  return chip(value, '#FFFFFF', 'Header bar');
    case 'accent':
      return (
        <Box>
          <Box component='span' sx={{ display: 'inline-block', bgcolor: value, color: '#FFFFFF',
            fontSize: 10, px: 0.75, py: 0.25, borderRadius: 1, fontWeight: 700 }}>Badge</Box>
        </Box>
      );
    case 'background':
      return (
        <Box sx={{ bgcolor: value, color: textColor || '#FFFFFF', border: '1px solid #243A72',
          fontSize: 11, px: 1, py: 0.5, borderRadius: 1 }}>Page background</Box>
      );
    case 'text':
      return <Typography sx={{ color: value, fontSize: 12, fontWeight: 600 }}>The quick brown fox</Typography>;
    default:
      return <Typography sx={{ color: value, fontSize: 12 }}>Sample</Typography>;
  }
}

/**
 * Section B - branding admin UI.
 * Profile editor, asset upload, theme editor with CSS preview, preview-as-iframe.
 *
 * @param platformTemplate when true, the tab edits the shared PLATFORM DEFAULT
 *   template (stored under a reserved pseudo-tenant) instead of the acting
 *   tenant's branding. Used by the platform-scope "Platform Default" tab. In
 *   this mode the theme-list and UI-label sections are hidden and all
 *   profile/theme/asset calls target the /platform-template/* endpoints.
 */
export default function BrandingTab({ platformTemplate = false }) {
  const { t } = useTranslation();
  const toast = useToast();
  const [profile, setProfile] = useState(null);
  const [themes, setThemes]   = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr]         = useState(null);
  const [theme, setTheme]     = useState(freshDefaultTheme);
  const [previewToken, setPreviewToken] = useState(null);
  // Tenant mode: which settings this tenant overrides ([] = fully on the platform default).
  const [overridden, setOverridden] = useState([]);
  // Unsaved asset changes: {kind: {file, url}} to upload, or {kind: {remove: true}}.
  const [assetDraft, setAssetDraft] = useState({});

  // ── UI label overrides (titles & text, per language) ──
  const [labelLang, setLabelLang]     = useState('en');
  const [labelRows, setLabelRows]     = useState([]);   // server truth: [{labelKey, lang, value}]
  const [labelDraft, setLabelDraft]   = useState({});   // {lang: {key: value}} — edited state
  const [labelFilter, setLabelFilter] = useState('');
  const [labelReport, setLabelReport] = useState(null); // last CSV import report
  const [labelHistory, setLabelHistory] = useState(null); // null = panel closed

  // `quiet` refreshes the effective values after a save without the spinner
  // and without discarding an unsaved theme-editor draft.
  const load = useCallback(async ({ quiet = false } = {}) => {
    if (!quiet) setLoading(true);
    setErr(null);
    try {
      if (platformTemplate) {
        // The platform default is a single active profile + its one theme,
        // returned together by the template endpoint.
        const r = await axios.get('/api/admin/branding/platform-template');
        const data = r.data || {};
        setProfile(data.profile || { name: 'platform-default' });
        setThemes([]);
        if (data.theme) {
          setTheme({
            id: data.theme.id,
            name: data.theme.name || 'default',
            mode: data.theme.mode || 'light',
            colors: { ...freshDefaultTheme().colors, ...(data.theme.colors || {}) },
            customCss: data.theme.sanitizedCss || '',
          });
        }
      } else {
        // The EFFECTIVE branding: this tenant's overrides layered over the
        // platform default. `overriddenFields` says which parts are the
        // tenant's own; everything else follows the platform default live.
        const [b, th] = await Promise.all([
          axios.get('/api/branding/public/active-bundle'),
          axios.get('/api/admin/branding/themes'),
        ]);
        const data = b.data || {};
        setProfile(data.profile);
        setThemes(th.data);
        setOverridden(Array.isArray(data.overriddenFields) ? data.overriddenFields : []);
        // Start the editor from the colours in effect (the platform default's
        // while inheriting). No id: saving creates a tenant-owned theme rather
        // than addressing the platform's row.
        if (data.theme && !quiet) {
          setTheme({
            name: data.theme.name || 'default',
            mode: data.theme.mode || 'light',
            colors: { ...freshDefaultTheme().colors, ...(data.theme.colors || {}) },
            customCss: data.theme.sanitizedCss || '',
          });
        }
      }
    } catch (e) {
      setErr(apiErrorMessage(e, t('admin.screens.branding.errLoad', 'Failed to load branding')));
    }
    if (!quiet) setLoading(false);
  }, [platformTemplate]);

  useEffect(() => { load(); }, [load]);

  const saveProfile = async (patch, successMsg = t('admin.screens.branding.profileSaved', 'Branding profile saved successfully.')) => {
    setErr(null);
    try {
      const url = platformTemplate
        ? '/api/admin/branding/platform-template/profile'
        : '/api/admin/branding/profiles';
      const r = await axios.put(url,
        { ...(profile || { name: platformTemplate ? 'platform-default' : 'default' }), ...patch });
      // The tenant save returns only the tenant's own override row (blank
      // where it inherits), so re-read the effective values for the form.
      if (platformTemplate) setProfile(r.data);
      else await load({ quiet: true });
      // Make the rest of the SPA (HeaderBar, document.title, injected CSS,
      // favicon) re-render with the new active theme / logo / colours.
      // Without this, clicking "Use" only updated the DB; the change wasn't
      // visible until a manual reload. The platform template is not the SPA's
      // own branding, so it does not trigger a live re-render.
      if (!platformTemplate) notifyBrandingChanged();
      toast.success(successMsg);
      return true;
    } catch (e) {
      setErr(apiErrorMessage(e, t('admin.screens.branding.errSave', 'Save failed')));
      return false;
    }
  };

  // ── Assets: stage, preview, then "Save assets" (like every other section) ──
  // Picking or removing a file only changes the draft; nothing reaches the
  // server until Save, so the admin can preview and back out.
  const pickAsset = (kind, file) => {
    const label = t(`admin.screens.branding.assetKind_${kind}`, kind);
    if (!ASSET_MIME.includes((file.type || '').toLowerCase())) {
      setErr(t('admin.screens.branding.errAssetType',
        '{{kind}}: unsupported file type. Allowed types: PNG, JPEG, SVG, WebP.', { kind: label }));
      return;
    }
    const limit = kind === 'background' ? MAX_BACKGROUND_BYTES : MAX_LOGO_BYTES;
    if (file.size > limit) {
      setErr(t('admin.screens.branding.errAssetSize',
        '{{kind}} is too large ({{size}}). Maximum allowed is {{max}}.',
        { kind: label, size: humanBytes(file.size), max: humanBytes(limit) }));
      return;
    }
    setErr(null);
    setAssetDraft(prev => {
      if (prev[kind]?.url) URL.revokeObjectURL(prev[kind].url);
      return { ...prev, [kind]: { file, url: URL.createObjectURL(file) } };
    });
  };

  const removeAsset = (kind) => setAssetDraft(prev => {
    if (prev[kind]?.url) URL.revokeObjectURL(prev[kind].url);
    return { ...prev, [kind]: { remove: true } };
  });

  const undoAsset = (kind) => setAssetDraft(prev => {
    if (prev[kind]?.url) URL.revokeObjectURL(prev[kind].url);
    const next = { ...prev };
    delete next[kind];
    return next;
  });

  // Free preview URLs when the tab unmounts.
  useEffect(() => () => Object.values(assetDraft).forEach(d => d?.url && URL.revokeObjectURL(d.url)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    []);

  const saveAssets = async () => {
    const kinds = Object.keys(assetDraft);
    if (kinds.length === 0) {
      toast.success(t('admin.screens.branding.assetsNoChanges', 'No asset changes to save.'));
      return;
    }
    setErr(null);
    const upload = async (url, kind, file) => {
      const fd = new FormData();
      fd.append('kind', kind);
      fd.append('file', file);
      return axios.post(url, fd, { headers: { 'Content-Type': 'multipart/form-data' } });
    };
    let failedKind = null;
    try {
      if (platformTemplate) {
        // The template endpoints link / unlink the asset on the template
        // profile server-side, so there is no profile save afterwards.
        for (const kind of kinds) {
          failedKind = kind;
          const d = assetDraft[kind];
          if (d.file) await upload('/api/admin/branding/platform-template/assets', kind, d.file);
          else if (d.remove && profile?.[ASSET_KEY[kind]]) {
            await axios.delete(`/api/admin/branding/platform-template/assets/${profile[ASSET_KEY[kind]]}`);
          }
        }
        failedKind = null;
        await load({ quiet: true });
        toast.success(t('admin.screens.branding.assetsSaved', 'Assets saved successfully.'));
      } else {
        const patch = {};
        for (const kind of kinds) {
          failedKind = kind;
          const d = assetDraft[kind];
          if (d.file) {
            const r = await upload('/api/admin/branding/assets', kind, d.file);
            patch[ASSET_KEY[kind]] = r.data.id;
          } else if (d.remove) {
            // Only this tenant's own upload is deleted; removing it lets the
            // platform default's asset show through again.
            if (overridden.includes(kind)) {
              await axios.delete(`/api/admin/branding/assets/${profile[ASSET_KEY[kind]]}`).catch(() => {});
            }
            patch[ASSET_KEY[kind]] = '';
          }
        }
        failedKind = null;
        const ok = await saveProfile(patch, t('admin.screens.branding.assetsSaved', 'Assets saved successfully.'));
        if (!ok) return;   // saveProfile already showed the error; keep the draft
      }
      kinds.forEach(k => assetDraft[k]?.url && URL.revokeObjectURL(assetDraft[k].url));
      setAssetDraft({});
    } catch (e) {
      const kind = failedKind ? t(`admin.screens.branding.assetKind_${failedKind}`, failedKind) : '';
      setErr(apiErrorMessage(e, t('admin.screens.branding.errUpload', 'Upload of {{kind}} failed', { kind })));
      // The draft is kept so the admin can fix the file and save again.
      if (platformTemplate) load({ quiet: true });
    }
  };

  const saveTheme = async () => {
    setErr(null);
    try {
      const url = platformTemplate
        ? '/api/admin/branding/platform-template/theme'
        : '/api/admin/branding/themes';
      const r = await axios.put(url, theme);
      setTheme(prev => ({ ...prev, id: r.data.id }));
      if (!platformTemplate && !overridden.includes('theme')) {
        // Still on the platform default's colours: editing them means this
        // tenant now wants its own, so apply the theme it just saved.
        await saveProfile({ activeThemeId: r.data.id },
          t('admin.screens.branding.themeSavedApplied', 'Theme saved and applied to this tenant.'));
        return;
      }
      load({ quiet: true });
      // If the active theme is the one we just edited, the rest of the SPA
      // needs to re-fetch so custom CSS / colours update live. (The platform
      // template is never the SPA's own active theme.)
      if (!platformTemplate && profile?.activeThemeId && profile.activeThemeId === r.data.id) {
        notifyBrandingChanged();
      }
      toast.success(t('admin.screens.branding.themeSaved', 'Theme saved successfully.'));
    } catch (e) { setErr(apiErrorMessage(e, t('admin.screens.branding.errThemeSave', 'Theme save failed'))); }
  };

  // Drop every appearance override of THIS tenant (tenant mode) so it follows
  // the live platform default again — including future platform changes.
  const restorePlatformDefault = async () => {
    if (!window.confirm(t('admin.screens.branding.confirmRestorePlatform',
      "Remove this tenant's customised appearance (profile, themes and uploaded assets) and use the platform default? UI text overrides are kept."))) return;
    setErr(null);
    try {
      await axios.post('/api/admin/branding/restore-platform-default');
      await load();
      notifyBrandingChanged();
      toast.success(t('admin.screens.branding.platformRestored', 'Branding restored to the platform default.'));
    } catch (e) {
      setErr(apiErrorMessage(e, t('admin.screens.branding.errRestorePlatform', 'Failed to restore the platform default')));
    }
  };

  // ── Restore-to-default handlers (one per section, tenant mode) ──
  // "Default" means the PLATFORM default: a blank tenant value inherits it, so
  // each restore clears that section's overrides rather than writing values.

  // Active profile: clear product/tagline/country/font overrides and persist.
  // Asset and active-theme references are left untouched (they have their own restore).
  const restoreProfileDefaults = async () => {
    if (!window.confirm(t('admin.screens.branding.confirmRestoreProfile',
      'Reset product name, tagline, primary country and font to the platform default?'))) return;
    await saveProfile(
      // country: '' clears the derived language/zone/currency with it — the
      // backend resolves those from the country, so leaving them set here
      // would only be re-derived away on save.
      { productName: '', tagline: '', country: '', defaultLanguage: '',
        fontFamily: '', fontSize: 0, timezone: '', currency: '' },
      t('admin.screens.branding.profileRestored', 'Profile restored to the platform default.'));
  };

  // Assets: delete this tenant's own logo/background/favicon binaries and clear
  // the references so the platform default's assets show through again.
  const restoreAssetsDefaults = async () => {
    const kinds = ['logo', 'background', 'favicon'];
    // Only the tenant's own uploads — the effective ids may be the platform's.
    const present = kinds.filter(k => overridden.includes(k));
    if (present.length === 0) {
      toast.success(t('admin.screens.branding.assetsAlreadyDefault', 'Assets are already at their defaults.'));
      return;
    }
    if (!window.confirm(t('admin.screens.branding.confirmRestoreAssets',
      'Remove the uploaded logo, background and favicon and use the platform default?'))) return;
    setErr(null);
    // allSettled: a binary may already be gone (dangling reference); we still
    // want to clear the profile pointers, which is the real goal.
    await Promise.allSettled(present.map(k =>
      axios.delete(`/api/admin/branding/assets/${profile[`${k}AssetId`]}`)));
    const ok = await saveProfile(
      { logoAssetId: '', backgroundAssetId: '', faviconAssetId: '' },
      t('admin.screens.branding.assetsRestored', 'Assets restored to the platform default.'));
    if (ok) {
      Object.values(assetDraft).forEach(d => d?.url && URL.revokeObjectURL(d.url));
      setAssetDraft({});
    }
  };

  // Theme: stop applying the tenant's own theme so the platform default's
  // colours show through; the editor is reloaded with them. Saved themes in
  // the "Existing themes" list are kept and can be re-applied.
  const restoreThemeDefaults = async () => {
    setPreviewToken(null);
    if (!overridden.includes('theme')) {
      toast.success(t('admin.screens.branding.themeAlreadyDefault', 'The theme is already the platform default.'));
      return;
    }
    await saveProfile({ activeThemeId: '' },
      t('admin.screens.branding.themeRestored', 'Theme restored to the platform default.'));
    await load();
  };

  const loadLabels = useCallback(async () => {
    try {
      const r = await axios.get('/api/admin/branding/labels');
      const rows = Array.isArray(r.data) ? r.data : [];
      setLabelRows(rows);
      const byLang = {};
      rows.forEach(l => { (byLang[l.lang] = byLang[l.lang] || {})[l.labelKey] = l.value; });
      setLabelDraft(byLang);
    } catch (e) {
      setErr(apiErrorMessage(e, t('admin.screens.branding.errLabelsLoad', 'Failed to load UI labels')));
    }
  }, []);

  // Labels are per-tenant; the platform template has none, so skip the fetch
  // (in platform scope there is no acting tenant for it to resolve against).
  useEffect(() => { if (!platformTemplate) loadLabels(); }, [loadLabels, platformTemplate]);

  const setDraftValue = (key, value) => {
    setLabelDraft(prev => ({
      ...prev,
      [labelLang]: { ...(prev[labelLang] || {}), [key]: value },
    }));
  };

  const saveLabels = async () => {
    setErr(null);
    // Diff draft against server truth; blank value = remove the override.
    const server = {};
    labelRows.forEach(l => { (server[l.lang] = server[l.lang] || {})[l.labelKey] = l.value; });
    const entries = [];
    const langs = new Set([...Object.keys(labelDraft), ...Object.keys(server)]);
    for (const lang of langs) {
      const keys = new Set([
        ...Object.keys(labelDraft[lang] || {}),
        ...Object.keys(server[lang] || {}),
      ]);
      for (const key of keys) {
        const before = server[lang]?.[key] ?? '';
        const after = (labelDraft[lang]?.[key] ?? '').trim();
        if (before !== after) entries.push({ labelKey: key, lang, value: after });
      }
    }
    if (entries.length === 0) {
      toast.success(t('admin.screens.branding.labelsNoChanges', 'No label changes to save.'));
      return;
    }
    try {
      await axios.put('/api/admin/branding/labels', { labels: entries });
      await loadLabels();
      // Re-fetch the active bundle so the whole SPA re-renders with the new text.
      notifyBrandingChanged();
      toast.success(t('admin.screens.branding.labelsSaved',
        '{{count}} label override(s) saved successfully.', { count: entries.length }));
    } catch (e) {
      setErr(apiErrorMessage(e, t('admin.screens.branding.errLabelsSave', 'Saving labels failed')));
    }
  };

  // Full-catalog CSV template (all languages, defaults included). Fill the
  // `value` column and import the file back — blank values are ignored/removed.
  const exportLabelsCsv = () => {
    const esc = s => (/[",\n\r]/.test(s) ? '"' + String(s).replace(/"/g, '""') + '"' : String(s));
    const lines = ['key,language,default,value'];
    SUPPORTED_LANGUAGES.forEach(({ code }) => {
      const catalog = getBaseCatalog(code);
      const overrides = labelDraft[code] || {};
      const keys = [...new Set([...Object.keys(catalog), ...Object.keys(overrides)])].sort();
      keys.forEach(k => lines.push(
        [esc(k), code, esc(catalog[k] || ''), esc(overrides[k] || '')].join(',')));
    });
    // BOM so Excel opens the Japanese column as UTF-8.
    const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'ui-labels.csv';
    a.click();
    URL.revokeObjectURL(a.href);
  };

  // Remove every override for the selected language — history keeps each removal.
  const restoreLabelDefaults = async () => {
    const langLabel = SUPPORTED_LANGUAGES.find(l => l.code === labelLang)?.label || labelLang;
    if (!window.confirm(`Remove ALL ${langLabel} label overrides and restore the built-in defaults?`)) return;
    setErr(null);
    try {
      const r = await axios.post('/api/admin/branding/labels/restore-defaults', null,
        { params: { lang: labelLang } });
      await loadLabels();
      notifyBrandingChanged();
      if (labelHistory !== null) await loadLabelHistory();
      toast.success(t('admin.screens.branding.labelsRestored',
        'Defaults restored — {{count}} override(s) removed.', { count: r.data.removed }));
    } catch (e) {
      setErr(apiErrorMessage(e, t('admin.screens.branding.errLabelsRestore', 'Restore defaults failed')));
    }
  };

  const loadLabelHistory = async () => {
    try {
      const r = await axios.get('/api/admin/branding/labels/history', { params: { limit: 50 } });
      setLabelHistory(Array.isArray(r.data) ? r.data : []);
    } catch (e) {
      setErr(apiErrorMessage(e, t('admin.screens.branding.errLabelsHistory', 'Failed to load label history')));
    }
  };

  // Put a key back to the value it had before this history entry (blank old
  // value = the override didn't exist, so reverting removes it again).
  const revertLabelChange = async (h) => {
    setErr(null);
    try {
      await axios.put('/api/admin/branding/labels',
        { labels: [{ labelKey: h.labelKey, lang: h.lang, value: h.oldValue || '' }] });
      await loadLabels();
      notifyBrandingChanged();
      await loadLabelHistory();
      toast.success(t('admin.screens.branding.labelReverted', 'Label "{{key}}" reverted.', { key: h.labelKey }));
    } catch (e) {
      setErr(apiErrorMessage(e, t('admin.screens.branding.errLabelRevert', 'Revert failed')));
    }
  };

  const importLabelsCsv = async (file) => {
    setErr(null); setLabelReport(null);
    const fd = new FormData();
    fd.append('file', file);
    try {
      const r = await axios.post('/api/admin/branding/labels/import', fd,
        { headers: { 'Content-Type': 'multipart/form-data' } });
      setLabelReport(r.data);
      await loadLabels();
      notifyBrandingChanged();
      toast.success(t('admin.screens.branding.labelsImported',
        'CSV imported: {{upserted}} set, {{removed}} removed.',
        { upserted: r.data.upserted, removed: r.data.removed }));
    } catch (e) {
      setErr(apiErrorMessage(e, t('admin.screens.branding.errLabelsImport', 'CSV import failed')));
    }
  };

  const requestPreview = async () => {
    try {
      const r = await axios.post('/api/admin/branding/themes/preview',
        { themeId: theme.id, colorsOverride: theme.colors, cssOverride: theme.customCss });
      setPreviewToken(r.data.token);
    } catch (e) { setErr(apiErrorMessage(e, t('admin.screens.branding.errPreview', 'Preview failed'))); }
  };

  if (loading) return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>;

  return (
    <Box>
      {platformTemplate ? (
        <Alert severity='info' sx={{ mb: 2, fontSize: 13 }}>
          {t('admin.screens.branding.platformTemplateBanner',
            'Editing the platform default appearance. Every tenant starts with it, and changes here apply straight away to every setting a tenant has not customised.')}
        </Alert>
      ) : (
        <>
          <TenantScopeBanner resource={t('admin.screens.branding.scopeResource', 'branding')} />
          <Alert severity={overridden.length ? 'warning' : 'success'} sx={{ mb: 1.5, fontSize: 13, alignItems: 'center' }}
            action={
              <Button size='small' variant='outlined' color='inherit'
                startIcon={<SettingsBackupRestoreIcon fontSize='small' />}
                disabled={overridden.length === 0}
                onClick={restorePlatformDefault}
                sx={{ textTransform: 'none', fontSize: 12, whiteSpace: 'nowrap' }}>
                {t('admin.screens.branding.restorePlatformDefault', 'Reset to Platform Default')}
              </Button>
            }>
            {overridden.length === 0
              ? t('admin.screens.branding.usingPlatformDefault',
                  'This tenant uses the platform default appearance and follows it automatically when it changes.')
              : t('admin.screens.branding.usingCustomised',
                  'Customised for this tenant: {{fields}}. Everything else follows the platform default.',
                  { fields: overridden.map(f => t(`admin.screens.branding.field_${f}`, FIELD_LABELS[f] || f)).join(', ') })}
          </Alert>
        </>
      )}
      {err && <Alert severity='error' sx={{ mb: 2 }}>{err}</Alert>}

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography fontWeight={700} fontSize={14}>{t('admin.screens.branding.activeProfile', 'Active profile')}</Typography>
        {!platformTemplate && (
          <Button size='small' startIcon={<SettingsBackupRestoreIcon fontSize='small' />}
            onClick={restoreProfileDefaults}
            sx={{ textTransform: 'none', fontSize: 12, color: '#C3D3F2' }}>
            {t('admin.screens.branding.restoreDefault', 'Restore to default')}
          </Button>
        )}
      </Box>
      <Paper variant='outlined' sx={{ p: 2, mb: 3 }}>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 1 }}>
          <TextField size='small' label={t('admin.screens.branding.profileName', 'Profile name')} value={profile?.name || ''}
            onChange={e => setProfile({ ...(profile || {}), name: e.target.value })} />
          <TextField size='small' label={t('admin.screens.branding.productName', 'Product name')} value={profile?.productName || ''}
            onChange={e => setProfile({ ...(profile || {}), productName: e.target.value })} />
          <TextField size='small' label={t('admin.screens.branding.tagline', 'Tagline')} value={profile?.tagline || ''}
            onChange={e => setProfile({ ...(profile || {}), tagline: e.target.value })} />
          {/* The workspace's primary country — ONE control behind four settings
              that have to agree: the default UI language, the time zone every
              clock and greeting resolves against, the reporting currency, and
              the notation figures are written in. They were three independent
              fields until now, which let a tenant end up on Japanese text over
              IST clocks and ₹ tiles. See eip-shell-context/country; the backend
              catalog (Countries.java) is what actually resolves them on save. */}
          <FormControl size='small' sx={{ minWidth: 220 }}>
            <InputLabel id='primary-country-label'>
              {t('admin.screens.branding.country', 'Primary country')}
            </InputLabel>
            <Select
              labelId='primary-country-label'
              label={t('admin.screens.branding.country', 'Primary country')}
              value={SUPPORTED_COUNTRIES.some(c => c.code === profile?.country) ? profile.country : ''}
              onChange={e => setProfile({ ...(profile || {}), country: e.target.value })}
            >
              {/* Blank is a real state, not a placeholder: tenants saved before
                  the country existed have none, and clearing it returns the
                  board to browser-zone clocks and the platform default unit. */}
              <MenuItem value='' sx={{ fontSize: 13, fontStyle: 'italic' }}>
                {t('admin.screens.branding.countryNone', 'Not set — platform defaults')}
              </MenuItem>
              {SUPPORTED_COUNTRIES.map(c => (
                <MenuItem key={c.code} value={c.code} sx={{ fontSize: 13 }}>
                  {c.flag}&nbsp;&nbsp;{t(`country.name.${c.code}`, c.label)}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </Box>
        {/* What the choice above actually resolves to. Shown rather than made
            editable: four separate inputs is exactly the arrangement that let
            them drift apart, and an admin still needs to see the values they
            are committing the whole tenant to. */}
        <ResolvedCountry code={profile?.country} />
        <Typography fontSize={12} color='#C3D3F2' mb={1}>
          {t('admin.screens.branding.countryHint',
            'Sets the workspace time zone, reporting currency, number format and language together. '
            + 'Changing it switches everyone to the new country’s language. '
            + 'Users can then pick their own display language from the header — that translates the interface only, '
            + 'and never moves the clocks or the currency.')}
        </Typography>
        <Typography fontSize={12} color='#C3D3F2' mb={1}>
          {t('admin.screens.branding.currencyHint',
            'The reporting currency is the unit used for money figures whose source does not state one. '
            + 'Figures that carry their own currency are always shown in it — no conversion is applied.')}
        </Typography>
        <Button size='small' variant='contained' onClick={() => saveProfile({})}>{t('admin.screens.branding.saveProfile', 'Save profile')}</Button>
      </Paper>

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography fontWeight={700} fontSize={14}>{t('admin.screens.branding.assets', 'Assets')}</Typography>
        {!platformTemplate && (
          <Button size='small' startIcon={<SettingsBackupRestoreIcon fontSize='small' />}
            onClick={restoreAssetsDefaults}
            sx={{ textTransform: 'none', fontSize: 12, color: '#C3D3F2' }}>
            {t('admin.screens.branding.restoreDefault', 'Restore to default')}
          </Button>
        )}
      </Box>
      <Paper variant='outlined' sx={{ p: 2, mb: 3 }}>
        <Typography fontSize={12} color='#C3D3F2' mb={1.5}>
          {t('admin.screens.branding.assetsHint',
            'Choose or remove a logo, background and favicon, check the preview, then click "Save assets". '
            + 'PNG, JPEG, SVG or WebP — up to 1 MB for the logo and favicon, 4 MB for the background.')}
        </Typography>
        <Box sx={{ display: 'grid', gap: 1.5, mb: 1.5,
          gridTemplateColumns: 'repeat(auto-fill, minmax(210px, 1fr))' }}>
          {ASSET_KINDS.map(kind => {
            const label = t(`admin.screens.branding.assetKind_${kind}`, kind);
            const draft = assetDraft[kind];
            const savedId = profile?.[ASSET_KEY[kind]];
            const src = draft?.url || (!draft?.remove && savedId ? `/api/branding/public/asset/${savedId}` : null);
            // Tenant mode: a saved asset the tenant did not upload is the platform default's.
            const inherited = !platformTemplate && !draft && savedId && !overridden.includes(kind);
            return (
              <Box key={kind} sx={{ display: 'flex', flexDirection: 'column', gap: 0.75,
                border: '1px solid #243A72', borderRadius: 1.5, p: 1.25 }}>
                <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 1 }}>
                  <Typography fontSize={12} fontWeight={700} sx={{ textTransform: 'capitalize' }}>{label}</Typography>
                  {draft?.file && (
                    <Chip size='small' label={t('admin.screens.branding.assetUnsaved', 'Unsaved')}
                      sx={{ fontSize: 10, bgcolor: 'rgba(255, 184, 77, 0.14)', color: '#FFB84D' }} />
                  )}
                  {draft?.remove && (
                    <Chip size='small' label={t('admin.screens.branding.assetWillRemove', 'Will be removed')}
                      sx={{ fontSize: 10, bgcolor: 'rgba(255, 107, 107, 0.14)', color: '#FF6B6B' }} />
                  )}
                  {inherited && (
                    <Chip size='small' label={t('admin.screens.branding.assetInherited', 'Platform default')}
                      sx={{ fontSize: 10, bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#5B9DFF' }} />
                  )}
                </Box>
                <Box sx={{ height: 72, display: 'flex', alignItems: 'center', justifyContent: 'center',
                  border: '1px dashed #243A72', borderRadius: 1, bgcolor: 'rgba(255,255,255,0.02)', overflow: 'hidden' }}>
                  {src ? (
                    <img alt={label} src={src} style={{ maxWidth: '100%', maxHeight: 64, objectFit: 'contain' }} />
                  ) : (
                    <Typography fontSize={11} color='#7D92C4'>
                      {t('admin.screens.branding.assetNone', 'No {{kind}} set', { kind: label })}
                    </Typography>
                  )}
                </Box>
                <Typography fontSize={10} color='#C3D3F2' noWrap title={draft?.file?.name || ''} sx={{ minHeight: 14 }}>
                  {draft?.file ? `${draft.file.name} · ${humanBytes(draft.file.size)}` : ''}
                </Typography>
                <Box sx={{ display: 'flex', gap: 0.75, flexWrap: 'wrap', mt: 'auto' }}>
                  <Button component='label' size='small' variant='outlined' sx={{ textTransform: 'none', fontSize: 12 }}>
                    {savedId || draft?.file
                      ? t('admin.screens.branding.replaceKind', 'Replace')
                      : t('admin.screens.branding.uploadKind', 'Upload {{kind}}', { kind: label })}
                    <input hidden type='file' accept={ASSET_MIME.join(',')}
                      onChange={e => { if (e.target.files?.[0]) pickAsset(kind, e.target.files[0]); e.target.value = ''; }} />
                  </Button>
                  {draft ? (
                    <Button size='small' onClick={() => undoAsset(kind)}
                      sx={{ textTransform: 'none', fontSize: 12, color: '#C3D3F2' }}>
                      {t('admin.screens.branding.assetUndo', 'Undo')}
                    </Button>
                  ) : (savedId && !inherited) && (
                    <Button size='small' color='error' onClick={() => removeAsset(kind)}
                      sx={{ textTransform: 'none', fontSize: 12 }}>
                      {t('admin.screens.branding.assetRemove', 'Remove')}
                    </Button>
                  )}
                </Box>
              </Box>
            );
          })}
        </Box>
        <Button size='small' variant='contained' onClick={saveAssets}>
          {t('admin.screens.branding.saveAssets', 'Save assets')}
        </Button>
      </Paper>

      {/* ── Font — tenant-wide font, independent of the colour theme ── */}
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography fontWeight={700} fontSize={14}>{t('admin.screens.branding.typography', 'Font')}</Typography>
      </Box>
      <Paper variant='outlined' sx={{ p: 2, mb: 3 }}>
        <Typography fontSize={12} color='#C3D3F2' mb={1.5}>
          {t('admin.screens.branding.typographyHint',
            'The font and size apply across the whole app. Leave the font blank to use the built-in default.')}
        </Typography>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'flex-start', mb: 1 }}>
          <TextField size='small' sx={{ minWidth: 300, flex: 1 }}
            label={t('admin.screens.branding.fontFamily', 'Font family')}
            placeholder={DEFAULT_FONT_STACK}
            value={profile?.fontFamily || ''}
            onChange={e => setProfile({ ...(profile || {}), fontFamily: e.target.value })}
            helperText={t('admin.screens.branding.fontFamilyHelp',
              'Type any font, or pick a suggestion below. Add fallbacks separated by commas.')}
            inputProps={{ style: { fontFamily: profile?.fontFamily || DEFAULT_FONT_STACK } }} />
          <FormControl size='small' sx={{ minWidth: 150 }}>
            <InputLabel id='font-size'>{t('admin.screens.branding.fontSize', 'Font size')}</InputLabel>
            <Select labelId='font-size' label={t('admin.screens.branding.fontSize', 'Font size')}
              value={profile?.fontSize || 0}
              onChange={e => setProfile({ ...(profile || {}), fontSize: e.target.value })}>
              {FONT_SIZE_OPTIONS.map(s => (
                <MenuItem key={s} value={s} sx={{ fontSize: 13 }}>
                  {s === 0 ? t('admin.screens.branding.fontSizeDefault', 'Default (13px)') : `${s}px`}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </Box>
        <Box sx={{ display: 'flex', gap: 0.75, flexWrap: 'wrap', mb: 1.5 }}>
          {FONT_SUGGESTIONS.map(o => (
            <Chip key={o.label} label={o.label} size='small' variant='outlined'
              onClick={() => setProfile({ ...(profile || {}), fontFamily: o.value })}
              sx={{ fontFamily: o.value || DEFAULT_FONT_STACK, fontSize: 12, cursor: 'pointer',
                bgcolor: (profile?.fontFamily || '') === o.value ? 'rgba(91, 157, 255, 0.14)' : 'transparent' }} />
          ))}
        </Box>
        <Box sx={{ border: '1px solid #243A72', borderRadius: 1, p: 1.5, mb: 1.5,
          fontFamily: profile?.fontFamily || DEFAULT_FONT_STACK,
          fontSize: profile?.fontSize || 13 }}>
          <Box sx={{ fontWeight: 700, mb: 0.5 }}>
            {t('admin.screens.branding.fontPreviewTitle', 'Live preview')}
          </Box>
          {t('admin.screens.branding.fontPreview',
            'The quick brown fox jumps over the lazy dog — Aa Bb Cc 0123456789')}
        </Box>
        <Button size='small' variant='contained'
          onClick={() => saveProfile(
            { fontFamily: profile?.fontFamily || '', fontSize: profile?.fontSize || 0 },
            t('admin.screens.branding.typographySaved', 'Font saved successfully.'))}>
          {t('admin.screens.branding.saveTypography', 'Save font')}
        </Button>
      </Paper>

      {/* Header status-dot thresholds — read by HeaderBar via the branding profile. */}
      <Typography fontWeight={700} fontSize={14} sx={{ mb: 1 }}>
        {t('admin.screens.branding.statusIndicator', 'Status indicator')}
      </Typography>
      <Paper variant='outlined' sx={{ p: 2, mb: 3 }}>
        <Typography fontSize={12.5} color='text.secondary' sx={{ mb: 1.5 }}>
          {t('admin.screens.branding.statusIndicatorHelp',
            'Colour of the header status dot by number of open tasks: green below the first value, blue below the second, amber up to the third, red above it.')}
        </Typography>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'center' }}>
          <TextField size='small' type='number' sx={{ width: 140 }}
            label={t('admin.screens.branding.statusGreen', 'Green below')}
            value={profile?.statusGreen ?? 15}
            onChange={e => setProfile({ ...(profile || {}), statusGreen: Number(e.target.value) })} />
          <TextField size='small' type='number' sx={{ width: 140 }}
            label={t('admin.screens.branding.statusBlue', 'Blue below')}
            value={profile?.statusBlue ?? 30}
            onChange={e => setProfile({ ...(profile || {}), statusBlue: Number(e.target.value) })} />
          <TextField size='small' type='number' sx={{ width: 140 }}
            label={t('admin.screens.branding.statusAmber', 'Amber up to')}
            value={profile?.statusAmber ?? 50}
            onChange={e => setProfile({ ...(profile || {}), statusAmber: Number(e.target.value) })} />
        </Box>
        <Button size='small' variant='contained' sx={{ mt: 2 }}
          onClick={() => saveProfile(
            { statusGreen: Number(profile?.statusGreen ?? 15),
              statusBlue:  Number(profile?.statusBlue  ?? 30),
              statusAmber: Number(profile?.statusAmber ?? 50) },
            t('admin.screens.branding.statusSaved', 'Status thresholds saved successfully.'))}>
          {t('admin.screens.branding.saveStatus', 'Save thresholds')}
        </Button>
      </Paper>

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1 }}>
        <Typography fontWeight={700} fontSize={14}>{t('admin.screens.branding.themeEditor', 'Theme editor')}</Typography>
        {!platformTemplate && (
          <Button size='small' startIcon={<SettingsBackupRestoreIcon fontSize='small' />}
            onClick={restoreThemeDefaults}
            sx={{ textTransform: 'none', fontSize: 12, color: '#C3D3F2' }}>
            {t('admin.screens.branding.restoreDefault', 'Restore to default')}
          </Button>
        )}
      </Box>
      <Paper variant='outlined' sx={{ p: 2, mb: 3 }}>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', mb: 1 }}>
          <TextField size='small' label={t('admin.screens.branding.themeName', 'Name')} value={theme.name}
            onChange={e => setTheme({ ...theme, name: e.target.value })} />
          <TextField size='small' label={t('admin.screens.branding.themeMode', 'Mode (light/dark/custom)')} value={theme.mode}
            onChange={e => setTheme({ ...theme, mode: e.target.value })} />
        </Box>

        {/* ── Palette: each colour with a caption + live sample of where it applies.
            A grid (not flex-wrap) keeps the cards on an even column and equal
            height; the caption reserves two lines and the sample is bottom-
            aligned so the previews line up across every card. ── */}
        <Box sx={{ display: 'grid', gap: 1.5, mb: 1,
          gridTemplateColumns: 'repeat(auto-fill, minmax(190px, 1fr))' }}>
          {Object.entries(theme.colors).map(([key, val]) => (
            <Box key={key} sx={{ display: 'flex', flexDirection: 'column', gap: 0.75,
              border: '1px solid #243A72', borderRadius: 1.5, p: 1.25 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                <Box component='input' type='color' value={val}
                  onChange={e => setTheme({ ...theme,
                    colors: { ...theme.colors, [key]: e.target.value } })}
                  sx={{ width: 34, height: 26, p: 0, cursor: 'pointer', flexShrink: 0,
                    border: '1px solid #243A72', borderRadius: 1, bgcolor: 'transparent',
                    '&::-webkit-color-swatch-wrapper': { p: '2px' },
                    '&::-webkit-color-swatch': { border: 'none', borderRadius: '3px' } }} />
                <Typography fontSize={12} fontWeight={700} sx={{ textTransform: 'capitalize' }}>
                  {t(`admin.screens.branding.color_${key}`, key)}
                </Typography>
              </Box>
              <Typography fontSize={10} color='#C3D3F2'
                sx={{ minHeight: 26, lineHeight: 1.3 }}>
                {t(`admin.screens.branding.colorUsage_${key}`, COLOR_USAGE[key] || '')}
              </Typography>
              <Box sx={{ mt: 'auto', minHeight: 30, display: 'flex', alignItems: 'center' }}>
                <ColorSample role={key} value={val} textColor={theme.colors.text} />
              </Box>
            </Box>
          ))}
        </Box>
        <TextField multiline minRows={6} fullWidth label={t('admin.screens.branding.customCss', 'Custom CSS (sanitised on save)')}
          value={theme.customCss}
          onChange={e => setTheme({ ...theme, customCss: e.target.value })}
          sx={{ fontFamily: 'monospace' }} />
        <Box mt={1} sx={{ display: 'flex', gap: 1 }}>
          <Button size='small' variant='contained' onClick={saveTheme}>{t('admin.screens.branding.saveTheme', 'Save theme')}</Button>
          <Button size='small' variant='outlined' onClick={requestPreview}>{t('admin.screens.branding.generatePreview', 'Generate preview')}</Button>
          {previewToken && (
            <Chip label={t('admin.screens.branding.previewToken', 'Preview token: {{token}}…', { token: previewToken.slice(0, 8) })}
              size='small' sx={{ alignSelf: 'center' }} />
          )}
        </Box>
      </Paper>

      {!platformTemplate && (<>
      <Divider sx={{ my: 2 }} />
      <Typography fontWeight={700} fontSize={14} mb={1}>{t('admin.screens.branding.existingThemes', 'Existing themes')}</Typography>
      <Paper variant='outlined'>
        {themes.length === 0 ? (
          <Box sx={{ p: 2, color: '#7D92C4', fontSize: 13 }}>{t('admin.screens.branding.noThemes', 'No themes saved.')}</Box>
        ) : themes.map(th => {
          const isActive = profile?.activeThemeId === th.id;
          return (
            <Box key={th.id} sx={{ p: 1.5, borderBottom: '1px solid #243A72',
              display: 'flex', alignItems: 'center', gap: 1.5,
              bgcolor: isActive ? 'rgba(91, 157, 255, 0.14)' : 'transparent', cursor: 'pointer' }}
              onClick={() => !isActive && saveProfile({ activeThemeId: th.id },
                t('admin.screens.branding.themeApplied', 'Theme "{{name}}" applied successfully.', { name: th.name }))}>
              <Tooltip title={isActive
                ? t('admin.screens.branding.themeActiveHint', 'This theme is currently in use')
                : t('admin.screens.branding.themeSelectHint', 'Select to apply this theme')}>
                <Radio size='small' checked={isActive} sx={{ p: 0.25 }}
                  onChange={() => !isActive && saveProfile({ activeThemeId: th.id },
                    t('admin.screens.branding.themeApplied', 'Theme "{{name}}" applied successfully.', { name: th.name }))} />
              </Tooltip>
              <Typography fontSize={13} fontWeight={600}>{th.name}</Typography>
              <Chip size='small' label={th.mode} />
              <Box sx={{ display: 'flex', gap: 0.5 }}>
                {Object.values(th.colors).map((c, i) =>
                  <Box key={i} sx={{ width: 18, height: 18, bgcolor: c, border: '1px solid #243A72' }} />)}
              </Box>
              {isActive && (
                <Chip size='small' label={t('admin.screens.branding.themeActive', 'Active')}
                  sx={{ fontSize: 10, bgcolor: 'rgba(61, 220, 151, 0.14)', color: '#3DDC97' }} />
              )}
            </Box>
          );
        })}
      </Paper>

      <Divider sx={{ my: 2 }} />
      <Typography fontWeight={700} fontSize={14} mb={0.5}>{t('admin.screens.branding.uiLabels', 'UI text & titles')}</Typography>
      <Typography fontSize={12} color='#C3D3F2' mb={1}>
        {t('admin.screens.branding.uiLabelsHint',
          'Rename any built-in title or text for this tenant, per language. Leave a field empty to use the default. Export the CSV template, fill the "value" column, and import it back for bulk changes.')}
      </Typography>
      <Paper variant='outlined' sx={{ p: 2, mb: 3 }}>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'center', mb: 1.5 }}>
          <FormControl size='small' sx={{ minWidth: 160 }}>
            <InputLabel id='label-lang'>{t('admin.screens.branding.labelLanguage', 'Language')}</InputLabel>
            <Select labelId='label-lang' label={t('admin.screens.branding.labelLanguage', 'Language')}
              value={labelLang} onChange={e => setLabelLang(e.target.value)}>
              {SUPPORTED_LANGUAGES.map(lang => (
                <MenuItem key={lang.code} value={lang.code} sx={{ fontSize: 13 }}>
                  {lang.nativeLabel} ({lang.label})
                </MenuItem>
              ))}
            </Select>
          </FormControl>
          <TextField size='small' sx={{ flex: 1, minWidth: 200 }}
            label={t('admin.screens.branding.labelFilter', 'Filter by key or text')}
            value={labelFilter} onChange={e => setLabelFilter(e.target.value)} />
          <Tooltip title={t('admin.screens.branding.labelsExport', 'Export CSV')}>
            <IconButton size='small' onClick={exportLabelsCsv}>
              <FileDownloadIcon fontSize='small' />
            </IconButton>
          </Tooltip>
          <Tooltip title={t('admin.screens.branding.labelsImport', 'Import CSV')}>
            <IconButton size='small' component='label'>
              <UploadFileIcon fontSize='small' />
              <input hidden type='file' accept='.csv,text/csv'
                onChange={e => {
                  if (e.target.files?.[0]) importLabelsCsv(e.target.files[0]);
                  e.target.value = '';
                }} />
            </IconButton>
          </Tooltip>
          <Tooltip title={t('admin.screens.branding.labelsRestore', 'Restore defaults')}>
            <IconButton size='small' color='warning' onClick={restoreLabelDefaults}>
              <SettingsBackupRestoreIcon fontSize='small' />
            </IconButton>
          </Tooltip>
          <Tooltip title={t('admin.screens.branding.labelsHistory', 'History')}>
            <IconButton size='small' onClick={loadLabelHistory}>
              <HistoryIcon fontSize='small' />
            </IconButton>
          </Tooltip>
        </Box>

        <Dialog open={labelHistory !== null} onClose={() => setLabelHistory(null)} maxWidth='md' fullWidth>
          <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
            {t('admin.screens.branding.labelsHistoryTitle', 'Label change history')}
          </DialogTitle>
          <DialogContent sx={{ pt: 0 }}>
            <Box sx={{ border: '1px solid #243A72', borderRadius: 1, maxHeight: 420, overflow: 'auto' }}>
              {(labelHistory || []).length === 0 ? (
                <Box sx={{ p: 2, color: '#7D92C4', fontSize: 12 }}>
                  {t('admin.screens.branding.labelsNoHistory', 'No label changes recorded yet.')}
                </Box>
              ) : (labelHistory || []).map((h, i) => (
                <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, px: 1.5, py: 0.75,
                  borderBottom: '1px solid #243A72', fontSize: 11 }}>
                  <Typography fontSize={11} color='#7D92C4' sx={{ width: 130, flexShrink: 0 }}>
                    {new Date(h.changedAt).toLocaleString()}
                  </Typography>
                  <Chip size='small' label={h.lang} sx={{ fontSize: 9 }} />
                  <Typography fontSize={11} fontFamily='monospace' sx={{ minWidth: 140 }} noWrap title={h.labelKey}>
                    {h.labelKey}
                  </Typography>
                  <Typography fontSize={11} color='#C3D3F2' sx={{ flex: 1 }} noWrap
                    title={`${h.oldValue || '(default)'} → ${h.newValue || '(default)'}`}>
                    {(h.oldValue || '(default)')} → {(h.newValue || '(default)')}
                  </Typography>
                  <Chip size='small' label={h.source || 'manual'} sx={{ fontSize: 9, bgcolor: '#0F2450' }} />
                  {h.changedBy && (
                    <Typography fontSize={10} color='#7D92C4' noWrap sx={{ maxWidth: 120 }} title={h.changedBy}>
                      {h.changedBy}
                    </Typography>
                  )}
                  <Button size='small' sx={{ fontSize: 10, textTransform: 'none', flexShrink: 0 }}
                    onClick={() => revertLabelChange(h)}>
                    {t('admin.screens.branding.labelRevert', 'Revert')}
                  </Button>
                </Box>
              ))}
            </Box>
          </DialogContent>
          <DialogActions sx={{ px: 3, pb: 2 }}>
            <Button size='small' onClick={() => setLabelHistory(null)}>
              {t('admin.screens.branding.labelsHistoryClose', 'Close')}
            </Button>
          </DialogActions>
        </Dialog>
        {labelReport?.errors?.length > 0 && (
          <Alert severity='warning' sx={{ mb: 1.5 }} onClose={() => setLabelReport(null)}>
            {t('admin.screens.branding.labelsImportIssues', 'Import finished with issues:')}
            <ul style={{ margin: '4px 0 0 16px', padding: 0 }}>
              {labelReport.errors.slice(0, 10).map((er, i) => <li key={i}>{er}</li>)}
              {labelReport.errors.length > 10 && <li>…</li>}
            </ul>
          </Alert>
        )}
        <Box sx={{ maxHeight: 380, overflow: 'auto', border: '1px solid #243A72', borderRadius: 1 }}>
          {(() => {
            const catalog = getBaseCatalog(labelLang);
            const overrides = labelDraft[labelLang] || {};
            const f = labelFilter.trim().toLowerCase();
            const keys = [...new Set([...Object.keys(catalog), ...Object.keys(overrides)])]
              .sort()
              .filter(k => !f
                || k.toLowerCase().includes(f)
                || (catalog[k] || '').toLowerCase().includes(f)
                || (overrides[k] || '').toLowerCase().includes(f));
            if (keys.length === 0) {
              return <Box sx={{ p: 2, color: '#7D92C4', fontSize: 13 }}>{t('admin.screens.branding.labelsNoMatch', 'No labels match the filter.')}</Box>;
            }
            return keys.map(key => (
              <Box key={key} sx={{ display: 'flex', alignItems: 'center', gap: 1.5, px: 1.5, py: 0.75,
                borderBottom: '1px solid #243A72', '&:hover': { bgcolor: '#152652' } }}>
                <Box sx={{ width: '32%', minWidth: 180 }}>
                  <Typography fontSize={12} fontFamily='monospace' noWrap title={key}>{key}</Typography>
                </Box>
                <Box sx={{ width: '30%', minWidth: 140 }}>
                  <Typography fontSize={12} color='#7D92C4' noWrap title={catalog[key] || ''}>
                    {catalog[key] || t('admin.screens.branding.labelNoDefault', '(no default)')}
                  </Typography>
                </Box>
                <TextField size='small' fullWidth variant='standard'
                  placeholder={catalog[key] || ''}
                  value={overrides[key] ?? ''}
                  onChange={e => setDraftValue(key, e.target.value)}
                  inputProps={{ style: { fontSize: 12 } }} />
              </Box>
            ));
          })()}
        </Box>
        <Box mt={1.5}>
          <Button size='small' variant='contained' onClick={saveLabels}>
            {t('admin.screens.branding.labelsSave', 'Save labels')}
          </Button>
        </Box>
      </Paper>
      </>)}
    </Box>
  );
}
