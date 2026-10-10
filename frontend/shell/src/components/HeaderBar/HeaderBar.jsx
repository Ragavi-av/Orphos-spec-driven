import { useEffect, useRef, useState } from 'react';
import { Box, Typography, IconButton, Tooltip, Menu, MenuItem, Divider } from '@mui/material';
import RefreshIcon       from '@mui/icons-material/Refresh';
import NotificationsIcon from '@mui/icons-material/Notifications';
import LogoutIcon        from '@mui/icons-material/Logout';
import SettingsIcon      from '@mui/icons-material/Settings';
import { useTranslation } from 'react-i18next';
import { useAuth, useTenant } from 'eip-shell-context/AuthProvider';
import { useBranding, brandingAssetUrl } from 'eip-shell-context/BrandingProvider';
import { useRole } from 'eip-shell-context/useRole';
import { useRoleLabels } from 'eip-shell-context/RolesProvider';
import { tenantRedirectUri } from 'eip-shell-context/tenantResolver';
import { ORPHOS_COLORS } from 'eip-shell-context/orphosTheme';
import RolePicker from './RolePicker';
import { useCountry } from 'eip-shell-context/country';
import LanguageSwitcher from '../LanguageSwitcher/LanguageSwitcher';
import TimeFormatToggle from '../TimeFormatToggle/TimeFormatToggle';
import { useTimeFormat, hour12Options } from 'eip-shell-context/timeFormat';
import axios from 'eip-shell-context/axiosAuth';

const DEFAULT_PRODUCT_NAME = 'Orphos';
const DEFAULT_TAGLINE      = 'Executive Intelligence Platform';

// Task-count status indicator (the header dot). Colour bands are configurable
// per tenant in the AI Admin; these are the defaults. Reading order (see
// resolveStatusThresholds): tenant branding profile → local override → defaults.
//   count <  green → green (healthy) · <  blue → blue (steady)
//   count <= amber → amber (busy)    · >  amber → red  (overloaded)
const DEFAULT_STATUS_THRESHOLDS = { green: 15, blue: 30, amber: 50 };

function resolveStatusThresholds(brandingProfile) {
  // Admin-configured values live on the branding profile as flat fields
  // (Appearance → Branding / Platform Default). Only positive numbers count as set.
  const fromProfile = {};
  if (brandingProfile) {
    const { statusGreen, statusBlue, statusAmber } = brandingProfile;
    if (statusGreen > 0) fromProfile.green = statusGreen;
    if (statusBlue  > 0) fromProfile.blue  = statusBlue;
    if (statusAmber > 0) fromProfile.amber = statusAmber;
  }
  let fromLocal = null;
  try {
    const raw = localStorage.getItem('orphos.statusThresholds');
    if (raw) fromLocal = JSON.parse(raw);
  } catch { /* private mode / blocked storage — ignore */ }
  return { ...DEFAULT_STATUS_THRESHOLDS, ...(fromLocal || {}), ...fromProfile };
}

function statusFor(count, th) {
  const t = { ...DEFAULT_STATUS_THRESHOLDS, ...(th || {}) };
  const n = Number.isFinite(count) ? count : 0;
  if (n < t.green)  return { color: ORPHOS_COLORS.success, label: 'Healthy' };
  if (n < t.blue)   return { color: '#2F6FF0',             label: 'Steady' };
  if (n <= t.amber) return { color: '#F3B24A',             label: 'Busy' };
  return { color: ORPHOS_COLORS.critical, label: 'Overloaded' };
}

// The header's slice of the palette, named for what each colour does in this
// band. Every value comes from ORPHOS_COLORS — this is a lookup, not a second
// palette, so a change to the tokens moves the header with everything else.
// App.jsx imports it to paint the sticky wrapper the same band colour.
export const HEADER_COLORS = {
  band:      ORPHOS_COLORS.header,
  line:      ORPHOS_COLORS.line,
  text:      ORPHOS_COLORS.textPrimary,
  muted:     ORPHOS_COLORS.textGrey,
  mutedSoft: ORPHOS_COLORS.textDim,
  bright:    ORPHOS_COLORS.textBright,
  live:      ORPHOS_COLORS.success,
  alert:     ORPHOS_COLORS.critical,
  menuLine:  ORPHOS_COLORS.lineStrong,
  // One step BELOW the canvas, so a menu floating over the page reads as a
  // layer above it rather than as a hole in it. No surface token sits this
  // deep, because nothing else in the app floats.
  menuBg:    '#04102B',
};

