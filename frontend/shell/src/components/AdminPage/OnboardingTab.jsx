import { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress, Chip, Divider,
  Table, TableHead, TableRow, TableCell, TableBody,
  Select, MenuItem, Checkbox, FormControl, Tooltip, TextField,
  Dialog, DialogTitle, DialogContent, DialogActions, InputLabel,
} from '@mui/material';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import EditIcon from '@mui/icons-material/Edit';
import AddIcon from '@mui/icons-material/Add';
import axios from 'eip-shell-context/axiosAuth';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { apiErrorMessage } from 'eip-shell-context/apiError';
import { useToast } from 'eip-shell-context/ToastProvider';
import { notifyDashboardLayoutChanged } from 'eip-shell-context/DashboardLayoutProvider';

const COLUMN_TYPES = ['STRING', 'INTEGER', 'NUMBER', 'DATE', 'BOOLEAN'];
const COLUMN_ROLES = ['ID', 'MEASURE', 'CATEGORY', 'DATE', 'TEXT'];
const WIDGET_TYPES = ['COUNT', 'SUM', 'AVG', 'BREAKDOWN', 'TREND'];
// Mirrors SchemaInferencer.PALETTE so admin-added widgets match the proposed ones.
const PALETTE = ['#8DBCFF', '#8DBCFF', '#3DDC97', '#FFB84D', '#FF6B81', '#8DBCFF', '#8DBCFF', '#3DDC97'];
const IDENT = /^[A-Za-z][A-Za-z0-9_]*$/;

/** Sanitise live input into a safe ArcadeDB/OWL property identifier. */
const cleanIdent = (v) => {
  let s = (v || '').replace(/[^A-Za-z0-9_]/g, '');
  if (s && !/^[A-Za-z]/.test(s)) s = 'p' + s;
  return s;
};

/**
 * Data & AI → Data Onboarding. Upload .xlsx/.csv files, review AND EDIT the
 * auto-inferred model before committing:
 *  - per column: include/exclude, type, semantic role, and the DB property name
 *    (the source header stays fixed — it is how rows are matched in the file);
 *  - per entity: the upsert key;
 *  - widgets: include/exclude, edit label/type/entity/columns, or add new ones.
 * The commit API accepts the edited plan verbatim (OnboardingDtos.CommitRequest);
 * client-only `_included` flags are stripped when building the payload.
 */
