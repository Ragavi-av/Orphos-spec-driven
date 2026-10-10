import { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, Paper, Table, TableHead, TableRow, TableCell, TableBody,
  IconButton, TextField, Button, Alert, CircularProgress, Chip,
  Dialog, DialogTitle, DialogContent, DialogActions, Tooltip,
  Accordion, AccordionSummary, AccordionDetails,
} from '@mui/material';
import EditIcon       from '@mui/icons-material/Edit';
import DeleteIcon     from '@mui/icons-material/DeleteOutline';
import RefreshIcon    from '@mui/icons-material/Refresh';
import HistoryIcon    from '@mui/icons-material/History';
import AddIcon        from '@mui/icons-material/Add';
import RestoreIcon    from '@mui/icons-material/SettingsBackupRestore';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';
import axios from './axiosAuth';
import TenantScopeBanner from './TenantScopeBanner';
import { useToast } from './ToastProvider';

const emptyDraft = { kind: '', promptKey: '', name: '', content: '', description: '', orderIndex: 0 };

/**
 * Shared admin editor for the versioned LLM prompts served by
 * nl-query-service (/api/nl-query/admin/prompts). Each admin surface renders
 * this with its own subset of prompt "kinds" — the AI Co-pilot prompts tab,
 * the Communications "Email AI" tab and the Document Ingestion "Document AI"
 * tab all share the same store, versioning and rollback semantics.
 *
 * Props:
 *   kinds           [{kind, label, blurb}] — the kind groups this surface owns
 *                   (rows of other kinds are neither shown nor reset from here)
 *   title, intro    header texts (already translated by the caller)
 *   scopeResource   TenantScopeBanner resource label
 *   placeholderHint helper text under the prompt field (placeholders in use)
 *   defaultExpandedKind  which accordion starts open (default: the first kind)
 */
