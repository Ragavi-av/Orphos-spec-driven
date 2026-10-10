import { useState, useEffect, useCallback, useMemo } from 'react';
import {
  Box, Typography, Paper, Chip, Button, Alert, CircularProgress, Divider,
  ToggleButton, ToggleButtonGroup, Select, MenuItem, Tooltip,
} from '@mui/material';
import RefreshIcon from '@mui/icons-material/Refresh';
import LockIcon    from '@mui/icons-material/Lock';
import axios from 'axios';
import { useToast } from 'eip-shell-context/ToastProvider';
import { useRoleLabels } from 'eip-shell-context/RolesProvider';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import {
  ACCESS_GROUPS, LOCKED_RESOURCES, IMMUTABLE_ROLES, ADMIN_LOCKOUT_GUARD,
  GROUP_OF, toGrantMap, explicitEffect, effectiveAccess,
} from './accessCatalog';

// Tri-state control for a single (role, resource) grant. `noDeny` disables the
// Deny option (used by the admin self-lockout guard).
function EffectControl({ value, effective, disabled, noDeny, onChange }) {
  return (
    <ToggleButtonGroup size='small' exclusive disabled={disabled}
      value={value || 'inherit'}
      onChange={(_, v) => { if (v !== null) onChange(v === 'inherit' ? '' : v); }}
      sx={{ '& .MuiToggleButton-root': { py: 0.1, px: 1, fontSize: 11, textTransform: 'none' } }}>
      <ToggleButton value='inherit'>
        Inherit{!value && effective ? ` · ${effective}` : ''}
      </ToggleButton>
      <ToggleButton value='allow'
        sx={{ '&.Mui-selected': { bgcolor: 'rgba(61, 220, 151, 0.14)', color: '#3DDC97', '&:hover': { bgcolor: 'rgba(61, 220, 151, 0.14)' } } }}>
        Allow
      </ToggleButton>
      <Tooltip title={noDeny ? 'Admins cannot be locked out of Access Control' : ''}>
        <span>
          <ToggleButton value='deny' disabled={noDeny}
            sx={{ '&.Mui-selected': { bgcolor: 'rgba(255, 0, 0, 0.14)', color: '#FF6B81', '&:hover': { bgcolor: 'rgba(255, 0, 0, 0.14)' } } }}>
            Deny
          </ToggleButton>
        </span>
      </Tooltip>
    </ToggleButtonGroup>
  );
}

function LockedChip() {
  return <Chip size='small' icon={<LockIcon sx={{ fontSize: 13 }} />} label='Platform admin only'
    sx={{ fontSize: 10, fontWeight: 700, bgcolor: '#0F2450', color: '#C3D3F2' }} />;
}