export default function OnboardingTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const [files, setFiles]       = useState([]);
  const [analysis, setAnalysis] = useState(null);
  const [busy, setBusy]         = useState(false);
  const [err, setErr]           = useState(null);
  const [current, setCurrent]   = useState(null);   // what's already onboarded for this tenant
  const [wEdit, setWEdit]       = useState(null);   // { draft, isNew } while the widget dialog is open

  const loadState = useCallback(() => {
    axios.get('/api/admin/onboarding/state')
      .then(r => setCurrent(r.data))
      .catch(() => setCurrent(null));
  }, []);
  useEffect(() => { loadState(); }, [loadState]);

  const analyze = async () => {
    if (files.length === 0) { setErr(t('admin.screens.onboarding.errNoFiles', 'Choose one or more .xlsx or .csv files first.')); return; }
    setErr(null); setBusy(true); setAnalysis(null);
    try {
      const fd = new FormData();
      files.forEach(f => fd.append('files', f));
      const r = await axios.post('/api/admin/onboarding/analyze', fd,
        { headers: { 'Content-Type': 'multipart/form-data' } });
      // Decorate with client-only inclusion flags (stripped again on commit).
      setAnalysis({
        ...r.data,
        entities: (r.data.entities || []).map(e => ({
          ...e,
          columns: (e.columns || []).map(c => ({ ...c, _included: true })),
        })),
        widgets: (r.data.widgets || []).map(w => ({ ...w, _included: true })),
      });
    } catch (e) { setErr(apiErrorMessage(e, t('admin.screens.onboarding.errAnalysisFailed', 'Analysis failed'))); }
    setBusy(false);
  };

  // ── review-stage edit helpers ─────────────────────────────────────────
  const patchColumn = (entityIdx, colName, patch) => setAnalysis(prev => {
    const entities = prev.entities.map((e, i) => {
      if (i !== entityIdx) return e;
      const target = e.columns.find(c => c.name === colName);
      const columns = e.columns.map(c => (c.name === colName ? { ...c, ...patch } : c));
      // Excluding the key column clears the entity's upsert key.
      const idProperty = (patch._included === false && e.idProperty === target?.property) ? null : e.idProperty;
      return { ...e, columns, idProperty };
    });
    return { ...prev, entities };
  });

  /**
   * Rename a column's DB property and cascade to the key + widget references.
   * The column is identified by its source header (`name`), which never changes —
   * matching on the property itself would misfire if two columns were mid-edit
   * with the same (e.g. empty) value.
   */
  const renameProperty = (entityIdx, colName, rawValue) => setAnalysis(prev => {
    const newProp = cleanIdent(rawValue);
    const entity = prev.entities[entityIdx];
    const oldProp = entity.columns.find(c => c.name === colName)?.property;
    const entities = prev.entities.map((e, i) => {
      if (i !== entityIdx) return e;
      return {
        ...e,
        idProperty: e.idProperty === oldProp ? (newProp || null) : e.idProperty,
        columns: e.columns.map(c => (c.name === colName ? { ...c, property: newProp } : c)),
      };
    });
    const widgets = prev.widgets.map(w => {
      if (w.entity !== entity.className || !oldProp) return w;
      const swap = p => (p === oldProp ? newProp : p);
      return { ...w, column: swap(w.column), groupBy: swap(w.groupBy), dateColumn: swap(w.dateColumn) };
    });
    return { ...prev, entities, widgets };
  });

  const setEntityKey = (entityIdx, property) => setAnalysis(prev => ({
    ...prev,
    entities: prev.entities.map((e, i) => (i === entityIdx ? { ...e, idProperty: property || null } : e)),
  }));

  const toggleWidget = (id) => setAnalysis(prev => ({
    ...prev,
    widgets: prev.widgets.map(w => (w.id === id ? { ...w, _included: !w._included } : w)),
  }));

  /** A widget is broken if any column it references has been excluded or renamed away. */
  const widgetBroken = (w) => {
    if (!analysis) return false;
    const entity = analysis.entities.find(e => e.className === w.entity);
    if (!entity) return true;
    const included = new Set(entity.columns.filter(c => c._included).map(c => c.property));
    return [w.column, w.groupBy, w.dateColumn].some(p => p && !included.has(p));
  };

  // ── widget dialog ──────────────────────────────────────────────────────
  // The editor drives its entity/column pickers from either the fresh analysis
  // (review stage) or the already-onboarded model (`current.entityModels`) when
  // managing committed widgets. `committed` also decides how a save persists:
  // in-memory (analysis) vs. an API call.
  const editorEntities = () =>
    (wEdit?.committed ? (current?.entityModels || []) : (analysis?.entities || []));

  const openWidgetEditor = (w, committed = false) => {
    const ents = committed ? (current?.entityModels || []) : (analysis?.entities || []);
    const count = committed ? (current?.widgets?.length || 0) : (analysis?.widgets?.length || 0);
    setWEdit(w
      ? { isNew: false, committed, draft: { ...w } }
      : {
          isNew: true, committed,
          draft: {
            id: '', label: '', type: 'COUNT',
            entity: ents[0]?.className || '',
            column: null, groupBy: null, dateColumn: null,
            color: PALETTE[count % PALETTE.length],
            defaultVisible: true, _included: true,
          },
        });
  };

  const patchDraft = (patch) => setWEdit(prev => {
    let draft = { ...prev.draft, ...patch };
    // Changing type or entity invalidates the column picks for the old shape.
    if (patch.type || patch.entity) draft = { ...draft, column: null, groupBy: null, dateColumn: null };
    return { ...prev, draft };
  });

  const widgetDialogError = () => {
    const d = wEdit?.draft;
    if (!d) return null;
    if (!d.label.trim()) return t('admin.screens.onboarding.wErrLabel', 'Widget name is required.');
    if ((d.type === 'SUM' || d.type === 'AVG' || d.type === 'TREND') && !d.column)
      return t('admin.screens.onboarding.wErrColumn', 'Pick the value column for this formula.');
    if (d.type === 'BREAKDOWN' && !d.groupBy)
      return t('admin.screens.onboarding.wErrGroupBy', 'Pick the category column to group by.');
    if (d.type === 'TREND' && !d.dateColumn)
      return t('admin.screens.onboarding.wErrDate', 'Pick the date column for the trend.');
    return null;
  };

  const saveWidget = async () => {
    const { draft, isNew, committed } = wEdit;
    const w = { ...draft, label: draft.label.trim() };

    // Managing an already-onboarded widget: persist via the API, then refresh
    // the onboarded panel and re-theme the live dashboard.
    if (committed) {
      const { _included, ...payload } = w;
      try {
        if (isNew) {
          await axios.post('/api/admin/onboarding/widgets', payload);
        } else {
          await axios.put(`/api/admin/onboarding/widgets/${encodeURIComponent(w.id)}`, payload);
        }
        toast.success(isNew
          ? t('admin.screens.onboarding.widgetAdded', 'Widget added.')
          : t('admin.screens.onboarding.widgetUpdated', 'Widget updated.'));
        setWEdit(null);
        notifyDashboardLayoutChanged();
        loadState();
      } catch (e) {
        setErr(apiErrorMessage(e, t('admin.screens.onboarding.errWidgetSave', 'Saving widget failed')));
      }
      return;
    }

    // Review stage: edit the in-memory plan; it commits with the upload.
    if (isNew) {
      const prop = w.column || w.groupBy || w.dateColumn || 'all';
      w.id = `${w.type.toLowerCase()}-${w.entity.toLowerCase()}-${prop}-${Math.random().toString(36).slice(2, 8)}`;
      setAnalysis(prev => ({ ...prev, widgets: [...prev.widgets, w] }));
    } else {
      setAnalysis(prev => ({ ...prev, widgets: prev.widgets.map(x => (x.id === w.id ? w : x)) }));
    }
    setWEdit(null);
  };

  const deleteCommittedWidget = async (w) => {
    if (!window.confirm(t('admin.screens.onboarding.confirmDeleteWidget',
      'Delete widget "{{label}}"? It will be removed from the dashboard for everyone in this tenant.', { label: w.label }))) return;
    try {
      await axios.delete(`/api/admin/onboarding/widgets/${encodeURIComponent(w.id)}`);
      toast.success(t('admin.screens.onboarding.widgetDeleted', 'Widget deleted.'));
      notifyDashboardLayoutChanged();
      loadState();
    } catch (e) {
      setErr(apiErrorMessage(e, t('admin.screens.onboarding.errWidgetDelete', 'Deleting widget failed')));
    }
  };

  const commit = async () => {
    if (!analysis?.uploadToken) return;
    // Validate property names: identifier-shaped and unique per entity.
    for (const e of analysis.entities) {
      const included = e.columns.filter(c => c._included);
      const bad = included.find(c => !IDENT.test(c.property || ''));
      if (bad) { setErr(t('admin.screens.onboarding.errBadProperty', 'Column "{{name}}" needs a valid property name (letters, digits, _; starts with a letter).', { name: bad.name })); return; }
      const seen = new Set();
      const dup = included.find(c => (seen.has(c.property) ? true : (seen.add(c.property), false)));
      if (dup) { setErr(t('admin.screens.onboarding.errDupProperty', 'Property name "{{prop}}" is used by more than one column in {{entity}}.', { prop: dup.property, entity: e.className })); return; }
    }
    const entities = analysis.entities
      .map(e => ({
        className: e.className, label: e.label, sourceFile: e.sourceFile,
        sourceSheet: e.sourceSheet, rowCount: e.rowCount, idProperty: e.idProperty,
        columns: e.columns.filter(c => c._included)
          .map(({ _included, ...c }) => c),
      }))
      .filter(e => e.columns.length > 0);
    if (entities.length === 0) { setErr(t('admin.screens.onboarding.errNoColumns', 'At least one column must stay included.')); return; }
    const widgets = analysis.widgets
      .filter(w => w._included && !widgetBroken(w))
      .map(({ _included, ...w }) => w);

    setErr(null); setBusy(true);
    try {
      const r = await axios.post('/api/admin/onboarding/commit', {
        entities,
        relationships: analysis.relationships,
        widgets,
        ontologyOwl:   analysis.ontologyOwl,
        uploadToken:   analysis.uploadToken,
      });
      const d = r.data;
      toast.success(t('admin.screens.onboarding.commitSuccess',
        'Loaded {{rows}} rows across {{entities}} entities, {{widgets}} widgets created.',
        { rows: d.rowsLoaded, entities: d.entitiesCreated, widgets: d.widgetsCreated }));
      notifyDashboardLayoutChanged();   // refresh dashboard with the new default + widgets
      setAnalysis(null); setFiles([]);
      loadState();                      // refresh the "currently onboarded" panel
    } catch (e) { setErr(apiErrorMessage(e, t('admin.screens.onboarding.errCommitFailed', 'Commit failed'))); }
    setBusy(false);
  };

  // Columns of the widget-dialog's selected entity, filtered for a role hint.
  const dialogColumns = (roleFilter) => {
    const entity = editorEntities().find(e => e.className === wEdit?.draft.entity);
    // Committed entity models have no client-only `_included` flag; treat those
    // columns as included (only the review stage tags excluded ones false).
    const cols = (entity?.columns || []).filter(c => c._included !== false);
    const preferred = roleFilter ? cols.filter(c => roleFilter.includes(c.role)) : cols;
    return preferred.length > 0 ? preferred : cols;   // fall back to all if none match the hint
  };

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.onboarding.scopeResource', 'data onboarding')} />
      <Typography fontWeight={700} fontSize={15} mb={0.5}>{t('admin.screens.onboarding.title', 'Data Onboarding')}</Typography>
      <Typography fontSize={13} color='#C3D3F2' mb={2}>
        {t('admin.screens.onboarding.intro',
          'Upload spreadsheets (.xlsx / .csv). We infer the data model, ontology, and dashboard widgets — review and adjust everything below, then load it for this tenant.')}
      </Typography>

      {err && <Alert severity='error' sx={{ mb: 2 }}>{err}</Alert>}

      {/* Currently onboarded */}
      {current?.onboarded && (
        <Paper variant='outlined' sx={{ p: 2, mb: 3, bgcolor: '#0F2450' }}>
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
            <Typography fontWeight={700} fontSize={13}>{t('admin.screens.onboarding.currentlyOnboarded', 'Currently onboarded')}</Typography>
            <Chip size='small' variant='outlined' label={t('admin.screens.onboarding.dbChip', 'DB: {{database}}', { database: current.database })} sx={{ fontSize: 10 }} />
            <Chip size='small' variant='outlined' label={t('admin.screens.onboarding.widgetsChip', '{{count}} widgets', { count: (current.widgets || []).length })} sx={{ fontSize: 10 }} />
          </Box>
          <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
            {(current.entities || []).map(e => (
              <Chip key={e.className} size='small'
                label={t('admin.screens.onboarding.entityRowsChip', '{{className}} · {{rows}} rows', { className: e.className, rows: e.rowCount })} />
            ))}
          </Box>
          <Typography fontSize={11} color='#C3D3F2' mt={1}>
            {t('admin.screens.onboarding.reuploadNote', 'Re-uploading updates existing records (matched by key) and inserts new ones.')}
          </Typography>

          {/* Manage the committed widgets directly — no re-upload needed. Edit
              opens the same editor used during review; delete removes it for the
              whole tenant. Requires the onboarded model (entityModels) for the
              editor's column pickers. */}
          <Divider sx={{ my: 1.5 }} />
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.75, flexWrap: 'wrap' }}>
            <Typography fontWeight={700} fontSize={12}>
              {t('admin.screens.onboarding.manageWidgets', 'Dashboard widgets')}
            </Typography>
            {(current.entityModels || []).length > 0 && (
              <Button size='small' startIcon={<AddIcon sx={{ fontSize: 16 }} />}
                onClick={() => openWidgetEditor(null, true)}
                sx={{ fontSize: 11, textTransform: 'none' }}>
                {t('admin.screens.onboarding.addWidget', 'Add widget')}
              </Button>
            )}
          </Box>
          <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap' }}>
            {(current.widgets || []).map(w => {
              const editable = (current.entityModels || []).length > 0;
              return (
                <Tooltip key={w.id} title={editable
                  ? t('admin.screens.onboarding.widgetEditHint', 'Click to edit · use ✕ to delete')
                  : t('admin.screens.onboarding.widgetDeleteOnly', 'Click ✕ to delete')}>
                  <Chip size='small' clickable={editable}
                    onClick={editable ? () => openWidgetEditor(w, true) : undefined}
                    onDelete={() => deleteCommittedWidget(w)}
                    label={t('admin.screens.onboarding.widgetChip', '{{label}} ({{type}})', { label: w.label, type: w.type })}
                    sx={{ bgcolor: 'rgba(91, 157, 255, 0.14)', border: '1px solid rgba(91, 157, 255, 0.42)' }} />
                </Tooltip>
              );
            })}
            {(current.widgets || []).length === 0 && (
              <Typography fontSize={11} color='#7D92C4'>
                {t('admin.screens.onboarding.noCommittedWidgets', 'No widgets yet — use “Add widget”.')}
              </Typography>
            )}
          </Box>
        </Paper>
      )}

      {/* Upload */}
      <Paper variant='outlined' sx={{ p: 2, mb: 3 }}>
        <Box sx={{ display: 'flex', gap: 1, flexWrap: 'wrap', alignItems: 'center' }}>
          <Button component='label' variant='outlined' size='small' startIcon={<UploadFileIcon />}>
            {t('admin.screens.onboarding.chooseFiles', 'Choose files')}
            <input hidden type='file' multiple accept='.xlsx,.csv'
              onChange={e => { setFiles(Array.from(e.target.files || [])); setAnalysis(null); }} />
          </Button>
          {files.map(f => <Chip key={f.name} label={f.name} size='small' />)}
          <Box sx={{ flex: 1 }} />
          <Button variant='contained' size='small' onClick={analyze} disabled={busy || files.length === 0}>
            {busy && !analysis ? <CircularProgress size={16} /> : t('admin.screens.onboarding.analyze', 'Analyze')}
          </Button>
        </Box>
      </Paper>

      {/* Review + edit */}
      {analysis && (
        <>
          {(analysis.warnings || []).length > 0 && (
            <Alert severity='warning' sx={{ mb: 2 }}>
              {analysis.warnings.map((w, i) => <div key={i}>{w}</div>)}
            </Alert>
          )}

          <Typography fontWeight={700} fontSize={14} mb={0.5}>{t('admin.screens.onboarding.detectedEntities', 'Detected entities')}</Typography>
          <Typography fontSize={12} color='#C3D3F2' mb={1}>
            {t('admin.screens.onboarding.editHint',
              'Adjust each column’s stored property name, type and role, untick columns you don’t want loaded, and pick the key used to match records on re-upload.')}
          </Typography>
          {(analysis.entities || []).map((e, ei) => (
            <Paper key={e.className} variant='outlined' sx={{ p: 2, mb: 2 }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
                <Typography fontWeight={700} fontSize={13}>{e.className}</Typography>
                <Chip size='small' label={t('admin.screens.onboarding.rowsChip', '{{rows}} rows', { rows: e.rowCount })} />
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5 }}>
                  <Typography fontSize={11} color='#C3D3F2'>{t('admin.screens.onboarding.keyLabel', 'Key:')}</Typography>
                  <FormControl size='small' variant='standard'>
                    <Select value={e.idProperty || ''} displayEmpty sx={{ fontSize: 12 }}
                      onChange={ev => setEntityKey(ei, ev.target.value)}>
                      <MenuItem value=''><em>{t('admin.screens.onboarding.keyNone', 'none — replace all rows on re-upload')}</em></MenuItem>
                      {e.columns.filter(c => c._included).map(c => (
                        <MenuItem key={c.property} value={c.property} sx={{ fontSize: 12 }}>{c.name}</MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                </Box>
                <Typography fontSize={11} color='#7D92C4'>
                  {e.sourceFile}{e.sourceSheet ? ` · ${e.sourceSheet}` : ''}
                </Typography>
              </Box>
              <CollapsibleSection title={t('admin.screens.onboarding.columnsSection', 'Columns')}
                count={(e.columns || []).length} detailsSx={{ p: 0 }}>
              <Table size='small'>
                <TableHead>
                  <TableRow>
                    <TableCell padding='checkbox'>{t('admin.screens.onboarding.colInclude', 'Load')}</TableCell>
                    <TableCell>{t('admin.screens.onboarding.colColumn', 'Source column')}</TableCell>
                    <TableCell>{t('admin.screens.onboarding.colProperty', 'Stored as (property)')}</TableCell>
                    <TableCell>{t('admin.screens.onboarding.colType', 'Type')}</TableCell>
                    <TableCell>{t('admin.screens.onboarding.colRole', 'Role')}</TableCell>
                  </TableRow>
                </TableHead>
                <TableBody>
                  {(e.columns || []).map(c => (
                    <TableRow key={c.name} sx={{ opacity: c._included ? 1 : 0.45 }}>
                      <TableCell padding='checkbox'>
                        <Checkbox size='small' checked={c._included}
                          onChange={ev => patchColumn(ei, c.name, { _included: ev.target.checked })} />
                      </TableCell>
                      <TableCell>{c.name}</TableCell>
                      <TableCell>
                        <TextField variant='standard' size='small' value={c.property}
                          disabled={!c._included}
                          error={c._included && !IDENT.test(c.property || '')}
                          inputProps={{ style: { fontSize: 12 } }}
                          onChange={ev => renameProperty(ei, c.name, ev.target.value)} />
                      </TableCell>
                      <TableCell>
                        <FormControl size='small' variant='standard' disabled={!c._included}>
                          <Select value={c.type} sx={{ fontSize: 12 }}
                            onChange={ev => patchColumn(ei, c.name, { type: ev.target.value })}>
                            {COLUMN_TYPES.map(x => <MenuItem key={x} value={x} sx={{ fontSize: 12 }}>{x}</MenuItem>)}
                          </Select>
                        </FormControl>
                      </TableCell>
                      <TableCell>
                        <FormControl size='small' variant='standard' disabled={!c._included}>
                          <Select value={c.role} sx={{ fontSize: 12 }}
                            onChange={ev => patchColumn(ei, c.name, { role: ev.target.value })}>
                            {COLUMN_ROLES.map(x => <MenuItem key={x} value={x} sx={{ fontSize: 12 }}>{x}</MenuItem>)}
                          </Select>
                        </FormControl>
                      </TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
              </CollapsibleSection>
            </Paper>
          ))}

          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 0.5 }}>
            <Typography fontWeight={700} fontSize={14}>{t('admin.screens.onboarding.proposedWidgets', 'Proposed widgets')}</Typography>
            <Button size='small' startIcon={<AddIcon />} onClick={() => openWidgetEditor(null)}>
              {t('admin.screens.onboarding.addWidget', 'Add widget')}
            </Button>
          </Box>
          <Typography fontSize={12} color='#C3D3F2' mb={1}>
            {t('admin.screens.onboarding.widgetHint', 'Click a widget to include or exclude it; use the pencil to edit its name, formula and columns.')}
          </Typography>
          <Paper variant='outlined' sx={{ p: 2, mb: 2, display: 'flex', gap: 1, flexWrap: 'wrap' }}>
            {(analysis.widgets || []).map(w => {
              const broken = widgetBroken(w);
              const active = w._included && !broken;
              const chip = (
                <Chip key={w.id} size='small' clickable={!broken}
                  onClick={broken ? undefined : () => toggleWidget(w.id)}
                  deleteIcon={<EditIcon sx={{ fontSize: 14 }} />}
                  onDelete={() => openWidgetEditor(w)}
                  label={t('admin.screens.onboarding.widgetChip', '{{label}} ({{type}})', { label: w.label, type: w.type })}
                  sx={{
                    bgcolor: active ? 'rgba(91, 157, 255, 0.14)' : undefined,
                    border: active ? '1px solid rgba(91, 157, 255, 0.42)' : '1px solid #243A72',
                    opacity: active ? 1 : 0.45,
                    textDecoration: active ? 'none' : 'line-through',
                  }} />
              );
              return broken
                ? <Tooltip key={w.id} title={t('admin.screens.onboarding.widgetBroken', 'Unavailable — a column this widget needs is excluded (edit it to fix)')}>{chip}</Tooltip>
                : chip;
            })}
            {(analysis.widgets || []).length === 0 && (
              <Typography fontSize={12} color='#7D92C4'>{t('admin.screens.onboarding.noWidgets', 'No widgets yet — use “Add widget”.')}</Typography>
            )}
          </Paper>

          <Divider sx={{ my: 2 }} />
          <Box sx={{ display: 'flex', gap: 1, alignItems: 'center' }}>
            <Button variant='contained' onClick={commit} disabled={busy}>
              {busy ? <CircularProgress size={16} /> : t('admin.screens.onboarding.commit', 'Commit — load data & build dashboard')}
            </Button>
            <Button variant='text' onClick={() => setAnalysis(null)} disabled={busy}>{t('admin.screens.onboarding.discard', 'Discard')}</Button>
            <Box sx={{ flex: 1 }} />
            <Typography fontSize={11} color='#7D92C4'>
              {t('admin.screens.onboarding.commitNote', 'Commits load into this tenant; re-committing replaces the previous load.')}
            </Typography>
          </Box>
        </>
      )}

      {/* Widget editor dialog */}
      {wEdit && (
        <Dialog open onClose={() => setWEdit(null)} maxWidth='xs' fullWidth>
          <DialogTitle sx={{ fontSize: 15 }}>
            {wEdit.isNew
              ? t('admin.screens.onboarding.wNewTitle', 'New widget')
              : t('admin.screens.onboarding.wEditTitle', 'Edit widget')}
          </DialogTitle>
          <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 2, pt: '8px !important' }}>
            <TextField label={t('admin.screens.onboarding.wName', 'Name')} size='small' fullWidth
              value={wEdit.draft.label} onChange={ev => patchDraft({ label: ev.target.value })} />
            <FormControl size='small' fullWidth>
              <InputLabel>{t('admin.screens.onboarding.wEntity', 'Entity')}</InputLabel>
              <Select label={t('admin.screens.onboarding.wEntity', 'Entity')} value={wEdit.draft.entity}
                onChange={ev => patchDraft({ entity: ev.target.value })}>
                {editorEntities().map(e => <MenuItem key={e.className} value={e.className}>{e.className}</MenuItem>)}
              </Select>
            </FormControl>
            <FormControl size='small' fullWidth>
              <InputLabel>{t('admin.screens.onboarding.wFormula', 'Formula')}</InputLabel>
              <Select label={t('admin.screens.onboarding.wFormula', 'Formula')} value={wEdit.draft.type}
                onChange={ev => patchDraft({ type: ev.target.value })}>
                {WIDGET_TYPES.map(x => <MenuItem key={x} value={x}>{x}</MenuItem>)}
              </Select>
            </FormControl>
            {(wEdit.draft.type === 'SUM' || wEdit.draft.type === 'AVG' || wEdit.draft.type === 'TREND') && (
              <FormControl size='small' fullWidth>
                <InputLabel>{t('admin.screens.onboarding.wColumn', 'Value column')}</InputLabel>
                <Select label={t('admin.screens.onboarding.wColumn', 'Value column')} value={wEdit.draft.column || ''}
                  onChange={ev => patchDraft({ column: ev.target.value })}>
                  {dialogColumns(['MEASURE']).map(c => <MenuItem key={c.property} value={c.property}>{c.name}</MenuItem>)}
                </Select>
              </FormControl>
            )}
            {wEdit.draft.type === 'BREAKDOWN' && (
              <FormControl size='small' fullWidth>
                <InputLabel>{t('admin.screens.onboarding.wGroupBy', 'Group by')}</InputLabel>
                <Select label={t('admin.screens.onboarding.wGroupBy', 'Group by')} value={wEdit.draft.groupBy || ''}
                  onChange={ev => patchDraft({ groupBy: ev.target.value })}>
                  {dialogColumns(['CATEGORY', 'TEXT']).map(c => <MenuItem key={c.property} value={c.property}>{c.name}</MenuItem>)}
                </Select>
              </FormControl>
            )}
            {wEdit.draft.type === 'TREND' && (
              <FormControl size='small' fullWidth>
                <InputLabel>{t('admin.screens.onboarding.wDate', 'Date column')}</InputLabel>
                <Select label={t('admin.screens.onboarding.wDate', 'Date column')} value={wEdit.draft.dateColumn || ''}
                  onChange={ev => patchDraft({ dateColumn: ev.target.value })}>
                  {dialogColumns(['DATE']).map(c => <MenuItem key={c.property} value={c.property}>{c.name}</MenuItem>)}
                </Select>
              </FormControl>
            )}
            {widgetDialogError() && <Alert severity='info' sx={{ py: 0 }}>{widgetDialogError()}</Alert>}
          </DialogContent>
          <DialogActions>
            <Button onClick={() => setWEdit(null)}>{t('admin.screens.onboarding.wCancel', 'Cancel')}</Button>
            <Button variant='contained' onClick={saveWidget} disabled={!!widgetDialogError()}>
              {t('admin.screens.onboarding.wSave', 'Save')}
            </Button>
          </DialogActions>
        </Dialog>
      )}
    </Box>
  );
}