export default function PromptKindsEditor({
  kinds, title, intro, scopeResource, placeholderHint, defaultExpandedKind,
}) {
  const { t } = useTranslation();
  const toast = useToast();

  const kindLabelMap = useMemo(() => Object.fromEntries(kinds.map(k => [k.kind, k.label])), [kinds]);

  // Localized label/blurb per prompt "kind"; falls back to the bundled English.
  const kindLabel = (kind, fallback) =>
    t(`admin.screens.prompts.kind.${kind}.label`, fallback || kindLabelMap[kind] || kind);
  const kindBlurb = (kind, fallback) =>
    t(`admin.screens.prompts.kind.${kind}.blurb`, fallback || '');

  const [rows, setRows]       = useState([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr]         = useState(null);

  const [dialogOpen, setDialogOpen] = useState(false);
  const [isNew, setIsNew]           = useState(false);
  const [draft, setDraft]           = useState(emptyDraft);
  const [saving, setSaving]         = useState(false);
  const [draftErr, setDraftErr]     = useState(null);

  const [histOpen, setHistOpen]   = useState(false);
  const [histKey, setHistKey]     = useState('');
  const [histRows, setHistRows]   = useState([]);
  const [histLoading, setHistLoading] = useState(false);

  // Scoped history browser (null kind = all groups of this surface)
  const [browserOpen, setBrowserOpen] = useState(false);
  const [browserKind, setBrowserKind] = useState(null);

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      const r = await axios.get('/api/nl-query/admin/prompts');
      setRows(Array.isArray(r.data?.prompts) ? r.data.prompts : []);
    } catch (e) {
      setErr(e?.response?.data?.error || e?.response?.data?.message || t('admin.screens.prompts.errLoad', 'Could not load LLM prompts.'));
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const grouped = useMemo(() => {
    const by = {};
    for (const r of rows) (by[r.kind] = by[r.kind] || []).push(r);
    for (const k of Object.keys(by)) by[k].sort((a, b) => (a.orderIndex - b.orderIndex)
      || String(a.promptKey).localeCompare(String(b.promptKey)));
    return by;
  }, [rows]);

  const isTuning = (kind) => kind === 'tuning';

  const openEdit = (row) => {
    setIsNew(false);
    setDraft({
      kind: row.kind, promptKey: row.promptKey, name: row.name || '',
      content: row.content || '', description: row.description || '',
      orderIndex: row.orderIndex ?? 0,
    });
    setDraftErr(null);
    setDialogOpen(true);
  };

  const openAdd = (kind) => {
    setIsNew(true);
    setDraft({ ...emptyDraft, kind });
    setDraftErr(null);
    setDialogOpen(true);
  };

  const closeDialog = () => { if (!saving) { setDialogOpen(false); } };

  const save = async () => {
    setDraftErr(null);
    if (isNew && !draft.promptKey.trim()) { setDraftErr(t('admin.screens.prompts.errKeyRequired', 'A unique key is required.')); return; }
    setSaving(true);
    try {
      if (isNew) {
        await axios.post('/api/nl-query/admin/prompts', {
          kind: draft.kind,
          promptKey: draft.promptKey.trim(),
          name: draft.name,
          content: draft.content,
          description: draft.description,
          orderIndex: Number(draft.orderIndex) || 0,
        });
      } else {
        await axios.put(`/api/nl-query/admin/prompts/${encodeURIComponent(draft.promptKey)}`, {
          name: draft.name,
          content: draft.content,
          description: draft.description,
          orderIndex: Number(draft.orderIndex) || 0,
        });
      }
      setDialogOpen(false);
      await load();
      toast.success(isNew ? t('admin.screens.prompts.createSuccess', 'Prompt created successfully.') : t('admin.screens.prompts.saveSuccess', 'Prompt saved successfully.'));
    } catch (e) {
      setDraftErr(e?.response?.data?.error || e?.response?.data?.message || t('admin.screens.prompts.errSave', 'Save failed.'));
    }
    setSaving(false);
  };

  const remove = async (row) => {
    if (!window.confirm(t('admin.screens.prompts.confirmDelete', 'Delete prompt "{{name}}"? This removes all its versions.', { name: row.name || row.promptKey }))) return;
    try {
      await axios.delete(`/api/nl-query/admin/prompts/${encodeURIComponent(row.promptKey)}`);
      await load();
      toast.success(t('admin.screens.prompts.deleteSuccess', 'Prompt deleted successfully.'));
    } catch (e) {
      setErr(e?.response?.data?.error || t('admin.screens.prompts.errDelete', 'Delete failed.'));
    }
  };

  const resetRow = async (row) => {
    if (!window.confirm(t('admin.screens.prompts.confirmReset', 'Restore "{{name}}" to its bundled default?', { name: row.name || row.promptKey }))) return;
    try {
      await axios.post(`/api/nl-query/admin/prompts/${encodeURIComponent(row.promptKey)}/reset`);
      await load();
      toast.success(t('admin.screens.prompts.resetSuccess', 'Prompt reset to default.'));
    } catch (e) {
      setErr(e?.response?.data?.error || t('admin.screens.prompts.errReset', 'Reset failed.'));
    }
  };

  const openHistory = async (row) => {
    setHistKey(row.promptKey); setHistRows([]); setHistLoading(true); setHistOpen(true);
    try {
      const r = await axios.get(`/api/nl-query/admin/prompts/${encodeURIComponent(row.promptKey)}/versions`);
      setHistRows(Array.isArray(r.data?.versions) ? r.data.versions : []);
    } catch {
      setHistRows([]);
    }
    setHistLoading(false);
  };

  const activate = async (promptKey, version) => {
    try {
      await axios.post(`/api/nl-query/admin/prompts/${encodeURIComponent(promptKey)}/activate/${version}`);
      setHistOpen(false);
      await load();
      toast.success(t('admin.screens.prompts.rollbackSuccess', 'Prompt rolled back to version {{version}}.', { version }));
    } catch (e) {
      setErr(e?.response?.data?.error || t('admin.screens.prompts.errActivate', 'Activate failed.'));
    }
  };

  // Bulk restore-to-defaults. kind = null restores every group OF THIS SURFACE
  // (one per-kind reset each, so other surfaces' prompts are never touched).
  const bulkReset = async (kind) => {
    const scope = kind
      ? t('admin.screens.prompts.scopeGroup', 'the "{{group}}" group', { group: kindLabel(kind) })
      : t('admin.screens.prompts.scopeAll', 'ALL prompts');
    if (!window.confirm(t('admin.screens.prompts.confirmBulkReset', 'Restore {{scope}} to the bundled defaults? Each default prompt gets a new active version; custom prompts you added are left untouched.', { scope }))) return;
    try {
      const targets = kind ? [kind] : kinds.map(k => k.kind);
      for (const k of targets) {
        await axios.post('/api/nl-query/admin/prompts/reset', null, { params: { kind: k } });
      }
      await load();
      toast.success(kind
        ? t('admin.screens.prompts.bulkResetGroupSuccess', '"{{group}}" prompts restored to defaults.', { group: kindLabel(kind) })
        : t('admin.screens.prompts.bulkResetAllSuccess', 'All prompts restored to defaults.'));
    } catch (e) {
      setErr(e?.response?.data?.error || t('admin.screens.prompts.errRestore', 'Restore failed.'));
    }
  };

  // Open the scoped version-history browser (a picker that drills into one
  // prompt's history). kind = null browses every prompt of this surface.
  const openBrowser = (kind) => { setBrowserKind(kind || null); setBrowserOpen(true); };
  const browseToHistory = (row) => { setBrowserOpen(false); openHistory(row); };

  const browserList = browserKind
    ? (grouped[browserKind] || [])
    : kinds.flatMap(k => grouped[k.kind] || []);

  const expandedKind = defaultExpandedKind || kinds[0]?.kind;

  const defaultPlaceholderHint = t('admin.screens.prompts.promptHelper',
    'Placeholders {{q}}, {{rag}}, {{live}} are substituted at runtime.',
    { q: '{{QUESTION}}', rag: '{{RAG_CONTEXT}}', live: '{{LIVE_CONTEXT}}' });

  return (
    <Box>
      <TenantScopeBanner resource={scopeResource} />

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 1.5 }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>{title}</Typography>
          <Typography fontSize={12} color='#7D92C4'>{intro}</Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Button size='small' startIcon={<HistoryIcon />} onClick={() => openBrowser(null)}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.prompts.versionHistoryRollback', 'Version history / rollback')}</Button>
          <Button size='small' color='warning' startIcon={<RestoreIcon />} onClick={() => bulkReset(null)}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.prompts.restoreAllDefaults', 'Restore all defaults')}</Button>
          <Button size='small' startIcon={<RefreshIcon />} onClick={load}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.prompts.refresh', 'Refresh')}</Button>
        </Box>
      </Box>

      {err && <Alert severity='error' sx={{ mb: 2, fontSize: 13 }}>{err}</Alert>}

      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>
      ) : (
        kinds.map(({ kind, label, blurb }) => {
          const list = grouped[kind] || [];
          return (
            <Accordion key={kind} defaultExpanded={kind === expandedKind} disableGutters
              sx={{ mb: 1, borderRadius: 2, '&:before': { display: 'none' }, border: '1px solid #243A72' }}>
              <AccordionSummary expandIcon={<ExpandMoreIcon />}>
                <Box>
                  <Typography fontWeight={700} fontSize={13} color='#FFFFFF'>
                    {kindLabel(kind, label)} <Chip size='small' label={list.length} sx={{ ml: 1, fontSize: 10, height: 18 }} />
                  </Typography>
                  <Typography fontSize={11} color='#7D92C4'>{kindBlurb(kind, blurb)}</Typography>
                </Box>
              </AccordionSummary>
              <AccordionDetails sx={{ pt: 0 }}>
                <Box sx={{ display: 'flex', justifyContent: 'flex-end', gap: 1, mb: 1 }}>
                  <Button size='small' startIcon={<HistoryIcon />} onClick={() => openBrowser(kind)}
                    sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.prompts.versionHistoryRollback', 'Version history / rollback')}</Button>
                  <Button size='small' color='warning' startIcon={<RestoreIcon />} onClick={() => bulkReset(kind)}
                    sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.prompts.restoreDefaults', 'Restore defaults')}</Button>
                  <Button size='small' startIcon={<AddIcon />} onClick={() => openAdd(kind)}
                    sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.prompts.add', 'Add')}</Button>
                </Box>
                <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
                  <Table size='small'>
                    <TableHead>
                      <TableRow sx={{ bgcolor: '#0F2450' }}>
                        <TableCell sx={hCell}>{t('admin.screens.prompts.colName', 'Name')}</TableCell>
                        <TableCell sx={hCell}>{isTuning(kind) ? t('admin.screens.prompts.colValue', 'Value') : t('admin.screens.prompts.colPrompt', 'Prompt')}</TableCell>
                        <TableCell sx={hCell}>{t('admin.screens.prompts.colDescription', 'Description')}</TableCell>
                        <TableCell sx={hCell} align='center'>{t('admin.screens.prompts.colVer', 'Ver')}</TableCell>
                        <TableCell sx={hCell} align='right'>{t('admin.screens.prompts.colActions', 'Actions')}</TableCell>
                      </TableRow>
                    </TableHead>
                    <TableBody>
                      {list.length === 0 ? (
                        <TableRow>
                          <TableCell colSpan={5} sx={{ textAlign: 'center', py: 3, color: '#7D92C4', fontSize: 13 }}>
                            {t('admin.screens.prompts.noEntries', 'No entries.')}
                          </TableCell>
                        </TableRow>
                      ) : list.map(row => (
                        <TableRow key={row.promptKey} hover>
                          <TableCell sx={{ fontSize: 13, verticalAlign: 'top', maxWidth: 180 }}>
                            <Box sx={{ fontWeight: 600, color: '#FFFFFF' }}>{row.name || row.promptKey}</Box>
                            <Box sx={{ fontSize: 10, color: '#7D92C4', fontFamily: 'monospace' }}>{row.promptKey}</Box>
                          </TableCell>
                          <TableCell sx={{ verticalAlign: 'top', maxWidth: isTuning(kind) ? 90 : 360 }}>
                            <Box sx={{
                              fontFamily: 'monospace', fontSize: 11, color: '#FFFFFF',
                              whiteSpace: 'pre-wrap', maxHeight: 88, overflow: 'hidden',
                            }}>{row.content || <span style={{ color: '#7D92C4' }}>{t('admin.screens.prompts.empty', '(empty)')}</span>}</Box>
                          </TableCell>
                          <TableCell sx={{ fontSize: 11, color: '#C3D3F2', verticalAlign: 'top', maxWidth: 220 }}>
                            {row.description}
                          </TableCell>
                          <TableCell align='center' sx={{ verticalAlign: 'top' }}>
                            <Chip size='small' label={`v${row.version}`} sx={{ fontSize: 10, height: 18 }} />
                          </TableCell>
                          <TableCell align='right' sx={{ verticalAlign: 'top', whiteSpace: 'nowrap' }}>
                            <Tooltip title={t('admin.screens.prompts.editTip', 'Edit (new version)')}>
                              <IconButton size='small' onClick={() => openEdit(row)}><EditIcon fontSize='small' /></IconButton>
                            </Tooltip>
                            <Tooltip title={t('admin.screens.prompts.versionHistoryRollback', 'Version history / rollback')}>
                              <IconButton size='small' onClick={() => openHistory(row)}><HistoryIcon fontSize='small' /></IconButton>
                            </Tooltip>
                            {row.isDefault && (
                              <Tooltip title={t('admin.screens.prompts.restoreBundledTip', 'Restore bundled default')}>
                                <IconButton size='small' onClick={() => resetRow(row)}><RestoreIcon fontSize='small' /></IconButton>
                              </Tooltip>
                            )}
                            <Tooltip title={t('admin.screens.prompts.deleteTip', 'Delete')}>
                              <IconButton size='small' onClick={() => remove(row)} sx={{ color: '#FF6B81' }}>
                                <DeleteIcon fontSize='small' />
                              </IconButton>
                            </Tooltip>
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </Paper>
              </AccordionDetails>
            </Accordion>
          );
        })
      )}

      {/* ── Add / edit dialog ────────────────────────────────────────────── */}
      <Dialog open={dialogOpen} onClose={closeDialog} fullWidth maxWidth='md'>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {isNew ? t('admin.screens.prompts.addPromptTitle', 'Add prompt') : t('admin.screens.prompts.editPromptTitle', 'Edit prompt')} — {kindLabel(draft.kind)}
        </DialogTitle>
        <DialogContent>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: 1 }}>
            {isNew && (
              <TextField label={t('admin.screens.prompts.uniqueKeyLabel', 'Unique key')} size='small' value={draft.promptKey}
                onChange={e => setDraft(d => ({ ...d, promptKey: e.target.value }))}
                placeholder={`${draft.kind}.my-fragment`}
                helperText={t('admin.screens.prompts.uniqueKeyHelper', 'Stable identifier (e.g. briefing.rule7). Cannot be changed later.')} fullWidth />
            )}
            <TextField label={t('admin.screens.prompts.nameLabel', 'Name')} size='small' value={draft.name}
              onChange={e => setDraft(d => ({ ...d, name: e.target.value }))} fullWidth />
            <TextField
              label={isTuning(draft.kind) ? t('admin.screens.prompts.colValue', 'Value') : t('admin.screens.prompts.colPrompt', 'Prompt')}
              size='small' value={draft.content}
              onChange={e => setDraft(d => ({ ...d, content: e.target.value }))}
              multiline={!isTuning(draft.kind)}
              minRows={isTuning(draft.kind) ? 1 : 8}
              fullWidth
              InputProps={{ sx: isTuning(draft.kind) ? {} : { fontFamily: 'monospace', fontSize: 13 } }}
              helperText={isTuning(draft.kind)
                ? t('admin.screens.prompts.tuningHelper', 'A number, e.g. 0.3 or 4096.')
                : (placeholderHint || defaultPlaceholderHint)} />
            <TextField label={t('admin.screens.prompts.descriptionLabel', 'Description')} size='small' value={draft.description}
              onChange={e => setDraft(d => ({ ...d, description: e.target.value }))}
              helperText={t('admin.screens.prompts.descriptionHelper', 'What this prompt does — shown in the table.')} fullWidth />
            <TextField label={t('admin.screens.prompts.orderLabel', 'Order')} size='small' type='number' value={draft.orderIndex}
              onChange={e => setDraft(d => ({ ...d, orderIndex: e.target.value }))}
              helperText={t('admin.screens.prompts.orderHelper', 'Assembly order within the group (lower first).')}
              sx={{ maxWidth: 160 }} />
            {draftErr && <Alert severity='error' sx={{ fontSize: 12 }}>{draftErr}</Alert>}
          </Box>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={closeDialog} size='small' disabled={saving}>{t('admin.screens.prompts.cancel', 'Cancel')}</Button>
          <Button onClick={save} variant='contained' size='small' disabled={saving}>
            {saving ? t('admin.screens.prompts.saving', 'Saving…') : (isNew ? t('admin.screens.prompts.create', 'Create') : t('admin.screens.prompts.saveNewVersion', 'Save new version'))}
          </Button>
        </DialogActions>
      </Dialog>

      {/* ── History / rollback dialog ────────────────────────────────────── */}
      <Dialog open={histOpen} onClose={() => setHistOpen(false)} fullWidth maxWidth='md'>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {t('admin.screens.prompts.versionHistory', 'Version history')} — <span style={{ fontFamily: 'monospace', fontSize: 13 }}>{histKey}</span>
        </DialogTitle>
        <DialogContent>
          {histLoading ? (
            <Box sx={{ p: 3, textAlign: 'center' }}><CircularProgress size={24} /></Box>
          ) : histRows.length === 0 ? (
            <Typography fontSize={13} color='#7D92C4' sx={{ py: 2 }}>{t('admin.screens.prompts.noHistory', 'No history.')}</Typography>
          ) : (
            <Table size='small'>
              <TableHead>
                <TableRow sx={{ bgcolor: '#0F2450' }}>
                  <TableCell sx={hCell} align='center'>{t('admin.screens.prompts.colVer', 'Ver')}</TableCell>
                  <TableCell sx={hCell}>{t('admin.screens.prompts.colContent', 'Content')}</TableCell>
                  <TableCell sx={hCell}>{t('admin.screens.prompts.colUpdated', 'Updated')}</TableCell>
                  <TableCell sx={hCell} align='right'>{t('admin.screens.prompts.colAction', 'Action')}</TableCell>
                </TableRow>
              </TableHead>
              <TableBody>
                {histRows.map(v => (
                  <TableRow key={v.version} hover>
                    <TableCell align='center' sx={{ verticalAlign: 'top' }}>
                      <Chip size='small' label={`v${v.version}`}
                        color={v.active ? 'success' : 'default'} sx={{ fontSize: 10, height: 18 }} />
                    </TableCell>
                    <TableCell sx={{ verticalAlign: 'top', maxWidth: 380 }}>
                      <Box sx={{ fontFamily: 'monospace', fontSize: 11, whiteSpace: 'pre-wrap',
                        maxHeight: 96, overflow: 'hidden', color: '#FFFFFF' }}>{v.content}</Box>
                    </TableCell>
                    <TableCell sx={{ fontSize: 11, color: '#C3D3F2', verticalAlign: 'top' }}>
                      {v.updatedAt}<br /><span style={{ color: '#7D92C4' }}>{v.createdBy}</span>
                    </TableCell>
                    <TableCell align='right' sx={{ verticalAlign: 'top' }}>
                      {v.active
                        ? <Chip size='small' label={t('admin.screens.prompts.active', 'Active')} color='success' sx={{ fontSize: 10, height: 18 }} />
                        : <Button size='small' onClick={() => activate(histKey, v.version)}
                            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.prompts.activate', 'Activate')}</Button>}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setHistOpen(false)} size='small'>{t('admin.screens.prompts.close', 'Close')}</Button>
        </DialogActions>
      </Dialog>

      {/* ── Scoped version-history browser (global / per-group) ──────────── */}
      <Dialog open={browserOpen} onClose={() => setBrowserOpen(false)} fullWidth maxWidth='sm'>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {t('admin.screens.prompts.versionHistoryRollback', 'Version history / rollback')} — {browserKind ? kindLabel(browserKind) : t('admin.screens.prompts.allPrompts', 'All prompts')}
        </DialogTitle>
        <DialogContent>
          <Typography fontSize={12} color='#C3D3F2' sx={{ mb: 1 }}>
            {t('admin.screens.prompts.browserHint', 'Pick a prompt to view its versions and roll back to an earlier one.')}
          </Typography>
          {browserList.length === 0 ? (
            <Typography fontSize={13} color='#7D92C4' sx={{ py: 2 }}>{t('admin.screens.prompts.noPrompts', 'No prompts.')}</Typography>
          ) : (
            <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
              <Table size='small'>
                <TableBody>
                  {browserList.map(row => (
                    <TableRow key={row.promptKey} hover sx={{ cursor: 'pointer' }}
                      onClick={() => browseToHistory(row)}>
                      <TableCell sx={{ fontSize: 13 }}>
                        <Box sx={{ fontWeight: 600, color: '#FFFFFF' }}>{row.name || row.promptKey}</Box>
                        <Box sx={{ fontSize: 10, color: '#7D92C4', fontFamily: 'monospace' }}>{row.promptKey}</Box>
                      </TableCell>
                      <TableCell align='right' sx={{ whiteSpace: 'nowrap' }}>
                        <Chip size='small' label={`v${row.version}`} sx={{ fontSize: 10, height: 18, mr: 1 }} />
                        <Button size='small' startIcon={<HistoryIcon />}
                          sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.prompts.history', 'History')}</Button>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            </Paper>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={() => setBrowserOpen(false)} size='small'>{t('admin.screens.prompts.close', 'Close')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}

const hCell = { fontSize: 12, fontWeight: 700, color: '#C3D3F2' };