export default function AccessControlTab() {
  const toast = useToast();
  const [grantMap, setGrantMap] = useState({});
  const [roles,    setRoles]    = useState([]);   // assignable roles (excludes immutable)
  const [locked,   setLocked]   = useState(LOCKED_RESOURCES);
  const [loading,  setLoading]  = useState(true);
  const [err,      setErr]      = useState(null);

  const [view, setView] = useState('role');       // 'role' | 'resource'
  const [selectedRole, setSelectedRole] = useState(null);
  const [selectedResource, setSelectedResource] = useState('auth');

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      const r = await axios.get('/api/admin/access-control');
      setGrantMap(toGrantMap(r.data.grants));
      const assignable = (r.data.roles || []).filter(x => !IMMUTABLE_ROLES.has(x));
      setRoles(assignable);
      setSelectedRole(prev => prev && assignable.includes(prev) ? prev : (assignable[0] ?? null));
      if (Array.isArray(r.data.lockedResources)) setLocked(new Set(r.data.lockedResources));
    } catch (e) {
      setErr(e?.response?.data?.error || 'Could not load the access map. Ensure a tenant is in scope.');
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const isLocked = useCallback(
    (key, groupKey) => locked.has(key) || (groupKey && locked.has(groupKey)),
    [locked]);

  // Optimistic single-grant write.
  const setGrant = useCallback(async (role, resourceKey, effect) => {
    if (IMMUTABLE_ROLES.has(role) || isLocked(resourceKey, GROUP_OF[resourceKey])) return;
    const prev = grantMap;
    setGrantMap(m => {
      const next = { ...m, [role]: { ...(m[role] || {}) } };
      if (effect) next[role][resourceKey] = effect; else delete next[role][resourceKey];
      return next;
    });
    try {
      await axios.put('/api/admin/access-control/grant', { role, resourceKey, effect });
    } catch (e) {
      setGrantMap(prev);
      toast.error(e?.response?.data?.error || 'Failed to save the grant.');
    }
  }, [grantMap, isLocked, toast]);

  // Flat resource options for the "by resource" selector (groups + their tabs).
  const resourceOptions = useMemo(() => {
    const opts = [];
    for (const g of ACCESS_GROUPS) {
      opts.push({ key: g.key, label: g.label, isGroup: true, groupKey: g.key });
      for (const t of g.tabs) opts.push({ key: t.key, label: t.label, isGroup: false, groupKey: g.key });
    }
    return opts;
  }, []);

  if (loading) return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>;

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1.5 }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>Access Control</Typography>
          <Typography fontSize={12} color='#7D92C4'>
            Map roles to admin tabs and user dashboard sections. Access flows down a group to
            its tabs unless a tab is set explicitly.
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <ToggleButtonGroup size='small' exclusive value={view}
            onChange={(_, v) => v && setView(v)}
            sx={{ '& .MuiToggleButton-root': { py: 0.3, px: 1.2, fontSize: 12, textTransform: 'none' } }}>
            <ToggleButton value='role'>By role</ToggleButton>
            <ToggleButton value='resource'>By tab / section</ToggleButton>
          </ToggleButtonGroup>
          <Button size='small' startIcon={<RefreshIcon />} onClick={load}
            sx={{ textTransform: 'none', fontSize: 12 }}>Refresh</Button>
        </Box>
      </Box>

      {err && <Alert severity='warning' sx={{ mb: 2, fontSize: 13 }}>{err}</Alert>}

      <Alert severity='info' sx={{ mb: 2, fontSize: 12 }}>
        <strong>platform_admin</strong> always has full access and cannot be changed. The Tenants tab and
        other platform-only screens can never be granted to other roles. Empty (Inherit) uses the
        role&rsquo;s default; set Allow/Deny to override.
      </Alert>

      {view === 'role' ? (
        <RoleView
          roles={roles} selectedRole={selectedRole} setSelectedRole={setSelectedRole}
          grantMap={grantMap} isLocked={isLocked} setGrant={setGrant} />
      ) : (
        <ResourceView
          roles={roles} resourceOptions={resourceOptions}
          selectedResource={selectedResource} setSelectedResource={setSelectedResource}
          grantMap={grantMap} isLocked={isLocked} setGrant={setGrant} />
      )}
    </Box>
  );
}

// ── By-role: pick a role, toggle every resource ─────────────────────────────
function RoleView({ roles, selectedRole, setSelectedRole, grantMap, isLocked, setGrant }) {
  const { roleLabel } = useRoleLabels();
  return (
    <>
      <Box sx={{ display: 'flex', gap: 1, mb: 2, alignItems: 'center' }}>
        <Typography fontSize={12} color='#C3D3F2' fontWeight={600}>Role:</Typography>
        <Select size='small' value={selectedRole || ''} displayEmpty
          onChange={e => setSelectedRole(e.target.value)}
          sx={{ fontSize: 13, minWidth: 220, fontWeight: 700, '& .MuiSelect-select': { py: 0.6 } }}>
          {roles.length === 0 && <MenuItem value='' disabled sx={{ fontSize: 13 }}>No assignable roles</MenuItem>}
          {roles.map(r => (
            <MenuItem key={r} value={r} sx={{ fontSize: 13, fontWeight: 700 }}>{roleLabel(r)}</MenuItem>
          ))}
        </Select>
        <Chip label={roleLabel('platform_admin')} size='small' icon={<LockIcon sx={{ fontSize: 13 }} />}
          sx={{ fontWeight: 700, fontSize: 11, bgcolor: 'rgba(255, 184, 77, 0.16)', color: '#FFB84D' }} />
      </Box>

      {!selectedRole ? (
        <Alert severity='info' sx={{ fontSize: 13 }}>No assignable roles found for this tenant.</Alert>
      ) : ACCESS_GROUPS.map(group => {
        const groupLocked = isLocked(group.key);
        return (
          <Box key={group.key} sx={{ mb: 1.5 }}>
          <CollapsibleSection title={group.label} count={group.tabs.length} detailsSx={{ p: 0 }}
            actions={groupLocked ? <LockedChip /> : (
              <EffectControl
                value={explicitEffect(grantMap, selectedRole, group.key) || ''}
                effective={effectiveAccess(grantMap, selectedRole, group.key, group.key)}
                noDeny={selectedRole === 'admin' && ADMIN_LOCKOUT_GUARD.has(group.key)}
                onChange={(v) => setGrant(selectedRole, group.key, v)} />
            )}>
            {group.tabs.map(tab => {
              const tabLocked = isLocked(tab.key, group.key);
              return (
                <Box key={tab.key} sx={{ display: 'flex', alignItems: 'center',
                  justifyContent: 'space-between', px: 2, py: 0.75, pl: 3.5,
                  borderBottom: '1px solid #243A72' }}>
                  <Typography fontSize={12.5} color='#C3D3F2'>{tab.label}</Typography>
                  {tabLocked ? <LockedChip /> : (
                    <EffectControl
                      value={explicitEffect(grantMap, selectedRole, tab.key) || ''}
                      effective={effectiveAccess(grantMap, selectedRole, tab.key, group.key)}
                      noDeny={selectedRole === 'admin' && ADMIN_LOCKOUT_GUARD.has(tab.key)}
                      onChange={(v) => setGrant(selectedRole, tab.key, v)} />
                  )}
                </Box>
              );
            })}
          </CollapsibleSection>
          </Box>
        );
      })}
    </>
  );
}

