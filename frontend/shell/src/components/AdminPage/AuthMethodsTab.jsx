import { useEffect, useState, useCallback } from 'react';
import {
  Box, Typography, Paper, Table, TableHead, TableRow, TableCell, TableBody,
  Alert, Divider, CircularProgress, Chip, Button, Tooltip,
} from '@mui/material';
import RefreshIcon        from '@mui/icons-material/Refresh';
import CheckCircleIcon    from '@mui/icons-material/CheckCircle';
import RemoveCircleIcon   from '@mui/icons-material/RemoveCircleOutline';
import LockIcon           from '@mui/icons-material/Lock';
import { useTranslation } from 'react-i18next';
import axios from 'eip-shell-context/axiosAuth';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';

// Browser-facing Keycloak base URL — baked at build time, must match
// KC_HOSTNAME_URL in docker-compose.infra.yml.
const KEYCLOAK_BASE = (import.meta.env.VITE_KEYCLOAK_URL || 'http://localhost:8443').replace(/\/$/, '');

/**
 * Authentication tab — a READ-ONLY view of the current tenant's Keycloak realm
 * authentication configuration.
 *
 * It surfaces, straight from Keycloak (never mutating it):
 *   - the sign-in methods that are effectively available (local, OIDC, SAML, LDAP)
 *   - the realm's login flags (self-registration, forgot-password, etc.)
 *   - configured identity providers (SSO) and user federation
 *
 * The admin-service login analytics / alerts / audit are kept below as
 * supplementary, best-effort informational sections. Editing is intentionally
 * out of scope for now.
 */