/** Derive up to 2 initials from a display name, e.g. "Muthuvel Sivasuriyan" → "MS" */
function getInitials(name) {
  if (!name) return '?';
  const parts = name.trim().split(/\s+/);
  if (parts.length === 1) return parts[0][0].toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

// Identity gradients for the avatar. The design draws the avatar as a soft
// two-stop gradient rather than a flat fill, so each entry is a [from, to]
// pair; hues are spread across the deck's range to stay distinguishable at
// 34px, ordered so adjacent hash buckets are not neighbouring hues.
const AVATAR_GRADIENTS = [
  ['#5B9DFF', '#2F6FF0'],
  ['#3DDC97', '#17A673'],
  ['#FF5D6C', '#E0344A'],
  ['#F3B24A', '#DE8B18'],
  ['#7F6BFF', '#5A3FE0'],
  ['#4FC3F7', '#0288D1'],
];

/** Deterministic gradient from name — consistent across sessions */
function nameToGradient(name) {
  if (!name) return AVATAR_GRADIENTS[0];
  const idx = [...name].reduce((a, c) => a + c.charCodeAt(0), 0) % AVATAR_GRADIENTS.length;
  return AVATAR_GRADIENTS[idx];
}

/** Baseline-aligned divider between the brand name and its trailing labels. */
function brandDivider() {
  return { pl: 1.25, borderLeft: `1px solid ${HEADER_COLORS.line}` };
}

export default function HeaderBar({ onRefresh, onBellClick, trackerCount = 0, onAdminClick }) {
  const { t, i18n } = useTranslation();
  const keycloak = useAuth();
  const tenant   = useTenant();
  const branding = useBranding();
  const { role, activeRole, isPlatformAdmin } = useRole();
  const { aliases } = useRoleLabels();
  // Role label: tenant alias wins; otherwise the translated built-in label.
  const roleSlug  = isPlatformAdmin ? 'platform_admin' : (activeRole || 'guest');
  const roleLabel = aliases[roleSlug] || t(`roles.${roleSlug}`, role);
  // The header clock is a WORKSPACE clock, not a reading preference: both the
  // zone it runs in and the notation it is written in come from the tenant's
  // primary country, so switching the UI language leaves it where it is. Only
  // a tenant with no country configured falls back to the language.
  const { timezone: countryTz, numberLocale: countryLocale } = useCountry();
  // Subscribed for its re-render only: the clock preference lives in a module
  // singleton (non-React formatters read it too), so hour12Options() below
  // returns the fresh value but nothing would repaint the band without this.
  useTimeFormat();
  const dateLocale = countryLocale
    || ((i18n.language || 'en').startsWith('ja') ? 'ja-JP' : 'en-GB');
  const clockZone = countryTz || branding?.profile?.timezone || '';
  const [now, setNow]           = useState(new Date());
  const [photoUrl, setPhotoUrl] = useState(null);   // blob URL from Azure Graph
  const [menuEl, setMenuEl]     = useState(null);   // avatar menu anchor
  const [taskCount, setTaskCount] = useState(null); // open tasks → status dot colour
  const blobUrlRef              = useRef(null);      // track for cleanup

  // Brand text — falls back to the platform default when the tenant has not
  // configured a branding profile.
  const productName = branding?.profile?.productName?.trim() || DEFAULT_PRODUCT_NAME;
  // A tenant-configured tagline is shown verbatim (free text, single language);
  // only the built-in default is translated.
  const tagline     = branding?.profile?.tagline?.trim()     || t('header.tagline', DEFAULT_TAGLINE);
  const logoUrl     = brandingAssetUrl(branding?.profile?.logoAssetId);
  // Sub-line under the brand: prefer the configured branding profile name
  // (e.g. "Infosys"); fall back to the resolved tenant domain when no profile
  // name has been set.
  const profileLabel = branding?.profile?.name?.trim() || tenant?.domain;

  // JWT claims — populated by Azure via Keycloak broker
  const userDisplayName = keycloak?.tokenParsed?.name
    || keycloak?.tokenParsed?.preferred_username
    || 'User';

  // Clock tick — the line shows minutes, so 30s bounds how stale the displayed
  // minute can be without re-rendering the header every second.
  useEffect(() => {
    const timer = setInterval(() => setNow(new Date()), 30000);
    return () => clearInterval(timer);
  }, []);

  // Open-task count → drives the status-dot colour. Polled gently; failures leave
  // the dot at its last value (or green while unknown). The summary endpoint is
  // the same one the tab/bell badges use.
  useEffect(() => {
    if (!keycloak?.authenticated) return undefined;
    let cancelled = false;
    const load = () => axios.get('/api/mail-insight/summary')
      .then(r => {
        if (cancelled) return;
        const c = r?.data?.tasks ?? r?.data?.openTasks ?? r?.data?.count;
        if (c != null) setTaskCount(Number(c));
      })
      .catch(() => { /* degraded: keep last-known colour */ });
    load();
    const id = setInterval(load, 60000);
    return () => { cancelled = true; clearInterval(id); };
  }, [keycloak?.authenticated]);

  // Azure profile photo — fetched via Keycloak broker token endpoint → Microsoft Graph
  useEffect(() => {
    if (!keycloak?.authenticated || !keycloak?.token) return;

    let cancelled = false;

    const fetchPhoto = async () => {
      try {
        // 1. Retrieve the stored Azure access token from Keycloak
        const base       = (keycloak.authServerUrl || 'http://localhost:8443').replace(/\/$/, '');
        const brokerUrl  = `${base}/realms/${keycloak.realm}/broker/azure/token`;

        const brokerResp = await fetch(brokerUrl, {
          headers: { Authorization: `Bearer ${keycloak.token}` },
        });
        if (!brokerResp.ok) return;                      // token not stored yet
        const brokerData = await brokerResp.json();
        const azureToken = brokerData.access_token;
        if (!azureToken || cancelled) return;

        // 2. Fetch profile photo from Microsoft Graph (returns JPEG binary)
        const photoResp = await fetch('https://graph.microsoft.com/v1.0/me/photo/$value', {
          headers: { Authorization: `Bearer ${azureToken}` },
        });
        if (!photoResp.ok || cancelled) return;          // 404 = no photo set

        const blob    = await photoResp.blob();
        const blobUrl = URL.createObjectURL(blob);

        // Revoke previous blob URL to avoid memory leak
        if (blobUrlRef.current) URL.revokeObjectURL(blobUrlRef.current);
        blobUrlRef.current = blobUrl;

        if (!cancelled) setPhotoUrl(blobUrl);
      } catch {
        // Network error, CORS block, etc. — silently fall back to initials
      }
    };

    fetchPhoto();

    return () => {
      cancelled = true;
      // Blob URL cleaned up on unmount
      if (blobUrlRef.current) {
        URL.revokeObjectURL(blobUrlRef.current);
        blobUrlRef.current = null;
      }
    };
  }, [keycloak?.authenticated]); // eslint-disable-line react-hooks/exhaustive-deps

  // "Thu 20 Aug 2026 · 15:45" — abbreviated weekday keeps the stamp on one line
  // beside the avatar. 24-hour clock: this is an operations header, and "15:45"
  // is unambiguous in every locale we ship.
  //
  // The zone is the reader's country when they picked one, else the tenant's
  // configured zone, else the browser's. Showing the browser's clock under a
  // board the reader has just moved to Japan is the mismatch the country pill
  // exists to remove — and this stamp sits two pixels from that pill.
  const zoned = (opts) => {
    try {
      return now.toLocaleString(dateLocale, { timeZone: clockZone || undefined, ...opts });
    } catch {
      // An invalid tenant zone must not blank the header — fall back to the
      // browser's zone rather than losing the stamp entirely.
      return now.toLocaleString(dateLocale, opts);
    }
  };
  const dateStr = zoned({ weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' });
  // The reader's clock preference. This used to hardcode hour12:false, which
  // left the header the one time on the page that ignored its locale — every
  // other timestamp goes through datetime.js and let the locale decide. Now
  // both read the same preference, so the band agrees with the body.
  const timeStr = zoned({ hour: '2-digit', minute: '2-digit', ...hour12Options() });

  // Preserve the tenant's path-prefixed URL across logout so the user lands
  // back on their own tenant's login page, not the demo tenant.
  const handleLogout = () => keycloak?.logout({ redirectUri: tenantRedirectUri() });

  const closeMenu = () => setMenuEl(null);
  const runAndClose = fn => () => { closeMenu(); fn?.(); };

  const [gradFrom, gradTo] = nameToGradient(userDisplayName);

  return (
    <Box sx={{
      display: 'flex', alignItems: 'center', gap: 2,
      px: '18px', py: '11px',
    }}>

      {/* ── Brand mark: tenant logo when configured, gradient monogram tile
              otherwise (the design's default mark) ── */}
      {logoUrl ? (
        <Box
          component="img"
          src={logoUrl}
          alt={`${productName} logo`}
          // The asset endpoint returns 404 if the tenant's logo was deleted
          // out from under the cached profile — hide silently rather than
          // showing a broken-image icon.
          onError={e => { e.currentTarget.style.display = 'none'; }}
          sx={{ height: 32, maxWidth: 140, objectFit: 'contain', flexShrink: 0 }}
        />
      ) : (
        <Box sx={{
          width: 32, height: 32, borderRadius: '10px', flexShrink: 0,
          background: 'conic-gradient(from 210deg, #5B9DFF, #2F6FF0, #7F6BFF, #5B9DFF)',
          display: 'grid', placeItems: 'center',
          fontWeight: 800, fontSize: 14, color: '#FFFFFF', userSelect: 'none',
        }}>
          ✳
        </Box>
      )}

      {/* ── Brand line: product name, then tagline and tenant on the same
              baseline, each behind a hairline divider ── */}
      <Box sx={{ display: 'flex', alignItems: 'baseline', gap: 1.25, minWidth: 0 }}>
        <Typography fontSize={17} fontWeight={700} letterSpacing='0.2px' color='#FFFFFF' lineHeight={1.2}>
          {productName}
        </Typography>
        <Typography sx={{
          fontSize: 10.5, fontWeight: 600, letterSpacing: '1.1px',
          textTransform: 'uppercase', color: HEADER_COLORS.muted,
          whiteSpace: 'nowrap', ...brandDivider(),
        }}>
          {tagline}
        </Typography>
        {profileLabel && (
          <Typography sx={{
            fontSize: 10.5, fontWeight: 600, letterSpacing: '0.4px',
            color: HEADER_COLORS.mutedSoft, whiteSpace: 'nowrap',
            display: { xs: 'none', lg: 'block' }, ...brandDivider(),
          }}>
            {profileLabel}
          </Typography>
        )}
      </Box>

      <Box sx={{ flex: 1 }} />

      {/* ── Right cluster: role switch, language, live clock, alerts, avatar ── */}
      <RolePicker />

      {/* Language only. The clock beside it, the currency on the tiles below it
          and the greeting's time of day all come from the tenant's primary
          country, which is an admin setting (Admin → Branding) rather than a
          reader preference — see eip-shell-context/country. */}
      <LanguageSwitcher />

      {/* ── Live stamp + refresh ── */}
      <Box sx={{
        display: 'flex', alignItems: 'center', gap: 1.1,
        fontSize: 12, color: HEADER_COLORS.muted, fontVariantNumeric: 'tabular-nums',
      }}>
        {/* Task-load status indicator. Colour is driven by the number of open
            tasks against the tenant's configurable thresholds (AI Admin). */}
        {(() => {
          const th = resolveStatusThresholds(branding?.profile);
          const s = statusFor(taskCount, th);
          const title = taskCount == null
            ? t('header.status.unknown', 'Status')
            : `${s.label} · ${taskCount} ${t('header.status.openTasks', 'open tasks')}`;
          return (
            <Tooltip title={title}>
              <Box sx={{ width: 6, height: 6, borderRadius: '50%', bgcolor: s.color, flexShrink: 0, cursor: 'default' }} />
            </Tooltip>
          );
        })()}
        <Typography fontSize={12} color='inherit' whiteSpace='nowrap' lineHeight={1.3}>
          {dateStr} · {timeStr}
        </Typography>
        <Tooltip title={t('header.refresh')}>
          <IconButton size="small" onClick={onRefresh}
            sx={{ p: 0.25, color: HEADER_COLORS.bright, '&:hover': { color: '#FFFFFF', bgcolor: 'transparent' } }}>
            <RefreshIcon sx={{ fontSize: 16 }} />
          </IconButton>
        </Tooltip>
      </Box>

      {/* ── Alerts ── */}
      <Tooltip title={trackerCount > 0 ? t('header.needAttention', { n: trackerCount }) : t('header.actionTracker')}>
        <IconButton
          size="small" onClick={onBellClick}
          sx={{
            position: 'relative', color: HEADER_COLORS.muted,
            '&:hover': { color: HEADER_COLORS.bright, bgcolor: 'rgba(168, 187, 230, 0.10)' },
          }}
        >
          <NotificationsIcon sx={{ fontSize: 18 }} />
          {trackerCount > 0 && (
            <Box sx={{
              position: 'absolute', top: 2, right: 2,
              minWidth: 15, height: 15, px: '3px',
              borderRadius: 999, bgcolor: HEADER_COLORS.alert,
              border: `2px solid ${HEADER_COLORS.band}`,
              display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: 8, fontWeight: 800, color: '#FFFFFF', lineHeight: 1,
              userSelect: 'none',
            }}>
              {trackerCount > 99 ? '99+' : trackerCount}
            </Box>
          )}
        </IconButton>
      </Tooltip>

      {/* ── Avatar — photo if available, initials otherwise. Identity and admin
              live in its menu; sign-out is also mirrored as the control beside it. ── */}
      <Tooltip title={`${userDisplayName} · ${roleLabel}`}>
        <Box
          component="button"
          type="button"
          onClick={e => setMenuEl(e.currentTarget)}
          aria-haspopup="menu"
          aria-expanded={Boolean(menuEl)}
          aria-label={`${userDisplayName} · ${roleLabel}`}
          sx={{
            width: 34, height: 34, borderRadius: '50%', p: 0, flexShrink: 0,
            border: 'none', cursor: 'pointer', overflow: 'hidden',
            display: 'grid', placeItems: 'center',
            background: photoUrl ? 'transparent' : `linear-gradient(140deg, ${gradFrom}, ${gradTo})`,
            // Two-step ring: the band colour first so the outline reads as a gap,
            // then the hairline — the design's way of lifting the avatar off the bar.
            boxShadow: `0 0 0 2px ${HEADER_COLORS.band}, 0 0 0 3px ${HEADER_COLORS.menuLine}`,
          }}
        >
          {photoUrl ? (
            <Box component="img" src={photoUrl} alt=""
              sx={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
          ) : (
            <Typography sx={{ color: '#FFFFFF', fontWeight: 700, fontSize: 12.5, lineHeight: 1, userSelect: 'none' }}>
              {getInitials(userDisplayName)}
            </Typography>
          )}
        </Box>
      </Tooltip>

      {/* ── Sign out — the avatar menu still carries it, but the header keeps a
              one-click exit in the far corner so leaving never needs a menu. ── */}
      <Tooltip title={t('header.signOut')}>
        <IconButton
          size="small" onClick={handleLogout} aria-label={t('header.signOut')}
          sx={{
            color: HEADER_COLORS.muted,
            '&:hover': { color: HEADER_COLORS.alert, bgcolor: 'rgba(255, 93, 108, 0.14)' },
          }}
        >
          <LogoutIcon sx={{ fontSize: 18 }} />
        </IconButton>
      </Tooltip>

      <Menu
        anchorEl={menuEl}
        open={Boolean(menuEl)}
        onClose={closeMenu}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'right' }}
        transformOrigin={{ vertical: 'top', horizontal: 'right' }}
        slotProps={{
          paper: {
            sx: {
              mt: 1.25, width: 216, p: 1.5, borderRadius: '12px',
              bgcolor: HEADER_COLORS.menuBg,
              border: `1px solid ${HEADER_COLORS.menuLine}`,
              boxShadow: '0 18px 40px rgba(0,0,0,0.5)',
              '& .MuiList-root': { p: 0 },
            },
          },
        }}
      >
        {/* Identity header — not a menu item, so it stays unfocusable */}
        <Box sx={{ px: 0.5, pb: 0.5, outline: 'none' }}>
          <Typography fontSize={13.5} fontWeight={650} color={HEADER_COLORS.text} noWrap>
            {userDisplayName}
          </Typography>
          <Typography fontSize={11.5} color={HEADER_COLORS.muted} mt='2px' noWrap>
            {roleLabel}
          </Typography>
        </Box>

        <Divider sx={{ borderColor: HEADER_COLORS.line, mx: -1.5, my: 1.25 }} />

        {/* Reader preferences. Not a MenuItem, so picking a format leaves the
            menu open and the clock above visibly changes as you compare. */}
        <TimeFormatToggle colors={HEADER_COLORS} />

        <Divider sx={{ borderColor: HEADER_COLORS.line, mx: -1.5, my: 1.25 }} />

        {onAdminClick && (
          <MenuItem onClick={runAndClose(onAdminClick)}
            sx={{ fontSize: 12.5, color: HEADER_COLORS.bright, px: 0.5, py: 0.75, borderRadius: '6px', gap: 1,
                  '&:hover': { bgcolor: 'rgba(168, 187, 230, 0.10)' } }}>
            <SettingsIcon sx={{ fontSize: 16 }} />
            {t('header.admin')}
          </MenuItem>
        )}

        <MenuItem onClick={runAndClose(handleLogout)}
          sx={{ fontSize: 12.5, color: HEADER_COLORS.bright, px: 0.5, py: 0.75, borderRadius: '6px', gap: 1,
                '&:hover': { bgcolor: 'rgba(255, 93, 108, 0.14)', color: HEADER_COLORS.alert } }}>
          <LogoutIcon sx={{ fontSize: 16 }} />
          {t('header.signOut')}
        </MenuItem>
      </Menu>
    </Box>
  );
}
