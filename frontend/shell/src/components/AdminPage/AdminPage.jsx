import { useState, useEffect, useCallback, useMemo } from 'react';
import { Trans, useTranslation, I18nextProvider } from 'react-i18next';
import { createScopedI18n } from 'eip-shell-context/i18n';
import {
  Box, Typography, Tabs, Tab, Paper, Chip, IconButton, ButtonBase, Avatar,
  Table, TableHead, TableRow, TableCell, TableBody,
  Select, MenuItem, Tooltip, CircularProgress, Divider,
  Button, Alert, Badge, Switch, TextField,
  Dialog, DialogTitle, DialogContent, DialogActions,
} from '@mui/material';
import CheckCircleIcon    from '@mui/icons-material/CheckCircle';
import EditNoteIcon       from '@mui/icons-material/EditNote';
import ErrorIcon          from '@mui/icons-material/Error';
import AddIcon            from '@mui/icons-material/Add';
import EditIcon           from '@mui/icons-material/Edit';
import DeleteIcon         from '@mui/icons-material/Delete';
import RefreshIcon        from '@mui/icons-material/Refresh';
import PeopleIcon         from '@mui/icons-material/People';
import SecurityIcon       from '@mui/icons-material/Security';
import ContentCopyIcon    from '@mui/icons-material/ContentCopy';
import OpenInNewIcon      from '@mui/icons-material/OpenInNew';
import LockIcon           from '@mui/icons-material/Lock';
import PaletteIcon        from '@mui/icons-material/Palette';
import MailIcon           from '@mui/icons-material/Mail';
import ForumIcon          from '@mui/icons-material/Forum';
import NotificationsActiveIcon from '@mui/icons-material/NotificationsActive';
import BusinessIcon       from '@mui/icons-material/Business';
import SmartToyIcon       from '@mui/icons-material/SmartToy';
import DashboardIcon      from '@mui/icons-material/Dashboard';
import StorageIcon        from '@mui/icons-material/Storage';
import HistoryIcon        from '@mui/icons-material/History';
import ArticleIcon        from '@mui/icons-material/Article';
import FolderIcon         from '@mui/icons-material/Folder';
import UploadFileIcon     from '@mui/icons-material/UploadFile';
import ManageSearchIcon   from '@mui/icons-material/ManageSearch';
import AccountTreeIcon    from '@mui/icons-material/AccountTree';
import ExtensionIcon      from '@mui/icons-material/Extension';
import TuneIcon           from '@mui/icons-material/Tune';
import SettingsSuggestIcon from '@mui/icons-material/SettingsSuggest';
import LogoutIcon          from '@mui/icons-material/Logout';
import SwapHorizIcon       from '@mui/icons-material/SwapHoriz';
import ScheduleIcon        from '@mui/icons-material/Schedule';
import AutoAwesomeIcon     from '@mui/icons-material/AutoAwesome';
import ContrastIcon        from '@mui/icons-material/Contrast';
import axios from 'axios';
import AuthMethodsTab        from './AuthMethodsTab';
import AccessControlTab       from './AccessControlTab';
import { toGrantMap, anyRoleAllows } from './accessCatalog';
import { ADMIN_GROUP_ORDER, ADMIN_SCREENS } from './screenRegistry';
import EventAlertsTab        from './EventAlertsTab';
import BrandingTab           from './BrandingTab';
import TenantManagementTab   from './TenantManagementTab';
import OnboardingTab         from './OnboardingTab';
import DataSourcesTab        from './DataSourcesTab';
import DataMappingTab        from './DataMappingTab';
import IngestionJobsTab      from './IngestionJobsTab';
import ServicesTab           from './ServicesTab';
import DbConnectionsTab      from './DbConnectionsTab';
import ServiceLogsTab        from './ServiceLogsTab';
import PluginsTab            from './PluginsTab';
import PlatformBasesTab      from './PlatformBasesTab';
import AgentsTab             from './AgentsTab';
import CapabilityCatalogTab  from './CapabilityCatalogTab';
import MobileAccessTab       from './MobileAccessTab';
import PhoneIphoneIcon       from '@mui/icons-material/PhoneIphone';
import VoiceTab              from './VoiceTab';
import GraphicEqIcon         from '@mui/icons-material/GraphicEq';
import AgentApprovalsTab     from './AgentApprovalsTab';
import DataFlow              from '../DataFlow/DataFlow';
import AdminGuard         from './AdminGuard';
import TenantScopeBanner  from 'eip-shell-context/TenantScopeBanner';
import PlatformTenantPicker from '../HeaderBar/PlatformTenantPicker';
import LanguageSwitcher     from '../LanguageSwitcher/LanguageSwitcher';
import PluginLoader        from '../../PluginLoader';
import { useRole }        from 'eip-shell-context/useRole';
import { useAuth }        from 'eip-shell-context/AuthProvider';
import { useBranding }    from 'eip-shell-context/BrandingProvider';
import { useToast }       from 'eip-shell-context/ToastProvider';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import { notifyRolesChanged } from 'eip-shell-context/RolesProvider';
import { getActingTenant, onActingTenantChange } from 'eip-shell-context/actingTenant';
import { tenantRedirectUri } from 'eip-shell-context/tenantResolver';

import { SUPPORTED_LANGUAGES } from 'eip-shell-context/i18n';
import { ORPHOS_TOKENS, ORPHOS_COLORS, ORPHOS_SHAPE } from 'eip-shell-context/orphosTheme';

// Admin tabs contributed by plugin remotes (AIProvidersTab, LLMPromptsTab, etc.
// used to live in this directory as local files; they moved into their owning
// remote's src/admin/ alongside the rest of that feature domain — see each
// plugin's PluginManifest.adminSectionsJson in platform-registry-service's
// data.sql seed). tabKey -> [pluginId, adminSection].
// Admin tabs whose body is a Module Federation remote — derived from the screen
// registry so it never drifts from the tab list. tabKey → [pluginId, adminSection].
const PLUGIN_ADMIN_MAP = Object.fromEntries(
  ADMIN_SCREENS.filter(s => s.plugin).map(s => [s.key, s.plugin])
);

// Icon per admin tab key. Kept here (not in the registry) so the registry stays
// pure data; every key in ADMIN_SCREENS must have an entry.
const TAB_ICON = {
  tenants:               <BusinessIcon         sx={{ fontSize: 16 }} />,
  services:              <StorageIcon          sx={{ fontSize: 16 }} />,
  'db-connections':      <StorageIcon          sx={{ fontSize: 16 }} />,
  bases:                 <AccountTreeIcon      sx={{ fontSize: 16 }} />,
  logs:                  <ArticleIcon          sx={{ fontSize: 16 }} />,
  auth:                  <LockIcon             sx={{ fontSize: 16 }} />,
  users:                 <PeopleIcon           sx={{ fontSize: 16 }} />,
  'access-control':      <SecurityIcon         sx={{ fontSize: 16 }} />,
  onboarding:            <UploadFileIcon       sx={{ fontSize: 16 }} />,
  'data-sources':        <StorageIcon          sx={{ fontSize: 16 }} />,
  'data-mapping':        <SwapHorizIcon        sx={{ fontSize: 16 }} />,
  'ingestion-jobs':      <ScheduleIcon         sx={{ fontSize: 16 }} />,
  queries:               <HistoryIcon          sx={{ fontSize: 16 }} />,
  ontology:              <AccountTreeIcon      sx={{ fontSize: 16 }} />,
  ai:                    <SmartToyIcon         sx={{ fontSize: 16 }} />,
  prompts:               <EditNoteIcon         sx={{ fontSize: 16 }} />,
  'doc-ai':              <SmartToyIcon         sx={{ fontSize: 16 }} />,
  'email-ai':            <SmartToyIcon         sx={{ fontSize: 16 }} />,
  'comms-ai':            <SmartToyIcon         sx={{ fontSize: 16 }} />,
  'doc-ingest-defaults': <SettingsSuggestIcon  sx={{ fontSize: 16 }} />,
  'doc-sources':         <StorageIcon          sx={{ fontSize: 16 }} />,
  'doc-upload':          <UploadFileIcon       sx={{ fontSize: 16 }} />,
  'doc-list':            <FolderIcon           sx={{ fontSize: 16 }} />,
  'doc-search-settings': <TuneIcon             sx={{ fontSize: 16 }} />,
  'doc-queries':         <ManageSearchIcon     sx={{ fontSize: 16 }} />,
  email:                 <MailIcon             sx={{ fontSize: 16 }} />,
  'event-alerts':        <NotificationsActiveIcon sx={{ fontSize: 16 }} />,
  'mail-monitor':        <MailIcon             sx={{ fontSize: 16 }} />,
  channels:              <ForumIcon            sx={{ fontSize: 16 }} />,
  branding:              <PaletteIcon          sx={{ fontSize: 16 }} />,
  dashboard:             <DashboardIcon        sx={{ fontSize: 16 }} />,
  'platform-branding':   <PaletteIcon          sx={{ fontSize: 16 }} />,
  plugins:               <ExtensionIcon        sx={{ fontSize: 16 }} />,
  agents:                <SmartToyIcon         sx={{ fontSize: 16 }} />,
  'agent-approvals':     <CheckCircleIcon      sx={{ fontSize: 16 }} />,
  'mobile-access':       <PhoneIphoneIcon      sx={{ fontSize: 16 }} />,
  capabilities:          <AccountTreeIcon      sx={{ fontSize: 16 }} />,
  voice:                 <GraphicEqIcon        sx={{ fontSize: 16 }} />,
};