// ── By-resource: pick a tab/section, toggle every role ──────────────────────
function ResourceView({ roles, resourceOptions, selectedResource, setSelectedResource,
                        grantMap, isLocked, setGrant }) {
  const { roleLabel } = useRoleLabels();
  const opt = resourceOptions.find(o => o.key === selectedResource) || resourceOptions[0];
  const groupKey = opt?.groupKey;
  const resLocked = isLocked(opt?.key, groupKey);

  return (
    <>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 2 }}>
        <Typography fontSize={12} color='#C3D3F2' fontWeight={600}>Tab / section:</Typography>
        <Select size='small' value={opt?.key || ''} onChange={e => setSelectedResource(e.target.value)}
          sx={{ fontSize: 13, minWidth: 280 }}>
          {resourceOptions.map(o => (
            <MenuItem key={`${o.isGroup ? 'g' : 't'}:${o.key}`} value={o.key}
              sx={{ fontSize: 13, fontWeight: o.isGroup ? 700 : 400, pl: o.isGroup ? 2 : 4 }}>
              {o.isGroup ? o.label : `— ${o.label}`}
            </MenuItem>
          ))}
        </Select>
      </Box>

      <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
        <Box sx={{ px: 2, py: 1, bgcolor: '#0F2450', borderBottom: '1px solid #243A72',
          display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
          <Typography fontSize={13} fontWeight={700}>
            {opt?.isGroup ? `${opt.label} (whole group)` : opt?.label}
          </Typography>
          {resLocked && <LockedChip />}
        </Box>

        {resLocked ? (
          <Box sx={{ p: 2 }}>
            <Typography fontSize={12.5} color='#C3D3F2'>
              This is a platform-admin-only screen and cannot be granted to other roles.
            </Typography>
          </Box>
        ) : (
          <>
            {roles.map(role => (
              <Box key={role} sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                px: 2, py: 0.75, borderBottom: '1px solid #243A72' }}>
                <Typography fontSize={12.5} fontWeight={600} color='#C3D3F2'>{roleLabel(role)}</Typography>
                <EffectControl
                  value={explicitEffect(grantMap, role, opt.key) || ''}
                  effective={effectiveAccess(grantMap, role, opt.key, groupKey)}
                  noDeny={role === 'admin' && ADMIN_LOCKOUT_GUARD.has(opt.key)}
                  onChange={(v) => setGrant(role, opt.key, v)} />
              </Box>
            ))}
            <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              px: 2, py: 0.75, bgcolor: 'rgba(255, 184, 77, 0.16)' }}>
              <Typography fontSize={12.5} fontWeight={700} color='#FFB84D'>{roleLabel('platform_admin')}</Typography>
              <Chip size='small' label='Allow (locked)' icon={<LockIcon sx={{ fontSize: 13 }} />}
                sx={{ fontSize: 10, fontWeight: 700, bgcolor: 'rgba(255, 184, 77, 0.16)', color: '#FFB84D' }} />
            </Box>
          </>
        )}
      </Paper>
    </>
  );
}
