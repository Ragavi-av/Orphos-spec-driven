import { useCallback, useEffect, useState } from 'react';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress,
  TextField, Autocomplete, Divider, MenuItem, Select, FormControl,
  InputLabel, InputAdornment, IconButton, Tooltip, Chip,
  Dialog, DialogTitle, DialogContent, DialogActions,
  List, ListItemButton, ListItemText, Breadcrumbs, Link,
} from '@mui/material';
import RefreshIcon    from '@mui/icons-material/Refresh';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import FolderIcon     from '@mui/icons-material/Folder';
import FolderOpenIcon from '@mui/icons-material/FolderOpen';
import CreateNewFolderIcon from '@mui/icons-material/CreateNewFolder';
import ArrowUpwardIcon from '@mui/icons-material/ArrowUpward';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import HomeIcon from '@mui/icons-material/Home';
import { useTranslation } from 'react-i18next';
import axios from 'eip-shell-context/axiosAuth';
import { useToast } from 'eip-shell-context/ToastProvider';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';

// "Entered or chosen" — free-text fields with common suggestions.
const CATEGORY_SUGGESTIONS   = ['Policy', 'Report', 'Contract', 'Invoice', 'Manual', 'Form', 'Memo', 'Circular'];
const DEPARTMENT_SUGGESTIONS = ['Finance', 'HR', 'Legal', 'Operations', 'IT', 'Sales', 'Marketing', 'Procurement'];

const ORGANIZE_OPTIONS = [
  { value: 'NONE',                labelKey: 'organizeNone',         labelEn: 'Flat — folder only' },
  { value: 'DEPARTMENT',          labelKey: 'organizeDepartment',   labelEn: 'By department' },
  { value: 'FILETYPE',            labelKey: 'organizeFiletype',     labelEn: 'By file type' },
  { value: 'DEPARTMENT_FILETYPE', labelKey: 'organizeDeptFiletype', labelEn: 'By department, then file type' },
];

/**
 * Tenant admin / ingestion-admin UI for default document-ingestion metadata.
 *
 * <p>These defaults are applied when an uploader leaves a field blank, and the
 * {@code organizeBy} rule drives the physical storage layout under the
 * configured storage root: {folder}/{department}/{fileType}/{documentId}.</p>
 */