// ── Shell layout ─────────────────────────────────────────────────────────
// Two inset values, one per column, so everything in a column shares a left
// edge: in the rail the title, every group icon and the avatar; in the main
// column the page title, the first sub-tab's icon and the tab body. RAIL_GUTTER
// is the nav list's own padding — the group pills inset by it, and each pill
// pads by the remainder, which is what puts their icons back on RAIL_INSET.
const SIDEBAR_W  = 270;
const RAIL_GUTTER = 10;
const RAIL_INSET  = 24;
const MAIN_INSET  = 24;
// Both columns' header blocks share this height so their bottom hairlines meet
// across the split and read as one rule.
const HEADER_H = 88;

// Icon per admin GROUP, for the sidebar rail. Keyed by the group keys in
// ADMIN_GROUP_ORDER; a group without an entry falls back to TuneIcon.
const GROUP_ICON = {
  'security-ops': <TuneIcon         sx={{ fontSize: 18 }} />,
  'auth-authz':   <SecurityIcon     sx={{ fontSize: 18 }} />,
  data:           <StorageIcon      sx={{ fontSize: 18 }} />,
  ai:             <AutoAwesomeIcon  sx={{ fontSize: 18 }} />,
  documents:      <UploadFileIcon   sx={{ fontSize: 18 }} />,
  comms:          <MailIcon         sx={{ fontSize: 18 }} />,
  appearance:     <ContrastIcon     sx={{ fontSize: 18 }} />,
  platform:       <ExtensionIcon    sx={{ fontSize: 18 }} />,
};

// ── constants ────────────────────────────────────────────────────────────────
const ROLES       = ['admin', 'ceo', 'executive', 'viewer', 'guest'];
const ROLE_COLORS = {
  admin:     { bg: 'rgba(255, 184, 77, 0.18)', color: '#FFB84D' },
  ceo:       { bg: 'rgba(91, 157, 255, 0.18)', color: '#8DBCFF' },
  executive: { bg: 'rgba(61, 220, 151, 0.18)', color: '#3DDC97' },
  // The two read-only roles take neutral tints rather than a status hue —
  // "viewer" and "guest" are not a severity. Guest is the quieter of the two,
  // so it takes the slate caption tone.
  viewer:    { bg: 'rgba(195, 211, 242, 0.14)', color: '#C3D3F2' },
  guest:     { bg: 'rgba(125, 146, 196, 0.18)', color: '#A8BBE6' },
};

// Tri-state: ok === true → OK, ok === false → Not configured, null/undefined →
// Unknown (we couldn't determine — distinct from a definitive failure).
function StatusBadge({ ok, label }) {
  const known = ok === true || ok === false;
  const color = ok === true ? '#3DDC97' : ok === false ? '#FF6B81' : '#7D92C4';
  const text  = label != null ? label
              : ok === true ? 'OK' : ok === false ? 'Not configured' : 'Unknown';
  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75 }}>
      {ok === true
        ? <CheckCircleIcon sx={{ fontSize: 16, color: '#3DDC97' }} />
        : ok === false
        ? <ErrorIcon       sx={{ fontSize: 16, color: '#FF6B81' }} />
        : <Box sx={{ width: 12, height: 12, borderRadius: '50%', border: '2px solid #2E4A8C' }} />}
      <Typography fontSize={13} color={color} fontWeight={600}>
        {text}
      </Typography>
    </Box>
  );
}

// NOTE: The former "SSO Configuration" tab (connection status + identity
// providers + role flow + quick links) was removed. Its live Keycloak data now
// lives in the read-only Authentication tab (AuthMethodsTab): the connection
// panel, realm settings, identity providers, and user federation.