export default function AuthMethodsTab() {
  const { t } = useTranslation();
  const [info,      setInfo]      = useState(null);   // RealmAuthInfo from Keycloak
  const [infoMsg,   setInfoMsg]   = useState(null);   // "no tenant" / unreachable message
  const [conn,      setConn]      = useState(null);   // Keycloak connection/endpoint status
  const [analytics, setAnalytics] = useState(null);
  const [alerts,    setAlerts]    = useState([]);
  const [audit,     setAudit]     = useState([]);
  const [loading,   setLoading]   = useState(true);
  const [err,       setErr]       = useState(null);

  const load = useCallback(async () => {
    setLoading(true); setErr(null); setInfoMsg(null);
    try {
      const r = await axios.get('/api/admin/auth/keycloak/auth-info');
      if (r.data && r.data.available === false) { setInfo(null); setInfoMsg(r.data.message); }
      else { setInfo(r.data); }
    } catch (e) {
      setErr(e?.response?.data?.message || 'Failed to load Keycloak authentication settings.');
    }
    // Connection status + supplementary sections — best-effort, never block the view.
    const [connRes, a, s, d] = await Promise.allSettled([
      axios.get('/api/admin/sso/status'),
      axios.get('/api/admin/auth/analytics'),
      axios.get('/api/admin/auth/security-alerts'),
      axios.get('/api/admin/auth/audit?limit=25'),
    ]);
    setConn(connRes.status === 'fulfilled' ? connRes.value.data : null);
    setAnalytics(a.status === 'fulfilled' ? a.value.data : null);
    setAlerts(s.status === 'fulfilled' ? s.value.data : []);
    setAudit(d.status === 'fulfilled' ? d.value.data : []);
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  if (loading) return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>;

  const idps = info?.identityProviders || [];
  const feds = info?.userFederation   || [];
  const idpEnabledOfType = frag => idps.some(p => p.enabled && (p.providerId || '').toLowerCase().includes(frag));

  // Effective sign-in methods, derived from Keycloak's live config.
  const methods = [
    ['Local', true,
      'Username / password login is always available on a Keycloak realm.'],
    ['OIDC', idpEnabledOfType('oidc'),
      'Enabled when an OIDC identity provider is configured and turned on.'],
    ['SAML', idpEnabledOfType('saml'),
      'Enabled when a SAML identity provider is configured and turned on.'],
    ['LDAP', feds.some(f => (f.providerId || '').toLowerCase() === 'ldap' && f.enabled),
      'Enabled when an LDAP user-federation provider is configured.'],
  ];

  const flags = info ? [
    ['User self-registration',   info.registrationAllowed],
    ['Forgot password',          info.resetPasswordAllowed],
    ['Remember me',              info.rememberMe],
    ['Verify email',             info.verifyEmail],
    ['Login with email',         info.loginWithEmailAllowed],
    ['Email used as username',   info.registrationEmailAsUsername],
    ['Users may edit username',  info.editUsernameAllowed],
    ['Brute-force protection',   info.bruteForceProtected],
  ] : [];

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.authMethods.scopeResource', 'authentication methods')} />

      <Alert severity='info' icon={<LockIcon fontSize='small' />} sx={{ mb: 2, fontSize: 13 }}>
        These settings are read live from this tenant&rsquo;s Keycloak realm and are
        shown for information only. Editing is not available here yet.
      </Alert>

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>
          Keycloak authentication{info?.realm ? ` — realm "${info.realm}"` : ''}
        </Typography>
        <Button size='small' startIcon={<RefreshIcon />} onClick={load}
          sx={{ textTransform: 'none', fontSize: 12 }}>Refresh</Button>
      </Box>

      {err && <Alert severity='error' sx={{ mb: 2, fontSize: 13 }}>{err}</Alert>}
      {infoMsg && <Alert severity='warning' sx={{ mb: 2, fontSize: 13 }}>{infoMsg}</Alert>}

      {/* ── Keycloak connection ── */}
      {conn && (
        <>
          <Typography fontWeight={700} fontSize={14} mb={1}>Keycloak connection</Typography>
          <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden', mb: 3 }}>
            {[
              ['Keycloak',      conn.keycloakReachable ? KEYCLOAK_BASE : 'Not reachable', conn.keycloakReachable],
              ['Realm',         conn.realm || (info?.realm) || '—',                       !!(conn.realm || info?.realm)],
              ['Issuer URL',    conn.issuer || '—',                                       !!conn.issuer],
              ['JWKS URI',      conn.jwksUri || '—',                                      !!conn.jwksUri],
              ['SPA Client ID', conn.clientId || 'orphos-spa',                            true],
              ['Auth Endpoint', conn.authorizationEndpoint || '—',                        !!conn.authorizationEndpoint],
            ].map(([label, value, ok], i, arr) => (
              <Box key={label} sx={{
                display: 'flex', alignItems: 'center', gap: 1, px: 2, py: 1.25,
                bgcolor: i % 2 === 0 ? '#152652' : '#0F2450',
                borderBottom: i < arr.length - 1 ? '1px solid #243A72' : 'none',
              }}>
                <Typography fontSize={12} fontWeight={600} color='#C3D3F2' sx={{ width: 140, flexShrink: 0 }}>{label}</Typography>
                <Typography fontSize={12} color='#FFFFFF' sx={{ flex: 1, wordBreak: 'break-all' }}>{value}</Typography>
                <BoolIcon on={ok} />
              </Box>
            ))}
          </Paper>
        </>
      )}

      {info && (
        <>
          {/* ── Sign-in methods ── */}
          <Typography fontWeight={700} fontSize={14} mb={1}>Sign-in methods</Typography>
          <Paper variant='outlined' sx={{ p: 2, mb: 3, display: 'flex', gap: 3, flexWrap: 'wrap' }}>
            {methods.map(([label, on, hint]) => (
              <Tooltip key={label} title={hint}>
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <BoolIcon on={on} />
                  <Typography fontSize={13} fontWeight={600}>{label}</Typography>
                  <Chip size='small' label={on ? 'Available' : 'Off'}
                    sx={{ fontSize: 10, fontWeight: 700,
                      bgcolor: on ? 'rgba(61, 220, 151, 0.14)' : '#0F2450',
                      color:   on ? '#3DDC97'  : '#7D92C4' }} />
                </Box>
              </Tooltip>
            ))}
          </Paper>

          {/* ── Realm login settings ── */}
          <Typography fontWeight={700} fontSize={14} mb={1}>Realm login settings</Typography>
          <Paper variant='outlined' sx={{ p: 2, mb: 3 }}>
            <Box sx={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(220px, 1fr))', gap: 1.5 }}>
              {flags.map(([label, on]) => (
                <Box key={label} sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <BoolIcon on={on} />
                  <Typography fontSize={13} color='#C3D3F2'>{label}</Typography>
                </Box>
              ))}
            </Box>
            <Divider sx={{ my: 1.5 }} />
            <Box sx={{ display: 'flex', gap: 3, flexWrap: 'wrap' }}>
              <Meta label='SSL required'          value={info.sslRequired || '—'} />
              <Meta label='Password policy'       value={info.passwordPolicy || 'None set'} />
              <Meta label='Access token lifespan' value={info.accessTokenLifespanSeconds != null ? `${info.accessTokenLifespanSeconds}s` : '—'} />
            </Box>
          </Paper>

          {/* ── Identity providers (SSO) ── */}
          <Box sx={{ mb: 3 }}>
          <CollapsibleSection title='Identity providers (SSO)' count={idps.length} detailsSx={{ p: 0 }}>
          <Paper variant='outlined' sx={{ overflow: 'hidden' }}>
            <Table size='small'>
              <TableHead><TableRow sx={{ bgcolor: '#0F2450' }}>
                {['Alias', 'Display name', 'Type', 'Enabled', 'Trust email', 'Issuer', 'Client ID'].map(h =>
                  <TableCell key={h} sx={{ fontWeight: 700, fontSize: 12, color: '#C3D3F2' }}>{h}</TableCell>)}
              </TableRow></TableHead>
              <TableBody>
                {idps.length === 0 ? (
                  <TableRow><TableCell colSpan={7} sx={{ textAlign: 'center', color: '#7D92C4', py: 3, fontSize: 13 }}>
                    No identity providers configured in Keycloak.
                  </TableCell></TableRow>
                ) : idps.map(p => (
                  <TableRow key={p.alias}>
                    <TableCell sx={{ fontSize: 13, fontWeight: 600 }}>{p.alias}</TableCell>
                    <TableCell sx={{ fontSize: 13 }}>{p.displayName || '—'}</TableCell>
                    <TableCell><Chip label={p.providerId} size='small' sx={{ fontSize: 10 }} /></TableCell>
                    <TableCell><BoolIcon on={p.enabled} /></TableCell>
                    <TableCell><BoolIcon on={p.trustEmail} /></TableCell>
                    <TableCell sx={{ fontSize: 11, color: '#C3D3F2', maxWidth: 260, wordBreak: 'break-all' }}>{p.issuer || '—'}</TableCell>
                    <TableCell sx={{ fontSize: 11, color: '#C3D3F2', maxWidth: 200, wordBreak: 'break-all' }}>{p.clientId || '—'}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </Paper>
          </CollapsibleSection>
          </Box>

          {/* ── User federation ── */}
          {feds.length > 0 && (
            <Box sx={{ mb: 3 }}>
              <CollapsibleSection title='User federation' count={feds.length} detailsSx={{ p: 0 }}>
              <Paper variant='outlined' sx={{ overflow: 'hidden' }}>
                <Table size='small'>
                  <TableHead><TableRow sx={{ bgcolor: '#0F2450' }}>
                    {['Name', 'Type', 'Enabled'].map(h =>
                      <TableCell key={h} sx={{ fontWeight: 700, fontSize: 12, color: '#C3D3F2' }}>{h}</TableCell>)}
                  </TableRow></TableHead>
                  <TableBody>
                    {feds.map(f => (
                      <TableRow key={f.name}>
                        <TableCell sx={{ fontSize: 13, fontWeight: 600 }}>{f.name}</TableCell>
                        <TableCell><Chip label={f.providerId} size='small' sx={{ fontSize: 10 }} /></TableCell>
                        <TableCell><BoolIcon on={f.enabled} /></TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>
              </Paper>
              </CollapsibleSection>
            </Box>
          )}
        </>
      )}

      {/* ── Supplementary login analytics / alerts / audit (admin-service) ── */}
      <Divider sx={{ my: 2 }} />

      <Typography fontWeight={700} fontSize={14} mb={1}>{t('admin.screens.authMethods.analytics24h', 'Analytics (24h)')}</Typography>
      {analytics ? (
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', mb: 2 }}>
          <Stat label={t('admin.screens.authMethods.statAttempts', 'Attempts')}  value={analytics.totalAttempts24h} />
          <Stat label={t('admin.screens.authMethods.statSuccesses', 'Successes')} value={analytics.successes24h} />
          <Stat label={t('admin.screens.authMethods.statFailures', 'Failures')}  value={analytics.failures24h} />
        </Box>
      ) : <Typography fontSize={12} color='#7D92C4' mb={2}>Analytics unavailable.</Typography>}

      <Box sx={{ mb: 3 }}>
      <CollapsibleSection title={t('admin.screens.authMethods.securityAlerts', 'Security alerts')}
        count={alerts.length} detailsSx={{ p: 0 }}>
      <Paper variant='outlined'>
        {alerts.length === 0 ? (
          <Box sx={{ p: 2, color: '#7D92C4', fontSize: 13 }}>{t('admin.screens.authMethods.noAlerts', 'No active alerts.')}</Box>
        ) : alerts.map(a => (
          <Box key={a.userKey} sx={{ p: 1.5, borderBottom: '1px solid #243A72' }}>
            <Typography fontSize={13}>
              <code>{a.userKey}</code> - {a.reason} - {t('admin.screens.authMethods.eventsCount', '{{count}} events', { count: a.count })}
            </Typography>
          </Box>
        ))}
      </Paper>
      </CollapsibleSection>
      </Box>

      <CollapsibleSection title={t('admin.screens.authMethods.auditLogLatest', 'Audit log (latest)')}
        count={audit.length} detailsSx={{ p: 0 }}>
      <Paper variant='outlined'>
        <Table size='small'>
          <TableBody>
            {audit.length === 0 ? (
              <TableRow><TableCell sx={{ color: '#7D92C4', fontSize: 13 }}>No audit entries.</TableCell></TableRow>
            ) : audit.map(e => (
              <TableRow key={e.id}>
                <TableCell sx={{ fontSize: 11, fontFamily: 'monospace', color: '#7D92C4' }}>
                  {new Date(e.at).toLocaleString()}
                </TableCell>
                <TableCell sx={{ fontSize: 12, fontWeight: 600 }}>{e.action}</TableCell>
                <TableCell sx={{ fontSize: 12 }}>{e.target}</TableCell>
                <TableCell sx={{ fontSize: 11, color: '#C3D3F2' }}>
                  {t('admin.screens.authMethods.by', 'by')} <code>{e.actorKey}</code>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
      </CollapsibleSection>
    </Box>
  );
}

function BoolIcon({ on }) {
  return on
    ? <CheckCircleIcon  sx={{ fontSize: 18, color: '#3DDC97' }} />
    : <RemoveCircleIcon sx={{ fontSize: 18, color: '#7D92C4' }} />;
}

function Meta({ label, value }) {
  return (
    <Box>
      <Typography fontSize={11} color='#C3D3F2'>{label}</Typography>
      <Typography fontSize={13} fontWeight={600} color='#FFFFFF'>{value}</Typography>
    </Box>
  );
}

function Stat({ label, value }) {
  return (
    <Paper variant='outlined' sx={{ p: 1.5, minWidth: 120 }}>
      <Typography fontSize={11} color='#C3D3F2'>{label}</Typography>
      <Typography fontWeight={700} fontSize={18}>{value}</Typography>
    </Paper>
  );
}