export default function IngestionDefaultsTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const [full, setFull]       = useState(null);   // full DocSettings (so we don't drop search settings on save)
  const [ing, setIng]         = useState(null);   // editable ingestion section
  const [loading, setLoading] = useState(true);
  const [saving, setSaving]   = useState(false);
  const [error, setError]     = useState(null);
  const [info, setInfo]       = useState(null);
  // Folder connections usable as a storage location (BROWSE-capable) — the shared pool.
  const [connections, setConnections] = useState([]);

  // Explorer-style folder browser — navigate the tenant's storage hierarchy
  // (local disk today; the same path-prefix contract covers S3 when it lands).
  const [folderPickerOpen, setFolderPickerOpen] = useState(false);
  const [browsePath, setBrowsePath]     = useState('');      // '' = storage root
  const [browseParent, setBrowseParent] = useState(null);    // null at root
  const [browseEntries, setBrowseEntries] = useState([]);    // [{name, path, hasChildren}]
  const [browseBackend, setBrowseBackend] = useState('');
  const [folderLoading, setFolderLoading] = useState(false);
  const [folderError, setFolderError]     = useState(null);
  const [folderDraft, setFolderDraft]     = useState('');    // the "Folder:" field value
  const [newFolderOpen, setNewFolderOpen] = useState(false);
  const [newFolderName, setNewFolderName] = useState('');

  const loadBrowse = async (path) => {
    const connId = ing?.storageConnectionId || '';
    setFolderLoading(true); setFolderError(null);
    try {
      if (connId) {
        // Browse within the chosen folder connection (S3/FTP/…).
        const r = await axios.get(`/api/doc-ingest/admin/sources/${connId}/browse`, { params: { path } });
        const p = r.data?.path ?? path ?? '';
        setBrowsePath(p);
        setBrowseParent(!p ? null : (p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : ''));
        setBrowseEntries(Array.isArray(r.data?.entries) ? r.data.entries : []);
        const conn = connections.find(c => c.id === connId);
        setBrowseBackend(conn ? (conn.displayName || conn.kind) : 'Connection');
        setFolderLoading(false);
        return;
      }
      // Bundled local storage.
      const r = await axios.get('/api/doc-ingest/admin/folders/browse', { params: { path } });
      setBrowsePath(r.data?.path ?? path);
      setBrowseParent(r.data?.parent ?? null);
      setBrowseEntries(Array.isArray(r.data?.entries) ? r.data.entries : []);
      setBrowseBackend(r.data?.backend || 'LOCAL');
    } catch (e) {
      if (!connId && e?.response?.status === 404) {
        // Older backend without the browse endpoint — flat top-level listing.
        try {
          const flat = await axios.get('/api/doc-ingest/admin/folders');
          setBrowsePath(''); setBrowseParent(null); setBrowseBackend('LOCAL');
          setBrowseEntries((Array.isArray(flat.data) ? flat.data : [])
            .map(name => ({ name, path: name, hasChildren: false })));
        } catch (e2) {
          setFolderError(e2?.response?.data?.message || t('admin.screens.ingestionDefaults.foldersLoadError', 'Could not load existing folders.'));
        }
      } else {
        setFolderError(e?.response?.data?.error || e?.response?.data?.message
          || t('admin.screens.ingestionDefaults.foldersLoadError', 'Could not load existing folders.'));
      }
    }
    setFolderLoading(false);
  };

  const openFolderPicker = () => {
    setFolderPickerOpen(true);
    setNewFolderOpen(false); setNewFolderName('');
    const current = (ing?.defaultFolder || '').trim();
    setFolderDraft(current);
    // Open at the current value's parent so the selection is visible in place.
    const startAt = current.includes('/') ? current.slice(0, current.lastIndexOf('/')) : '';
    loadBrowse(startAt);
  };

  const navigateTo = (path) => {
    // Entering a folder also makes it the selection — same as Windows Explorer.
    setFolderDraft(path);
    setNewFolderOpen(false);
    loadBrowse(path);
  };

  const createFolder = async () => {
    const name = newFolderName.trim();
    if (!name) return;
    const fullPath = browsePath ? `${browsePath}/${name}` : name;
    const connId = ing?.storageConnectionId || '';
    setFolderError(null);
    try {
      if (connId) await axios.post(`/api/doc-ingest/admin/sources/${connId}/folders`, { path: fullPath });
      else        await axios.post('/api/doc-ingest/admin/folders', null, { params: { path: fullPath } });
      setNewFolderOpen(false); setNewFolderName('');
      setFolderDraft(fullPath);
      await loadBrowse(browsePath);
      toast.success(t('admin.screens.ingestionDefaults.folderCreated', 'Folder "{{name}}" created.', { name }));
    } catch (e) {
      setFolderError(e?.response?.data?.error || e?.response?.data?.message
        || t('admin.screens.ingestionDefaults.folderCreateError', 'Folder creation failed.'));
    }
  };

  // Confirm the picker — applies the typed/selected folder path.
  const confirmFolderChoice = () => {
    const v = (folderDraft || '').trim().replace(/^\/+|\/+$/g, '');
    if (!v) return;
    set('defaultFolder', v);
    setFolderPickerOpen(false);
  };

  const load = useCallback(async () => {
    setLoading(true); setError(null); setInfo(null);
    try {
      const r = await axios.get('/api/doc-ingest/admin/settings');
      setFull(r.data);
      setIng(r.data?.ingestion || {});
    } catch (e) {
      setError(e?.response?.data?.message || e?.message || t('admin.screens.ingestionDefaults.loadError', 'Failed to load ingestion defaults.'));
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  // Storage-capable folder connections for the "Storage location" picker.
  useEffect(() => {
    (async () => {
      try {
        const r = await axios.get('/api/doc-ingest/admin/sources', { params: { usableForStorage: true } });
        setConnections(Array.isArray(r.data) ? r.data : []);
      } catch { setConnections([]); }
    })();
  }, []);

  const set = (k, v) => { setIng(prev => ({ ...prev, [k]: v })); setInfo(null); };

  const save = async () => {
    setSaving(true); setError(null); setInfo(null);
    try {
      const r = await axios.put('/api/doc-ingest/admin/settings', { ...full, ingestion: ing });
      setFull(r.data);
      setIng(r.data?.ingestion || {});
      setInfo(t('admin.screens.ingestionDefaults.saveInfo', 'Ingestion defaults saved. New uploads that omit a field use these values.'));
      toast.success(t('admin.screens.ingestionDefaults.saveSuccess', 'Ingestion defaults saved successfully.'));
    } catch (e) {
      setError(e?.response?.data?.message || e?.message || t('admin.screens.ingestionDefaults.saveError', 'Save failed.'));
    }
    setSaving(false);
  };

  const reset = async () => {
    if (!window.confirm(t('admin.screens.ingestionDefaults.resetConfirm', 'Reset ingestion + search settings to platform defaults?'))) return;
    setSaving(true); setError(null); setInfo(null);
    try {
      await axios.delete('/api/doc-ingest/admin/settings');
      setInfo(t('admin.screens.ingestionDefaults.resetInfo', 'Settings reset to platform defaults.'));
      await load();
      toast.success(t('admin.screens.ingestionDefaults.resetSuccess', 'Settings reset to platform defaults successfully.'));
    } catch (e) {
      setError(e?.response?.data?.message || e?.message || t('admin.screens.ingestionDefaults.resetError', 'Reset failed.'));
    }
    setSaving(false);
  };

  // Live preview of the storage path a sample PDF would land at.
  const pathPreview = () => {
    if (!ing) return '';
    const segs = [];
    if (ing.defaultFolder)     segs.push(ing.defaultFolder);
    const ob = ing.organizeBy || 'DEPARTMENT_FILETYPE';
    if (ob.includes('DEPARTMENT') && ing.defaultDepartment) segs.push(ing.defaultDepartment);
    if (ob.includes('FILETYPE')) segs.push('pdf');
    segs.push('<documentId>');
    return '{tenant}/' + segs.join('/');
  };

  if (loading) return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={24} /></Box>;

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.ingestionDefaults.scopeResource', 'ingestion defaults')} />

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>{t('admin.screens.ingestionDefaults.title', 'Ingestion Defaults')}</Typography>
          <Typography fontSize={12} color='#7D92C4'>
            {t('admin.screens.ingestionDefaults.subtitle', 'Defaults applied to uploads that omit these fields, and the folder layout used to organise stored originals. Every document also keeps its own unique ID.')}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button size='small' startIcon={<RefreshIcon />} onClick={load} disabled={saving}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.ingestionDefaults.refresh', 'Refresh')}</Button>
          <Button size='small' startIcon={<RestartAltIcon />} onClick={reset} disabled={saving}
            sx={{ textTransform: 'none', fontSize: 12, color: '#FF6B81' }}>{t('admin.screens.ingestionDefaults.resetToDefaults', 'Reset to defaults')}</Button>
        </Box>
      </Box>

      {error && <Alert severity='error'   sx={{ mb: 2, fontSize: 13 }}>{error}</Alert>}
      {info  && <Alert severity='success' sx={{ mb: 2, fontSize: 13 }}>{info}</Alert>}

      <Paper variant='outlined' sx={{ p: 2.5, borderRadius: 2 }}>
        <Typography fontWeight={600} fontSize={13} color='#C3D3F2' mb={2}>
          {t('admin.screens.ingestionDefaults.defaultMetadata', 'Default metadata')}
        </Typography>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', mb: 2 }}>
          <Autocomplete freeSolo options={CATEGORY_SUGGESTIONS}
            value={ing?.defaultCategory || ''} onInputChange={(_, v) => set('defaultCategory', v)}
            sx={{ flex: 1, minWidth: 220 }}
            renderInput={p => <TextField {...p} size='small' label={t('admin.screens.ingestionDefaults.defaultCategory', 'Default category')}
              placeholder={t('admin.screens.ingestionDefaults.categoryPlaceholder', 'e.g. Policy')} helperText={t('admin.screens.ingestionDefaults.enterOrChoose', 'Enter or choose')} />} />
          <Autocomplete freeSolo options={DEPARTMENT_SUGGESTIONS}
            value={ing?.defaultDepartment || ''} onInputChange={(_, v) => set('defaultDepartment', v)}
            sx={{ flex: 1, minWidth: 220 }}
            renderInput={p => <TextField {...p} size='small' label={t('admin.screens.ingestionDefaults.defaultDepartment', 'Default department')}
              placeholder={t('admin.screens.ingestionDefaults.departmentPlaceholder', 'e.g. Finance')} helperText={t('admin.screens.ingestionDefaults.enterOrChoose', 'Enter or choose')} />} />
        </Box>

        <Typography fontWeight={600} fontSize={13} color='#C3D3F2' mb={2}>
          {t('admin.screens.ingestionDefaults.storageLayout', 'Storage layout')}
        </Typography>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', mb: 2 }}>
          <FormControl size='small' sx={{ flex: 1, minWidth: 260 }}>
            <InputLabel>{t('admin.screens.ingestionDefaults.storageLocation', 'Storage location')}</InputLabel>
            <Select label={t('admin.screens.ingestionDefaults.storageLocation', 'Storage location')}
              value={ing?.storageConnectionId || ''} onChange={e => set('storageConnectionId', e.target.value)}>
              <MenuItem value='' sx={{ fontSize: 13 }}>
                {t('admin.screens.ingestionDefaults.storageLocal', 'Local storage (bundled)')}
              </MenuItem>
              {connections.map(c => (
                <MenuItem key={c.id} value={c.id} sx={{ fontSize: 13 }}>
                  {(c.displayName || c.kind)} · {c.kind}
                </MenuItem>
              ))}
            </Select>
          </FormControl>
        </Box>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', alignItems: 'flex-start' }}>
          <TextField size='small' label={t('admin.screens.ingestionDefaults.defaultFolder', 'Default folder')} sx={{ flex: 1, minWidth: 220 }}
            value={ing?.defaultFolder || ''} onChange={e => set('defaultFolder', e.target.value)}
            placeholder={t('admin.screens.ingestionDefaults.folderPlaceholder', 'e.g. corporate-docs')}
            helperText={t('admin.screens.ingestionDefaults.folderHelper', 'Type a folder name, or browse existing folders')}
            InputProps={{
              endAdornment: (
                <InputAdornment position='end'>
                  <Tooltip title={t('admin.screens.ingestionDefaults.chooseFromExisting', 'Choose from existing folders')}>
                    <IconButton size='small' edge='end' onClick={openFolderPicker}>
                      <FolderOpenIcon sx={{ fontSize: 18, color: '#C3D3F2' }} />
                    </IconButton>
                  </Tooltip>
                </InputAdornment>
              ),
            }} />
          <FormControl size='small' sx={{ flex: 1, minWidth: 220 }}>
            <InputLabel>{t('admin.screens.ingestionDefaults.organiseFiles', 'Organise files')}</InputLabel>
            <Select label={t('admin.screens.ingestionDefaults.organiseFiles', 'Organise files')} value={ing?.organizeBy || 'DEPARTMENT_FILETYPE'}
              onChange={e => set('organizeBy', e.target.value)}>
              {ORGANIZE_OPTIONS.map(o => (
                <MenuItem key={o.value} value={o.value} sx={{ fontSize: 13 }}>{t(`admin.screens.ingestionDefaults.${o.labelKey}`, o.labelEn)}</MenuItem>
              ))}
            </Select>
          </FormControl>
        </Box>

        <Box sx={{ mt: 2, p: 1.5, bgcolor: '#0F2450', borderRadius: 1.5, border: '1px solid #243A72',
          display: 'flex', alignItems: 'center', gap: 1 }}>
          <FolderIcon sx={{ fontSize: 16, color: '#C3D3F2' }} />
          <Typography fontSize={12} color='#C3D3F2' fontFamily='monospace'
            sx={{ wordBreak: 'break-all' }}>
            {pathPreview()}
          </Typography>
        </Box>
      </Paper>

      <Divider sx={{ my: 2.5 }} />
      <Box sx={{ display: 'flex', justifyContent: 'flex-end' }}>
        <Button onClick={save} variant='contained' size='small' disabled={saving}
          sx={{ textTransform: 'none', fontSize: 13 }}>
          {saving ? t('admin.screens.ingestionDefaults.saving', 'Saving…') : t('admin.screens.ingestionDefaults.saveDefaults', 'Save defaults')}
        </Button>
      </Box>

      {/* Explorer-style folder browser — navigate, create, then Select Folder. */}
      <Dialog open={folderPickerOpen} onClose={() => setFolderPickerOpen(false)} maxWidth='sm' fullWidth>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 1 }}>
          {t('admin.screens.ingestionDefaults.chooseFolderTitle', 'Select folder')}
          {browseBackend && (
            <Chip size='small' label={browseBackend === 'LOCAL'
              ? t('admin.screens.ingestionDefaults.backendLocal', 'Local storage')
              : browseBackend}
              sx={{ fontSize: 10, bgcolor: '#0F2450' }} />
          )}
        </DialogTitle>
        <DialogContent dividers sx={{ p: 0 }}>
          {/* ── Toolbar: up, breadcrumbs, new folder, refresh ── */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, px: 1.5, py: 1,
            borderBottom: '1px solid #243A72', bgcolor: '#0F2450' }}>
            <Tooltip title={t('admin.screens.ingestionDefaults.upOneLevel', 'Up one level')}>
              <span>
                <IconButton size='small' disabled={browseParent === null || folderLoading}
                  onClick={() => navigateTo(browseParent || '')}>
                  <ArrowUpwardIcon sx={{ fontSize: 16 }} />
                </IconButton>
              </span>
            </Tooltip>
            <Breadcrumbs separator={<ChevronRightIcon sx={{ fontSize: 13 }} />}
              sx={{ flex: 1, ml: 0.5, '& .MuiBreadcrumbs-separator': { mx: 0.25 } }}>
              <Link component='button' underline='hover' color='inherit'
                onClick={() => navigateTo('')}
                sx={{ display: 'flex', alignItems: 'center', gap: 0.5, fontSize: 12 }}>
                <HomeIcon sx={{ fontSize: 14 }} />
                {t('admin.screens.ingestionDefaults.storageRoot', 'Storage root')}
              </Link>
              {browsePath.split('/').filter(Boolean).map((seg, i, segs) => (
                <Link key={i} component='button' underline='hover' color='inherit'
                  onClick={() => navigateTo(segs.slice(0, i + 1).join('/'))}
                  sx={{ fontSize: 12, fontFamily: 'monospace' }}>
                  {seg}
                </Link>
              ))}
            </Breadcrumbs>
            <Tooltip title={t('admin.screens.ingestionDefaults.newFolder', 'New folder')}>
              <IconButton size='small' onClick={() => { setNewFolderOpen(o => !o); setNewFolderName(''); }}>
                <CreateNewFolderIcon sx={{ fontSize: 17, color: newFolderOpen ? '#8DBCFF' : '#C3D3F2' }} />
              </IconButton>
            </Tooltip>
            <Tooltip title={t('admin.screens.ingestionDefaults.refresh', 'Refresh')}>
              <IconButton size='small' onClick={() => loadBrowse(browsePath)}>
                <RefreshIcon sx={{ fontSize: 16 }} />
              </IconButton>
            </Tooltip>
          </Box>

          {/* ── Inline "New folder" row ── */}
          {newFolderOpen && (
            <Box sx={{ display: 'flex', gap: 1, alignItems: 'center', px: 1.5, py: 1,
              borderBottom: '1px solid #243A72' }}>
              <CreateNewFolderIcon sx={{ fontSize: 18, color: '#C3D3F2' }} />
              <TextField autoFocus size='small' sx={{ flex: 1 }} value={newFolderName}
                placeholder={t('admin.screens.ingestionDefaults.newFolderPlaceholder', 'New folder name')}
                onChange={e => setNewFolderName(e.target.value)}
                onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); createFolder(); } }}
                inputProps={{ style: { fontSize: 13 } }} />
              <Button size='small' variant='contained' disabled={!newFolderName.trim()}
                onClick={createFolder} sx={{ textTransform: 'none', fontSize: 12 }}>
                {t('admin.screens.ingestionDefaults.create', 'Create')}
              </Button>
            </Box>
          )}

          {/* ── Folder listing ── */}
          {folderError && <Alert severity='error' sx={{ m: 1.5, fontSize: 13 }}>{folderError}</Alert>}
          {folderLoading ? (
            <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={22} /></Box>
          ) : browseEntries.length === 0 ? (
            <Typography fontSize={12} color='#7D92C4' sx={{ p: 2.5 }}>
              {t('admin.screens.ingestionDefaults.emptyFolder', 'No sub-folders here. Use the new-folder button above, or select this folder below.')}
            </Typography>
          ) : (
            <List dense disablePadding sx={{ maxHeight: 300, overflowY: 'auto' }}>
              {browseEntries.map(entry => (
                <ListItemButton key={entry.path}
                  selected={entry.path === folderDraft}
                  onClick={() => setFolderDraft(entry.path)}
                  onDoubleClick={() => navigateTo(entry.path)}
                  sx={{ py: 0.75 }}>
                  <FolderIcon sx={{ fontSize: 18, color: '#FFB84D', mr: 1 }} />
                  <ListItemText primary={entry.name}
                    primaryTypographyProps={{ fontSize: 13, color: '#FFFFFF' }} />
                  <Tooltip title={t('admin.screens.ingestionDefaults.openFolder', 'Open')}>
                    <IconButton size='small' edge='end'
                      onClick={e => { e.stopPropagation(); navigateTo(entry.path); }}>
                      <ChevronRightIcon sx={{ fontSize: 16, color: '#7D92C4' }} />
                    </IconButton>
                  </Tooltip>
                </ListItemButton>
              ))}
            </List>
          )}
        </DialogContent>

        {/* ── Footer: Folder field + actions (Windows layout) ── */}
        <DialogActions sx={{ px: 2, py: 1.5, gap: 1 }}>
          <Typography fontSize={12} color='#C3D3F2' sx={{ flexShrink: 0 }}>
            {t('admin.screens.ingestionDefaults.folderLabel', 'Folder:')}
          </Typography>
          <TextField size='small' sx={{ flex: 1 }} value={folderDraft}
            onChange={e => setFolderDraft(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); confirmFolderChoice(); } }}
            placeholder={t('admin.screens.ingestionDefaults.folderPlaceholder', 'e.g. corporate-docs')}
            inputProps={{ style: { fontSize: 13, fontFamily: 'monospace' } }} />
          <Button size='small' variant='contained' onClick={confirmFolderChoice}
            disabled={!folderDraft.trim()}
            sx={{ textTransform: 'none', fontSize: 12, flexShrink: 0 }}>
            {t('admin.screens.ingestionDefaults.selectFolder', 'Select Folder')}
          </Button>
          <Button size='small' onClick={() => setFolderPickerOpen(false)}
            sx={{ textTransform: 'none', fontSize: 12, flexShrink: 0 }}>
            {t('admin.screens.ingestionDefaults.cancel', 'Cancel')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