// ── User Management Tab ───────────────────────────────────────────────────────
function UserManagementTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const [users,   setUsers]   = useState([]);
  const [loading, setLoading] = useState(true);
  const [saving,  setSaving]  = useState({});
  const [error,   setError]   = useState(null);

  // Add-user dialog
  const emptyAddForm = { displayName: '', email: '', role: 'viewer', password: '', requireReset: true };
  const [addOpen,   setAddOpen]   = useState(false);
  const [addForm,   setAddForm]   = useState(emptyAddForm);
  const [addBusy,   setAddBusy]   = useState(false);
  const [addError,  setAddError]  = useState(null);

  // Edit-user dialog
  const [editUser,  setEditUser]  = useState(null);   // the user row being edited
  const [editForm,  setEditForm]  = useState({ displayName: '', email: '' });
  const [editBusy,  setEditBusy]  = useState(false);
  const [editError, setEditError] = useState(null);

  // Delete confirmation
  const [deleteUser, setDeleteUser] = useState(null);
  const [deleteBusy, setDeleteBusy] = useState(false);

  // ── Roles (custom + built-in) with aliases ────────────────────────────────
  const [roleDefs, setRoleDefs]     = useState([]);   // [{key,alias,color,custom,isDefault,userCount,deletable}]
  const [rolesLoading, setRolesLoading] = useState(true);
  const [roleDialog, setRoleDialog] = useState(null); // { mode:'create'|'edit'|'copy', draft, sourceKey }
  const [roleBusy,   setRoleBusy]   = useState(false);
  const [roleErr,    setRoleErr]    = useState(null);
  const [resetBusy,  setResetBusy]  = useState(false);

  const roleByKey = useMemo(() => {
    const m = {};
    roleDefs.forEach(r => { m[r.key] = r; });
    return m;
  }, [roleDefs]);

  // Assignable roles for the user dropdowns: everything except guest is offered
  // (guest is the implicit unassigned state); fall back to a minimal set before
  // the list loads so the selects are never empty.
  const assignableRoles = roleDefs.length
    ? roleDefs.map(r => r.key)
    : ['admin', 'ceo', 'executive', 'viewer', 'guest'];

  const loadRoles = useCallback(async () => {
    setRolesLoading(true);
    try {
      const r = await axios.get('/api/admin/roles');
      setRoleDefs(Array.isArray(r.data) ? r.data : []);
    } catch {
      setRoleDefs([]);
    }
    setRolesLoading(false);
  }, []);
  useEffect(() => { loadRoles(); }, [loadRoles]);

  const roleAlias = (key) => roleByKey[key]?.alias || key;
  const roleColorChip = (key) => {
    const c = roleByKey[key]?.color;
    return c ? { bgcolor: c + '3d', color: c } : (ROLE_COLORS[key] ?? ROLE_COLORS.guest);
  };

  const openCreateRole = () => {
    setRoleErr(null);
    setRoleDialog({ mode: 'create', draft: { key: '', alias: '', color: '#8DBCFF', basedOn: '' } });
  };
  const openEditRole = (r) => {
    setRoleErr(null);
    setRoleDialog({ mode: 'edit', sourceKey: r.key, draft: { key: r.key, alias: r.alias, color: r.color || '#8DBCFF' } });
  };
  const openCopyRole = (r) => {
    setRoleErr(null);
    setRoleDialog({ mode: 'copy', sourceKey: r.key,
      draft: { key: '', alias: (r.alias || r.key) + ' (copy)', color: r.color || '#8DBCFF', basedOn: r.key } });
  };

  const saveRole = async () => {
    const d = roleDialog.draft;
    if (roleDialog.mode !== 'edit' && !/^[a-z][a-z0-9_]{1,35}$/.test(d.key || '')) {
      setRoleErr('Role key must be lowercase letters/digits/underscore, starting with a letter (2–36 chars).');
      return;
    }
    if (!d.alias.trim()) { setRoleErr('A display name (alias) is required.'); return; }
    setRoleBusy(true); setRoleErr(null);
    try {
      if (roleDialog.mode === 'create') {
        await axios.post('/api/admin/roles', { key: d.key.trim(), alias: d.alias.trim(), color: d.color, basedOn: d.basedOn || null });
      } else if (roleDialog.mode === 'copy') {
        await axios.post(`/api/admin/roles/${encodeURIComponent(roleDialog.sourceKey)}/copy`,
          { key: d.key.trim(), alias: d.alias.trim(), color: d.color });
      } else {
        await axios.put(`/api/admin/roles/${encodeURIComponent(roleDialog.sourceKey)}`,
          { alias: d.alias.trim(), color: d.color });
      }
      setRoleDialog(null);
      await loadRoles();
      notifyRolesChanged();
      toast.success(roleDialog.mode === 'edit' ? 'Role updated.' : 'Role saved.');
    } catch (e) {
      setRoleErr(e.response?.data?.error || 'Saving the role failed.');
    }
    setRoleBusy(false);
  };

  const deleteRole = async (r) => {
    if (!window.confirm(`Delete role "${r.alias}" (${r.key})? This removes it from the tenant.`)) return;
    try {
      await axios.delete(`/api/admin/roles/${encodeURIComponent(r.key)}`);
      await loadRoles();
      notifyRolesChanged();
      toast.success(`Role "${r.alias}" deleted.`);
    } catch (e) {
      const msg = e.response?.status === 409
        ? (e.response?.data?.error || 'Role is still assigned to users — reassign them first.')
        : (e.response?.data?.error || 'Deleting the role failed.');
      setError(msg);
    }
  };

  const resetRoles = async () => {
    if (!window.confirm('Reset roles to the application defaults? This removes custom roles (that have no users) and clears all aliases for this tenant.')) return;
    setResetBusy(true);
    try {
      const r = await axios.post('/api/admin/roles/reset');
      await loadRoles();
      notifyRolesChanged();
      const kept = r.data?.kept || [];
      toast.success(kept.length
        ? `Roles reset. Kept ${kept.length} in-use custom role(s): ${kept.join(', ')} — reassign their users to remove them.`
        : 'Roles reset to defaults.');
    } catch (e) {
      setError(e.response?.data?.error || 'Reset failed.');
    }
    setResetBusy(false);
  };

  const loadUsers = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const r = await axios.get('/api/admin/scim/v2/Users');
      // SCIM 2.0 response: { Resources: [...], totalResults }
      setUsers(r.data.Resources ?? r.data.users ?? []);
    } catch {
      setError('Could not load users. Ensure the admin-service is running.');
    }
    setLoading(false);
  }, []);

  useEffect(() => { loadUsers(); }, [loadUsers]);

  const changeRole = async (userId, newRole) => {
    setSaving(s => ({ ...s, [userId]: true }));
    try {
      await axios.patch(`/api/admin/scim/v2/Users/${userId}`, {
        schemas: ['urn:ietf:params:scim:api:messages:2.0:PatchOp'],
        Operations: [{ op: 'replace', path: 'roles', value: [{ value: newRole, primary: true }] }],
      });
      setUsers(u => u.map(user =>
        user.id === userId ? { ...user, currentRole: newRole } : user
      ));
      toast.success(`Role updated to "${roleAlias(newRole)}". Takes effect on the user's next login.`);
    } catch {
      setError(`Failed to update role for user ${userId}`);
    }
    setSaving(s => ({ ...s, [userId]: false }));
  };

  const changeLanguage = async (userId, lang) => {
    setSaving(s => ({ ...s, [`lang-${userId}`]: true }));
    try {
      await axios.patch(`/api/admin/scim/v2/Users/${userId}`, {
        schemas: ['urn:ietf:params:scim:api:messages:2.0:PatchOp'],
        Operations: [{ op: 'replace', path: 'preferredLanguage', value: lang }],
      });
      setUsers(u => u.map(user =>
        user.id === userId ? { ...user, preferredLanguage: lang } : user
      ));
      const label = SUPPORTED_LANGUAGES.find(l => l.code === lang)?.nativeLabel || lang;
      toast.success(`Preferred language set to ${label} for this user.`);
    } catch {
      setError(`Failed to update language for user ${userId}`);
    }
    setSaving(s => ({ ...s, [`lang-${userId}`]: false }));
  };

  // ── Create ──────────────────────────────────────────────────────────────
  const openAdd = () => { setAddForm(emptyAddForm); setAddError(null); setAddOpen(true); };

  const submitAdd = async () => {
    const email = addForm.email.trim();
    if (!email) { setAddError('An email address is required.'); return; }
    if (!addForm.password) { setAddError('Set an initial password for the user.'); return; }
    setAddBusy(true); setAddError(null);
    try {
      const r = await axios.post('/api/admin/scim/v2/Users', {
        schemas: ['urn:ietf:params:scim:schemas:core:2.0:User'],
        userName: email,
        emails: [{ value: email, primary: true }],
        displayName: addForm.displayName.trim(),
        password: addForm.password,
        temporaryPassword: addForm.requireReset,
        roles: [{ value: addForm.role, primary: true }],
      });
      setUsers(u => [...u, r.data]);
      setAddOpen(false);
      toast.success(`User "${email}" created${addForm.requireReset ? ' — they must set a new password on first login.' : '.'}`);
    } catch (e) {
      setAddError(e.response?.data?.error || 'Failed to create the user. The username or email may already exist.');
    }
    setAddBusy(false);
  };

  // ── Edit (display name + email) ───────────────────────────────────────────
  const openEdit = (u) => {
    setEditUser(u);
    setEditForm({ displayName: u.displayName || '', email: u.emails?.[0]?.value || '' });
    setEditError(null);
  };

  const submitEdit = async () => {
    if (!editUser) return;
    setEditBusy(true); setEditError(null);
    try {
      const r = await axios.patch(`/api/admin/scim/v2/Users/${editUser.id}`, {
        schemas: ['urn:ietf:params:scim:api:messages:2.0:PatchOp'],
        Operations: [
          { op: 'replace', path: 'displayName', value: editForm.displayName.trim() },
          { op: 'replace', path: 'emails', value: [{ value: editForm.email.trim(), primary: true }] },
        ],
      });
      setUsers(u => u.map(x => x.id === editUser.id ? r.data : x));
      setEditUser(null);
      toast.success('User details updated.');
    } catch (e) {
      setEditError(e.response?.data?.error || 'Failed to update the user.');
    }
    setEditBusy(false);
  };

  // ── Activate / deactivate ─────────────────────────────────────────────────
  const toggleActive = async (u) => {
    const next = u.active === false;   // currently inactive → activate
    setSaving(s => ({ ...s, [`active-${u.id}`]: true }));
    try {
      const r = await axios.patch(`/api/admin/scim/v2/Users/${u.id}`, {
        schemas: ['urn:ietf:params:scim:api:messages:2.0:PatchOp'],
        Operations: [{ op: 'replace', path: 'active', value: next }],
      });
      setUsers(us => us.map(x => x.id === u.id ? r.data : x));
      toast.success(next ? 'User activated.' : 'User deactivated — they can no longer sign in.');
    } catch {
      setError(`Failed to change status for ${u.userName}`);
    }
    setSaving(s => ({ ...s, [`active-${u.id}`]: false }));
  };

  // ── Delete ────────────────────────────────────────────────────────────────
  const confirmDelete = async () => {
    if (!deleteUser) return;
    setDeleteBusy(true);
    try {
      await axios.delete(`/api/admin/scim/v2/Users/${deleteUser.id}`);
      setUsers(u => u.filter(x => x.id !== deleteUser.id));
      toast.success(`User "${deleteUser.userName}" deleted.`);
      setDeleteUser(null);
    } catch {
      setError(`Failed to delete ${deleteUser.userName}`);
    }
    setDeleteBusy(false);
  };

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.users.scopeResource', 'users')} />
      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2.5 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>{t('admin.tabs.users')}</Typography>
          <Chip label='SCIM 2.0' size='small'
            sx={{ bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#8DBCFF', fontWeight: 700, fontSize: 10 }} />
        </Box>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
          <Button size='small' variant='contained' startIcon={<AddIcon />} onClick={openAdd}
            sx={{ textTransform: 'none', fontSize: 12, boxShadow: 'none' }}>{t('admin.screens.users.addUser')}</Button>
          <Button size='small' startIcon={<RefreshIcon />} onClick={loadUsers}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.common.refresh')}</Button>
        </Box>
      </Box>

      {error && <Alert severity='warning' sx={{ mb: 2, fontSize: 13 }}>{error}</Alert>}

      {/* ── Roles management (aliases + custom roles) ── */}
      <Box sx={{ mb: 3 }}>
      <CollapsibleSection title={t('admin.screens.users.roles')} count={roleDefs.length}
        icon={<SecurityIcon sx={{ fontSize: 18, color: '#C3D3F2' }} />}
        actions={<>
          <Button size='small' variant='outlined' startIcon={<AddIcon />} onClick={openCreateRole}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.users.newRole')}</Button>
          <Button size='small' startIcon={<RefreshIcon />} onClick={resetRoles} disabled={resetBusy}
            sx={{ textTransform: 'none', fontSize: 12, color: '#C3D3F2' }}>{t('admin.screens.users.resetDefaults')}</Button>
        </>}>
      <Typography fontSize={11} color='#7D92C4' mb={1}>
        <Trans i18nKey='admin.screens.users.rolesHint' components={{ strong: <strong /> }} />
      </Typography>
      <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
        <Table size='small'>
          <TableHead>
            <TableRow sx={{ bgcolor: '#0F2450' }}>
              {['Display Name', 'Key', 'Type', 'Users', 'Actions'].map(h => (
                <TableCell key={h} sx={{ fontWeight: 700, fontSize: 12, color: '#C3D3F2',
                  borderBottom: '2px solid #243A72', py: 1.5 }}>{h}</TableCell>
              ))}
            </TableRow>
          </TableHead>
          <TableBody>
            {rolesLoading ? (
              <TableRow><TableCell colSpan={5} sx={{ textAlign: 'center', py: 3 }}><CircularProgress size={20} /></TableCell></TableRow>
            ) : roleDefs.length === 0 ? (
              <TableRow><TableCell colSpan={5} sx={{ textAlign: 'center', py: 3, color: '#7D92C4', fontSize: 13 }}>
                No roles found for this tenant.
              </TableCell></TableRow>
            ) : roleDefs.map((r, i) => (
              <TableRow key={r.key} sx={{ bgcolor: i % 2 === 0 ? '#152652' : '#0F2450' }}>
                <TableCell>
                  <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                    <Box sx={{ width: 12, height: 12, borderRadius: '50%', bgcolor: r.color || '#8DBCFF', flexShrink: 0 }} />
                    <Typography fontSize={13} fontWeight={600} color='#FFFFFF'>{r.alias}</Typography>
                  </Box>
                </TableCell>
                <TableCell><Typography fontSize={12} fontFamily='monospace' color='#C3D3F2'>{r.key}</Typography></TableCell>
                <TableCell>
                  <Chip size='small' label={r.custom ? t('admin.screens.users.roleCustom') : t('admin.screens.users.roleDefault')}
                    sx={{ fontSize: 10, fontWeight: 700,
                      bgcolor: r.custom ? 'rgba(91, 157, 255, 0.14)' : '#0F2450', color: r.custom ? '#8DBCFF' : '#C3D3F2' }} />
                </TableCell>
                <TableCell><Typography fontSize={12} color='#C3D3F2'>{r.userCount}</Typography></TableCell>
                <TableCell>
                  <Box sx={{ display: 'flex', gap: 0.25 }}>
                    <Tooltip title={t('admin.screens.users.editRoleTip')}>
                      <IconButton size='small' onClick={() => openEditRole(r)} sx={{ color: '#8DBCFF' }}>
                        <EditIcon sx={{ fontSize: 16 }} />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title={t('admin.screens.users.copyRoleTip')}>
                      <IconButton size='small' onClick={() => openCopyRole(r)} sx={{ color: '#8DBCFF' }}>
                        <ContentCopyIcon sx={{ fontSize: 16 }} />
                      </IconButton>
                    </Tooltip>
                    {r.deletable ? (
                      <Tooltip title={t('admin.screens.users.deleteRoleTip')}>
                        <IconButton size='small' onClick={() => deleteRole(r)} sx={{ color: '#FF6B81' }}>
                          <DeleteIcon sx={{ fontSize: 16 }} />
                        </IconButton>
                      </Tooltip>
                    ) : (
                      <Tooltip title={r.isDefault
                        ? t('admin.screens.users.roleBuiltIn')
                        : (r.userCount > 0 ? t('admin.screens.users.roleHasUsers') : t('admin.screens.users.roleUndeletable'))}>
                        <span><IconButton size='small' disabled sx={{ color: '#7D92C4' }}>
                          <LockIcon sx={{ fontSize: 15 }} />
                        </IconButton></span>
                      </Tooltip>
                    )}
                  </Box>
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Paper>
      </CollapsibleSection>
      </Box>

      <CollapsibleSection title={t('admin.screens.users.usersSection')} count={users.length}>
      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>
      ) : (
        <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
          <Table size='small'>
            <TableHead>
              <TableRow sx={{ bgcolor: '#0F2450' }}>
                {['displayName', 'usernameEmail', 'status', 'currentRole', 'changeRole', 'language', 'actions'].map(h => (
                  <TableCell key={h} sx={{ fontWeight: 700, fontSize: 12, color: '#C3D3F2',
                    borderBottom: '2px solid #243A72', py: 1.5 }}>
                    {t(`admin.screens.users.col.${h}`)}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {users.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={7} sx={{ textAlign: 'center', py: 4, color: '#7D92C4', fontSize: 13 }}>
                    {t('admin.screens.users.empty')}
                  </TableCell>
                </TableRow>
              ) : users.map((u, i) => {
                const rc = roleColorChip(u.currentRole);
                return (
                  <TableRow key={u.id} sx={{ bgcolor: i % 2 === 0 ? '#152652' : '#0F2450',
                    '&:hover': { bgcolor: 'rgba(91, 157, 255, 0.14)' } }}>
                    <TableCell sx={{ fontSize: 13, fontWeight: 600, color: '#FFFFFF' }}>
                      {u.displayName || u.name?.formatted || '—'}
                    </TableCell>
                    <TableCell sx={{ fontSize: 12, color: '#C3D3F2' }}>
                      <Box>{u.userName}</Box>
                      {u.emails?.[0]?.value && (
                        <Box sx={{ fontSize: 11, color: '#7D92C4' }}>{u.emails[0].value}</Box>
                      )}
                    </TableCell>
                    <TableCell>
                      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                        {saving[`active-${u.id}`]
                          ? <CircularProgress size={16} />
                          : <Switch size='small' checked={u.active !== false}
                              onChange={() => toggleActive(u)} />}
                        <Chip size='small' label={u.active !== false ? t('admin.screens.users.active') : t('admin.screens.users.inactive')}
                          sx={{ fontSize: 10, fontWeight: 700,
                            bgcolor: u.active !== false ? 'rgba(61, 220, 151, 0.14)' : 'rgba(255, 0, 0, 0.14)',
                            color:   u.active !== false ? '#3DDC97'  : '#FF6B81' }} />
                      </Box>
                    </TableCell>
                    <TableCell>
                      <Chip size='small' label={roleAlias(u.currentRole || 'guest')}
                        sx={{ fontSize: 10, fontWeight: 700, ...rc }} />
                    </TableCell>
                    <TableCell>
                      {saving[u.id] ? <CircularProgress size={16} /> : (
                        <Select size='small' value={u.currentRole || 'guest'}
                          onChange={e => changeRole(u.id, e.target.value)}
                          sx={{ fontSize: 12, height: 28, minWidth: 130 }}>
                          {assignableRoles.map(r => (
                            <MenuItem key={r} value={r} sx={{ fontSize: 12 }}>{roleAlias(r)}</MenuItem>
                          ))}
                        </Select>
                      )}
                    </TableCell>
                    <TableCell>
                      {saving[`lang-${u.id}`] ? <CircularProgress size={16} /> : (
                        <Select size='small' value={u.preferredLanguage || ''}
                          displayEmpty
                          onChange={e => changeLanguage(u.id, e.target.value)}
                          sx={{ fontSize: 12, height: 28, minWidth: 100 }}>
                          <MenuItem value='' sx={{ fontSize: 12, color: '#7D92C4' }}>
                            {t('admin.screens.users.tenantDefaultLang')}
                          </MenuItem>
                          {SUPPORTED_LANGUAGES.map(lang => (
                            <MenuItem key={lang.code} value={lang.code} sx={{ fontSize: 12 }}>
                              {lang.nativeLabel}
                            </MenuItem>
                          ))}
                        </Select>
                      )}
                    </TableCell>
                    <TableCell>
                      <Box sx={{ display: 'flex', gap: 0.25 }}>
                        <Tooltip title={t('admin.screens.users.editUserTip')}>
                          <IconButton size='small' onClick={() => openEdit(u)}
                            sx={{ color: '#8DBCFF' }}>
                            <EditIcon sx={{ fontSize: 17 }} />
                          </IconButton>
                        </Tooltip>
                        <Tooltip title={t('admin.screens.users.deleteUserTip')}>
                          <IconButton size='small' onClick={() => setDeleteUser(u)}
                            sx={{ color: '#FF6B81' }}>
                            <DeleteIcon sx={{ fontSize: 17 }} />
                          </IconButton>
                        </Tooltip>
                      </Box>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Paper>
      )}
      </CollapsibleSection>

      <Typography fontSize={11} color='#7D92C4' mt={1.5}>
        {t('admin.screens.users.scimNote')}
        <code style={{ fontSize: 11, marginLeft: 4 }}>/api/admin/scim/v2/Users</code>
      </Typography>

      {/* ── Add-user dialog ── */}
      <Dialog open={addOpen} onClose={() => !addBusy && setAddOpen(false)} fullWidth maxWidth='xs'>
        <DialogTitle sx={{ fontSize: 16, fontWeight: 700 }}>{t('admin.screens.users.addUser')}</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}>
          {addError && <Alert severity='error' sx={{ fontSize: 12 }}>{addError}</Alert>}
          <TextField label={t('admin.screens.users.fDisplayName')} size='small' fullWidth value={addForm.displayName}
            onChange={e => setAddForm(f => ({ ...f, displayName: e.target.value }))} />
          <TextField label={t('admin.screens.users.fEmail')} type='email' size='small' fullWidth required value={addForm.email}
            onChange={e => setAddForm(f => ({ ...f, email: e.target.value }))}
            helperText={t('admin.screens.users.fEmailHint')} />
          <TextField label={t('admin.screens.users.fRole')} size='small' fullWidth select value={addForm.role}
            onChange={e => setAddForm(f => ({ ...f, role: e.target.value }))}>
            {assignableRoles.map(r => <MenuItem key={r} value={r} sx={{ fontSize: 13 }}>{roleAlias(r)}</MenuItem>)}
          </TextField>
          <TextField label={t('admin.screens.users.fInitialPassword')} type='password' size='small' fullWidth required
            value={addForm.password} autoComplete='new-password'
            onChange={e => setAddForm(f => ({ ...f, password: e.target.value }))} />
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Switch size='small' checked={addForm.requireReset}
              onChange={e => setAddForm(f => ({ ...f, requireReset: e.target.checked }))} />
            <Typography fontSize={12} color='#C3D3F2'>{t('admin.screens.users.requireReset')}</Typography>
          </Box>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setAddOpen(false)} size='small' disabled={addBusy}>{t('admin.common.cancel')}</Button>
          <Button onClick={submitAdd} size='small' variant='contained' disabled={addBusy}
            startIcon={addBusy ? <CircularProgress size={14} /> : null}>{t('admin.common.create')}</Button>
        </DialogActions>
      </Dialog>

      {/* ── Edit-user dialog ── */}
      <Dialog open={!!editUser} onClose={() => !editBusy && setEditUser(null)} fullWidth maxWidth='xs'>
        <DialogTitle sx={{ fontSize: 16, fontWeight: 700 }}>{t('admin.screens.users.editUser')}</DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}>
          {editError && <Alert severity='error' sx={{ fontSize: 12 }}>{editError}</Alert>}
          <Typography fontSize={12} color='#7D92C4'>{editUser?.userName}</Typography>
          <TextField label={t('admin.screens.users.fDisplayName')} size='small' fullWidth value={editForm.displayName}
            onChange={e => setEditForm(f => ({ ...f, displayName: e.target.value }))} />
          <TextField label={t('admin.screens.users.fEmail')} type='email' size='small' fullWidth value={editForm.email}
            onChange={e => setEditForm(f => ({ ...f, email: e.target.value }))} />
          <Typography fontSize={11} color='#7D92C4'>
            {t('admin.screens.users.editRowHint')}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setEditUser(null)} size='small' disabled={editBusy}>{t('admin.common.cancel')}</Button>
          <Button onClick={submitEdit} size='small' variant='contained' disabled={editBusy}
            startIcon={editBusy ? <CircularProgress size={14} /> : null}>{t('admin.common.save')}</Button>
        </DialogActions>
      </Dialog>

      {/* ── Delete confirmation ── */}
      <Dialog open={!!deleteUser} onClose={() => !deleteBusy && setDeleteUser(null)} fullWidth maxWidth='xs'>
        <DialogTitle sx={{ fontSize: 16, fontWeight: 700 }}>{t('admin.screens.users.deleteUser')}</DialogTitle>
        <DialogContent>
          <Typography fontSize={13} color='#C3D3F2'>
            <Trans i18nKey='admin.screens.users.deleteConfirm'
              values={{ name: deleteUser?.displayName || deleteUser?.userName }}
              components={{ strong: <strong /> }} />
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setDeleteUser(null)} size='small' disabled={deleteBusy}>{t('admin.common.cancel')}</Button>
          <Button onClick={confirmDelete} size='small' variant='contained' color='error' disabled={deleteBusy}
            startIcon={deleteBusy ? <CircularProgress size={14} /> : null}>{t('admin.common.delete')}</Button>
        </DialogActions>
      </Dialog>

      {/* ── Role create / edit / copy dialog ── */}
      <Dialog open={!!roleDialog} onClose={() => !roleBusy && setRoleDialog(null)} fullWidth maxWidth='xs'>
        <DialogTitle sx={{ fontSize: 16, fontWeight: 700 }}>
          {roleDialog?.mode === 'create' ? t('admin.screens.users.newRole')
            : roleDialog?.mode === 'copy' ? t('admin.screens.users.copyRole')
            : t('admin.screens.users.editRole')}
        </DialogTitle>
        <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}>
          {roleErr && <Alert severity='error' sx={{ fontSize: 12 }}>{roleErr}</Alert>}
          {roleDialog?.mode === 'copy' && (
            <Typography fontSize={12} color='#7D92C4'>
              <Trans i18nKey='admin.screens.users.copyingFrom'
                values={{ role: roleAlias(roleDialog.sourceKey) }}
                components={{ strong: <strong /> }} />
            </Typography>
          )}
          <TextField label={t('admin.screens.users.fAlias')} size='small' fullWidth value={roleDialog?.draft.alias || ''}
            onChange={e => setRoleDialog(s => ({ ...s, draft: { ...s.draft, alias: e.target.value } }))}
            helperText={t('admin.screens.users.fAliasHint')} />
          {roleDialog?.mode !== 'edit' && (
            <TextField label={t('admin.screens.users.fRoleKey')} size='small' fullWidth value={roleDialog?.draft.key || ''}
              onChange={e => setRoleDialog(s => ({ ...s, draft: { ...s.draft, key: e.target.value.toLowerCase().replace(/[^a-z0-9_]/g, '') } }))}
              helperText={t('admin.screens.users.fRoleKeyHint')} />
          )}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
            <Typography fontSize={13} color='#C3D3F2'>{t('admin.screens.users.fColour')}</Typography>
            <Box component='input' type='color' value={roleDialog?.draft.color || '#8DBCFF'}
              onChange={e => setRoleDialog(s => ({ ...s, draft: { ...s.draft, color: e.target.value } }))}
              sx={{ width: 42, height: 28, p: 0, border: '1px solid #243A72', borderRadius: 1,
                cursor: 'pointer', bgcolor: 'transparent' }} />
          </Box>
          {roleDialog?.mode === 'create' && (
            <TextField label={t('admin.screens.users.fBaseRole')} size='small' fullWidth select value={roleDialog?.draft.basedOn || ''}
              onChange={e => setRoleDialog(s => ({ ...s, draft: { ...s.draft, basedOn: e.target.value } }))}
              helperText={t('admin.screens.users.fBaseRoleHint')}>
              <MenuItem value=''><em>{t('admin.screens.users.fBaseRoleNone')}</em></MenuItem>
              {roleDefs.map(r => <MenuItem key={r.key} value={r.key} sx={{ fontSize: 13 }}>{r.alias} ({r.key})</MenuItem>)}
            </TextField>
          )}
          {roleDialog?.mode !== 'edit' && (
            <Typography fontSize={11} color='#7D92C4'>
              <Trans i18nKey='admin.screens.users.afterCreateHint' components={{ strong: <strong /> }} />
            </Typography>
          )}
        </DialogContent>
        <DialogActions>
          <Button onClick={() => setRoleDialog(null)} size='small' disabled={roleBusy}>{t('admin.common.cancel')}</Button>
          <Button onClick={saveRole} size='small' variant='contained' disabled={roleBusy}
            startIcon={roleBusy ? <CircularProgress size={14} /> : null}>
            {roleDialog?.mode === 'edit' ? t('admin.common.save') : t('admin.common.create')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

// ── Main AdminPage ────────────────────────────────────────────────────────────
// The Admin console owns its own language choice, persisted under
// 'orphos.admin.lang' — independent of the dashboard/plugins ('orphos.lang').
// The I18nextProvider in the default export points every useTranslation()
// inside the admin tree (tabs, LanguageSwitcher) at this scoped instance.
const adminI18n = createScopedI18n('orphos.admin.lang');

function AdminPageContent({ onBack }) {
  const { t } = useTranslation();
  const { isPlatformAdmin, isAdmin, isIngestionAdmin, isDataAiAdmin, isSecopsAdmin, roles } = useRole();
  const keycloak = useAuth();
  const branding = useBranding();
  // The tenant's chosen time zone (Appearance → Branding). Threaded into the
  // plugin admin sections' context so their ScheduleEditor / schedule summaries
  // default to it — a remote reads its OWN eip-shell-context copy where the
  // shell's BrandingProvider setTenantTimezone() never landed (federation
  // singleton disconnect). '' → the remote falls back to the browser zone.
  const tenantTimezone = branding?.profile?.timezone || '';
  // Tenant theme params (font family + size + colours) so a plugin admin
  // section applies the SAME MUI theme as the shell (RemoteThemeProvider) — the
  // shell's ThemeProvider does not cross the federation boundary. The App.jsx
  // pluginContext carries the same for the plugins' tab/side-panel renders.
  const pluginContext = useMemo(() => ({
    tenantTimezone,
    themeParams: {
      mode: branding?.theme?.mode,
      colors: branding?.theme?.colors,
      fontFamily: branding?.profile?.fontFamily,
      fontSize: branding?.profile?.fontSize,
    },
  }), [
    tenantTimezone,
    branding?.theme?.mode, branding?.theme?.colors,
    branding?.profile?.fontFamily, branding?.profile?.fontSize,
  ]);

  // A scoped ingestion admin (the ingestion_admin role WITHOUT full admin) only
  // ever sees the Document Ingestion group — never SSO, users, AI, branding, etc.
  const ingestionOnly = isIngestionAdmin && !isAdmin && !isPlatformAdmin;

  // A scoped Data & AI admin (the data_ai_admin role WITHOUT full admin) only
  // ever sees the Data and AI groups — never SSO, users, documents, branding, etc.
  const dataAiOnly = isDataAiAdmin && !isAdmin && !isPlatformAdmin && !isIngestionAdmin;

  // A scoped Security & Auth admin (the secops_admin role WITHOUT full admin)
  // only ever sees the Authentication & Authorization group — never AI,
  // documents, branding, etc.
  const secopsOnly = isSecopsAdmin && !isAdmin && !isPlatformAdmin
    && !isIngestionAdmin && !isDataAiAdmin;

  // Which tenant is in scope. A platform admin defaults to "platform" (no
  // override); picking a tenant in the header swaps this to that tenant's UUID.
  // Regular tenant admins are implicitly scoped to their own tenant (JWT claim).
  const [actingTenantId, setActingTenantId] = useState(
    () => getActingTenant()?.tenantId ?? 'platform'
  );
  useEffect(() => onActingTenantChange(
    t => setActingTenantId(t?.tenantId ?? 'platform')
  ), []);

  // Per-tenant RBAC access map (role → resource grants) that governs tab
  // visibility. Empty until loaded (and empty === today's role defaults, so the
  // tab list is correct during load). Re-fetched when the acting tenant changes.
  const [accessGrantMap, setAccessGrantMap] = useState({});
  useEffect(() => {
    let cancelled = false;
    axios.get('/api/admin/access-control')
      .then(r => { if (!cancelled) setAccessGrantMap(toGrantMap(r.data?.grants)); })
      .catch(() => { if (!cancelled) setAccessGrantMap({}); });
    return () => { cancelled = true; };
  }, [actingTenantId]);

  // The tenant picker toggles between two mutually-exclusive tab surfaces:
  //
  //   • Platform scope  — platform admin with NO tenant picked. Shows ONLY the
  //     cross-tenant tabs (Tenants, Services, Service Logs).
  //   • Tenant scope    — a tenant is picked (or the caller is a regular tenant
  //     admin). Shows ONLY that tenant's tabs (SSO, users, AI, branding,
  //     documents, …), which read/write via the X-Tenant-Id header. The
  //     platform-level tabs are hidden here.
  //
  // So picking a tenant swaps the platform tabs OUT for the tenant tabs rather
  // than stacking both, and the tenant tabs never render without a tenant in
  // scope (where they would have no X-Tenant-Id and error).
  const tenantScopeAvailable = !isPlatformAdmin || actingTenantId !== 'platform';
  const platformScope        = isPlatformAdmin && actingTenantId === 'platform';

  // Tab visibility — a tenant admin only ever sees their own tenant's
  // settings, so the cross-tenant "Tenants" management surface is hidden
  // for them entirely. Backend already refuses /api/admin/tenants for
  // non-platform-admins, but hiding the tab keeps the UI honest.
  // Admin sections are organised into groups; each group renders a secondary
  // tab strip. `group` on a tabDef ties it to a GROUP entry below.
  const GROUPS = useMemo(
    () => ADMIN_GROUP_ORDER.map(([key, label]) => ({ key, label })), []);

  // Memoised so the clamp effect below doesn't re-run every commit. Built from
  // the shared screen registry (single source of truth) so the tab list, the
  // plugin map above and the Access Control catalog can never drift apart.
  const tabDefs = useMemo(() => ADMIN_SCREENS
    .map(s => ({
      key: s.key,
      group: s.group,
      label: s.label,
      icon: TAB_ICON[s.key],
      platformOnly: !!s.platformOnly,
      platformAdminOnly: !!s.platformAdminOnly,
    }))
    .filter(t => t.platformOnly ? platformScope : tenantScopeAvailable)
    // Tabs a platform admin manages on behalf of a tenant (still tenant-scoped,
    // so they need a tenant picked, but tenant admins must not see them).
   .filter(t => !t.platformAdminOnly || isPlatformAdmin)
    // Access map governs which tabs each role sees. anyRoleAllows falls back to
    // the role defaults (admin→all, each scoped admin→its group) when no explicit
    // grant exists, so an empty map reproduces the previous confinement behaviour.
    // Explicit Allow grants can widen a role's access; Deny grants narrow it.
   .filter(t => isPlatformAdmin || anyRoleAllows(accessGrantMap, roles, t.key, t.group)),
    [platformScope, tenantScopeAvailable, isPlatformAdmin, accessGrantMap, roles]);

  // Only show groups that have at least one visible tab (platform-only tabs are
  // filtered out for tenant admins, which can empty a group).
  const visibleGroups = useMemo(
    () => GROUPS.filter(g => tabDefs.some(t => t.group === g.key)),
    [GROUPS, tabDefs]);

  // Track active tab by key (not index) so the right tab stays selected even
  // if the platform-admin role flips at runtime and the tab list reshapes.
  const [tabKey, setTabKey] = useState(() => tabDefs[0]?.key ?? 'auth');
  useEffect(() => {
    if (!tabDefs.find(t => t.key === tabKey)) {
      setTabKey(tabDefs[0]?.key ?? 'auth');
    }
  }, [tabDefs, tabKey]);

  // The active group is derived from the active tab so the two strips stay in
  // sync without a second piece of state. Switching group selects its first tab.
  const activeGroupKey = tabDefs.find(t => t.key === tabKey)?.group
    ?? visibleGroups[0]?.key;
  const groupTabs = tabDefs.filter(t => t.group === activeGroupKey);
  const onGroupChange = (gKey) => {
    const first = tabDefs.find(t => t.group === gKey);
    if (first) setTabKey(first.key);
  };

  // The header states group over tab, so both defs are needed by name.
  const activeGroup = visibleGroups.find(g => g.key === activeGroupKey);
  const activeTab   = tabDefs.find(t => t.key === tabKey);

  // Sidebar footer identity. The artboard labels the account by email; the
  // username is the fallback when the IdP released no email claim.
  const userLabel = keycloak?.tokenParsed?.email
    || keycloak?.tokenParsed?.preferred_username
    || t('admin.account.signedIn', 'Signed in');
  const userFullName = keycloak?.tokenParsed?.name?.trim() || '';
  // Initials off the display name when there is one ('Ada King' -> 'AK'),
  // else the first two characters of the address's local part.
  const userInitials = useMemo(() => {
    if (userFullName) {
      const parts = userFullName.split(/\s+/).filter(Boolean);
      return (parts[0][0] + (parts[1]?.[0] ?? '')).toUpperCase();
    }
    return String(userLabel).split('@')[0].slice(0, 2).toUpperCase();
  }, [userFullName, userLabel]);
  // The footer line is clipped to the rail's width, so the address is often
  // cut and the display name is never rendered. The tooltip carries both.
  const identityTooltip = userFullName && userFullName !== userLabel
    ? `${userFullName} — ${userLabel}`
    : userLabel;

  // Tab content is keyed by `actingTenantId` (declared at the top of this
  // component) — admin-service filters its responses by the X-Tenant-Id header
  // the axios interceptor attaches, so switching the acting tenant must refetch.
  // Re-keying remounts each tenant-scoped tab and refires its child useEffects.
  // The Tenants tab stays unkeyed because it is cross-tenant.

  // AdminGuard re-checks isAdmin on every render. If the role goes away
  // (token refresh drops it, admin manually revoked), the guard swaps in
  // an "access denied" panel - no need to coordinate with App-level state.
  return (
    <AdminGuard onBack={onBack}>
    <Box sx={{ display: 'flex', minHeight: '100vh', bgcolor: ORPHOS_TOKENS.bg.canvas }}>

      {/* ── Sidebar: brand block, group nav, signed-in footer ─────────────
          The group strip that used to sit above the sub-tabs moved here. Two
          scrollable horizontal strips stacked on each other made the active
          group easy to lose track of once either overflowed; a rail keeps every
          group visible at once and gives the sub-tabs the full width back. */}
      <Box component='nav' sx={{
        width: SIDEBAR_W, flexShrink: 0, display: 'flex', flexDirection: 'column',
        position: 'sticky', top: 0, height: '100vh',
        bgcolor: ORPHOS_TOKENS.bg.header,
        borderRight: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
      }}>
        <Box sx={{
          px: `${RAIL_INSET}px`, display: 'flex', flexDirection: 'column', justifyContent: 'center',
          minHeight: HEADER_H, flexShrink: 0,
          borderBottom: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
        }}>
          <Typography fontWeight={800} fontSize={18} color={ORPHOS_TOKENS.text.primary}>
            {t('admin.title')}
          </Typography>
          <Typography fontSize={12.5} lineHeight={1.45} mt={0.5}
            color={ORPHOS_TOKENS.text.muted}>
            {t('admin.subtitle')}
          </Typography>
        </Box>

        <Box sx={{
          flex: 1, minHeight: 0, overflowY: 'auto',
          px: `${RAIL_GUTTER}px`, py: 1.5,
          display: 'flex', flexDirection: 'column', gap: 0.5,
        }}>
          {visibleGroups.map(g => {
            const active = g.key === activeGroupKey;
            return (
              <ButtonBase key={g.key} onClick={() => onGroupChange(g.key)}
                aria-current={active ? 'page' : undefined}
                sx={{
                  width: '100%', justifyContent: 'flex-start', gap: 1.25,
                  px: `${RAIL_INSET - RAIL_GUTTER}px`, py: 1.25,
                  borderRadius: `${ORPHOS_SHAPE.radiusPill}px`, textAlign: 'left',
                  color: active ? ORPHOS_TOKENS.text.onFilled : ORPHOS_TOKENS.text.secondary,
                  bgcolor: active ? ORPHOS_COLORS.primary : 'transparent',
                  transition: 'background-color 120ms ease, color 120ms ease',
                  '&:hover': {
                    bgcolor: active ? ORPHOS_COLORS.primary : ORPHOS_TOKENS.state.hover,
                    color: ORPHOS_TOKENS.text.primary,
                  },
                }}>
                <Box sx={{ display: 'flex', flexShrink: 0 }}>
                  {GROUP_ICON[g.key] ?? <TuneIcon sx={{ fontSize: 18 }} />}
                </Box>
                <Typography component='span' sx={{
                  flex: 1, fontSize: 13.5, fontWeight: active ? 700 : 600, lineHeight: 1.3,
                }}>
                  {t(`admin.groups.${g.key}`, g.label)}
                </Typography>
                {/* Decorative only — it restates the fill beside it, which is
                    what already carries the selected state. */}
                {active && <Box sx={{
                  width: 6, height: 6, borderRadius: '50%', flexShrink: 0,
                  bgcolor: 'rgba(255, 255, 255, 0.75)',
                }} />}
              </ButtonBase>
            );
          })}
        </Box>

        {/* Signed-in identity + sign out. Sign out sat in the page header
            before; it belongs next to the account it acts on. */}
        <Box sx={{
          display: 'flex', alignItems: 'center', gap: 1.25, flexShrink: 0,
          px: `${RAIL_INSET}px`, py: 1.75,
          borderTop: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
        }}>
          {/* One tooltip over avatar + text rather than one per line: the
              whole block identifies a single account, and the address is the
              part that gets clipped. */}
          <Tooltip title={identityTooltip}>
            <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.25, flex: 1, minWidth: 0 }}>
              <Avatar sx={{
                width: 32, height: 32, fontSize: 12, fontWeight: 700, flexShrink: 0,
                bgcolor: ORPHOS_COLORS.primary, color: ORPHOS_TOKENS.text.onFilled,
              }}>
                {userInitials}
              </Avatar>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography fontSize={13} fontWeight={600} color={ORPHOS_TOKENS.text.primary}
                  sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {userLabel}
                </Typography>
                <Typography fontSize={11.5} color={ORPHOS_TOKENS.text.muted}
                  sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {isPlatformAdmin
                    ? t('admin.role.platform', 'Platform administrator')
                    : t('admin.role.tenant', 'Tenant administrator')}
                </Typography>
              </Box>
            </Box>
          </Tooltip>
          <Tooltip title={t('header.signOut')}>
            <IconButton size='small'
              onClick={() => keycloak?.logout({ redirectUri: tenantRedirectUri() })}
              sx={{
                color: ORPHOS_TOKENS.text.secondary,
                border: `1px solid ${ORPHOS_TOKENS.line.strong}`,
                borderRadius: `${ORPHOS_SHAPE.radiusChip}px`,
                '&:hover': { bgcolor: ORPHOS_TOKENS.state.hover, color: ORPHOS_TOKENS.text.primary },
              }}>
              <LogoutIcon fontSize='small' />
            </IconButton>
          </Tooltip>
        </Box>
      </Box>

      {/* ── Main column ──────────────────────────────────────────────────── */}
      <Box sx={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>

        <Box sx={{
          display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 2,
          px: `${MAIN_INSET}px`, minHeight: HEADER_H, flexShrink: 0,
          borderBottom: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
        }}>
          <Box sx={{ minWidth: 0 }}>
            {/* Group over tab: the rail says which group is open, this restates
                it so the title below is unambiguous when a label repeats. */}
            <Typography fontSize={11} fontWeight={700} letterSpacing='1.2px'
              color={ORPHOS_TOKENS.text.muted} sx={{ textTransform: 'uppercase' }}>
              {t(`admin.groups.${activeGroupKey}`, activeGroup?.label ?? '')}
            </Typography>
            <Typography fontSize={22} fontWeight={700} lineHeight={1.25}
              color={ORPHOS_TOKENS.text.primary}>
              {activeTab ? t(`admin.tabs.${activeTab.key}`, activeTab.label) : t('admin.title')}
            </Typography>
          </Box>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1.5, flexShrink: 0 }}>
            <LanguageSwitcher />
            {isPlatformAdmin && <PlatformTenantPicker />}
            {onBack && !isPlatformAdmin && (
              <Button size='small' variant='outlined' onClick={onBack}
                sx={{
                  textTransform: 'none', fontSize: 13, fontWeight: 600,
                  borderRadius: `${ORPHOS_SHAPE.radiusButton}px`,
                  borderColor: ORPHOS_TOKENS.line.strong,
                  color: ORPHOS_TOKENS.text.secondary,
                  '&:hover': {
                    borderColor: ORPHOS_COLORS.primary,
                    bgcolor: ORPHOS_TOKENS.state.hover,
                    color: ORPHOS_TOKENS.text.primary,
                  },
                }}>
                {t('admin.backToDashboard', '← Back to Dashboard')}
              </Button>
            )}
          </Box>
        </Box>

        <Tabs value={tabKey} onChange={(_, v) => setTabKey(v)}
          variant='scrollable' scrollButtons='auto'
          sx={{
            px: `${MAIN_INSET}px`, minHeight: 48, flexShrink: 0,
            borderBottom: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
            '& .MuiTabs-indicator': { backgroundColor: ORPHOS_COLORS.primary, height: 2 },
            '& .MuiTab-root': {
              minHeight: 48, borderRadius: 0, textTransform: 'none',
              fontWeight: 600, fontSize: 13, color: ORPHOS_TOKENS.text.muted,
              '&:hover': { color: ORPHOS_TOKENS.text.secondary, backgroundColor: 'transparent' },
              '&.Mui-selected': {
                color: ORPHOS_TOKENS.text.primary, fontWeight: 700,
                backgroundColor: 'transparent',
              },
            },
            '& .MuiTab-root:first-of-type': { pl: 0 },
          }}>
          {groupTabs.map(tab => (
            <Tab key={tab.key} value={tab.key}
              icon={tab.icon} iconPosition='start'
              label={t(`admin.tabs.${tab.key}`, tab.label)} />
          ))}
        </Tabs>

        <Box sx={{ flex: 1, minWidth: 0, px: `${MAIN_INSET}px`, py: 3 }}>
          {tabKey === 'tenants'  && <TenantManagementTab />}
          {tabKey === 'services' && <ServicesTab />}
          {tabKey === 'db-connections' && <DbConnectionsTab />}
          {tabKey === 'bases' && <PlatformBasesTab />}
          {tabKey === 'users'    && <UserManagementTab key={`users-${actingTenantId}`}  />}
          {tabKey === 'auth'     && <AuthMethodsTab    key={`auth-${actingTenantId}`}   />}
          {tabKey === 'access-control' && <AccessControlTab key={`ac-${actingTenantId}`} />}
          {tabKey === 'event-alerts' && <EventAlertsTab key={`event-alerts-${actingTenantId}`} />}
          {tabKey === 'branding'  && <BrandingTab          key={`brand-${actingTenantId}`}     />}
          {tabKey === 'platform-branding' && <BrandingTab platformTemplate key='platform-branding' />}
          {tabKey === 'onboarding' && <OnboardingTab   key={`onboarding-${actingTenantId}`} />}
          {tabKey === 'data-sources'   && <DataSourcesTab   key={`data-sources-${actingTenantId}`}   />}
          {tabKey === 'data-mapping'   && <DataMappingTab   key={`data-mapping-${actingTenantId}`}   />}
          {tabKey === 'ingestion-jobs' && <IngestionJobsTab key={`ingestion-jobs-${actingTenantId}`} />}
          {tabKey === 'ontology'     && <DataFlow            key={`ontology-${actingTenantId}`}    />}
          {tabKey === 'logs'        && <ServiceLogsTab />}
          {tabKey === 'plugins'     && (
            <PluginsTab
              key={`plugins-${actingTenantId}`}
              tenantId={actingTenantId !== 'platform' ? actingTenantId : null}
            />
          )}
          {tabKey === 'agents'      && (
            <AgentsTab
              key={`agents-${actingTenantId}`}
              tenantId={actingTenantId !== 'platform' ? actingTenantId : null}
            />
          )}
          {tabKey === 'agent-approvals' && (
            <AgentApprovalsTab
              key={`agent-approvals-${actingTenantId}`}
              tenantId={actingTenantId !== 'platform' ? actingTenantId : null}
            />
          )}
          {tabKey === 'mobile-access' && (
            <MobileAccessTab
              key={`mobile-access-${actingTenantId}`}
              tenantId={actingTenantId !== 'platform' ? actingTenantId : null}
            />
          )}
          {tabKey === 'capabilities' && <CapabilityCatalogTab />}
          {tabKey === 'voice'       && <VoiceTab />}
          {PLUGIN_ADMIN_MAP[tabKey] && (
            <PluginLoader
              key={`${tabKey}-${actingTenantId}`}
              pluginId={PLUGIN_ADMIN_MAP[tabKey][0]}
              adminSection={PLUGIN_ADMIN_MAP[tabKey][1]}
              context={pluginContext}
            />
          )}
        </Box>
      </Box>
    </Box>
    </AdminGuard>
  );
}

export default function AdminPage(props) {
  return (
    <I18nextProvider i18n={adminI18n}>
      <AdminPageContent {...props} />
    </I18nextProvider>
  );
}
