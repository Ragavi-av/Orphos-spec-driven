import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress, Chip, Tooltip,
  Table, TableHead, TableRow, TableCell, TableBody, IconButton, Divider,
  Dialog, DialogTitle, DialogContent, DialogActions,
  TextField, Select, MenuItem, FormControl, InputLabel, FormControlLabel, Switch,
  OutlinedInput,
} from '@mui/material';
import AddIcon          from '@mui/icons-material/Add';
import EditIcon         from '@mui/icons-material/Edit';
import DeleteIcon       from '@mui/icons-material/Delete';
import RefreshIcon      from '@mui/icons-material/Refresh';
import ArrowForwardIcon from '@mui/icons-material/ArrowForward';
import AutoFixHighIcon  from '@mui/icons-material/AutoFixHigh';
import InfoOutlinedIcon from '@mui/icons-material/InfoOutlined';
import axios from 'eip-shell-context/axiosAuth';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { apiErrorMessage } from 'eip-shell-context/apiError';
import { useToast } from 'eip-shell-context/ToastProvider';

const BASE = '/api/data-ingestion/admin';

const TARGET_TYPES = ['STRING', 'INTEGER', 'NUMBER', 'BOOLEAN', 'DATE'];

// Transform catalog: value → whether it takes an argument (serialized NAME:<arg>).
const TRANSFORMS = [
  { value: 'IDENTITY',     arg: false },
  { value: 'TRIM',         arg: false },
  { value: 'UPPER',        arg: false },
  { value: 'LOWER',        arg: false },
  { value: 'CONCAT',       arg: true,  hint: 'separator' },
  { value: 'PREFIX',       arg: true,  hint: 'prefix' },
  { value: 'SUFFIX',       arg: true,  hint: 'suffix' },
  { value: 'REPLACE',      arg: true,  hint: 'old=new' },
  { value: 'SUBSTRING',    arg: true,  hint: 'start,end' },
  { value: 'DATE_PATTERN', arg: true,  hint: 'yyyy-MM-dd' },
];

const EMPTY_ROW = { target: '', sources: [], targetType: null, transform: '', transformArg: '' };

function camelCase(name) {
  const parts = String(name || '').split(/[^A-Za-z0-9]+/).filter(Boolean);
  if (parts.length === 0) return '';
  return parts
    .map((p, i) => i === 0 ? p.toLowerCase() : p.charAt(0).toUpperCase() + p.slice(1).toLowerCase())
    .join('');
}

/** "CONCAT: - " ← {transform:'CONCAT', transformArg:' - '}; null/'' ← no transform. */
function serializeTransform(row) {
  if (!row.transform) return null;
  const def = TRANSFORMS.find(x => x.value === row.transform);
  if (def?.arg) return `${row.transform}:${row.transformArg ?? ''}`;
  return row.transform;
}
function parseTransform(s) {
  if (!s) return { transform: '', transformArg: '' };
  const i = s.indexOf(':');
  if (i < 0) return { transform: s, transformArg: '' };
  return { transform: s.slice(0, i), transformArg: s.slice(i + 1) };
}

/** Data & AI → Data Mapping: visual source-table → target-class field mapper. */
export default function DataMappingTab() {
  const { t } = useTranslation();
  const toast = useToast();

  const [dataSources, setDataSources] = useState([]);
  const [mappings, setMappings]       = useState([]);
  const [loading, setLoading]         = useState(true);
  const [err, setErr]                 = useState(null);

  // editor state
  const [editorOpen, setEditorOpen]   = useState(false);
  const [editingId, setEditingId]     = useState(null);       // mapping id | null
  const [name, setName]               = useState('');
  const [dataSourceId, setDataSourceId] = useState('');
  const [snapshot, setSnapshot]       = useState(null);
  const [snapLoading, setSnapLoading] = useState(false);
  const [tableKey, setTableKey]       = useState('');         // "schema|name"
  const [targetClass, setTargetClass] = useState('');
  const [targetKind, setTargetKind]   = useState('DOCUMENT'); // DOCUMENT | VERTEX
  const [vertexTypes, setVertexTypes] = useState([]);         // existing graph vertex types
  const [edges, setEdges]             = useState([]);         // [{edgeType,direction,targetVertexType,sourceKeyColumn}]
  const [keyTarget, setKeyTarget]     = useState('');
  const [incrementalColumn, setIncrementalColumn] = useState('');
  const [rows, setRows]               = useState([{ ...EMPTY_ROW }]);
  const [enabled, setEnabled]         = useState(true);
  const [saving, setSaving]           = useState(false);
  const [editorErr, setEditorErr]     = useState(null);

  // live validation
  const [validation, setValidation]   = useState(null);       // ValidationReport | null
  const [validating, setValidating]   = useState(false);
  const debounceRef = useRef(null);

  const [deleteTarget, setDeleteTarget] = useState(null);
  const [deleting, setDeleting]         = useState(false);

  const load = useCallback(() => {
    setLoading(true);
    Promise.all([
      axios.get(`${BASE}/datasources`),
      axios.get(`${BASE}/mappings`),
    ])
      .then(([ds, mp]) => {
        setDataSources(ds.data?.dataSources || []);
        setMappings(mp.data?.mappings || []);
        setErr(null);
      })
      .catch(e => setErr(apiErrorMessage(e, t('admin.screens.dataMapping.errLoad', 'Could not load mappings'))))
      .finally(() => setLoading(false));
  }, [t]);
  useEffect(() => { load(); }, [load]);

  const loadSnapshot = useCallback((dsId) => {
    if (!dsId) { setSnapshot(null); return; }
    setSnapLoading(true);
    axios.get(`${BASE}/datasources/${dsId}/metadata`, { params: { refresh: false } })
      .then(r => setSnapshot(r.data))
      .catch(e => {
        setSnapshot(null);
        setEditorErr(apiErrorMessage(e, t('admin.screens.dataMapping.errMetadata', 'Could not load source schema')));
      })
      .finally(() => setSnapLoading(false));
  }, [t]);

  const tables = snapshot?.tables || [];
  const selectedTable = useMemo(() => {
    if (!tableKey) return null;
    const [sch, nm] = tableKey.split('|');
    return tables.find(tb => (tb.schema || '') === sch && tb.name === nm) || null;
  }, [tableKey, tables]);
  const sourceColumns = selectedTable?.columns || [];

  const mappedTargets = rows.map(r => r.target.trim()).filter(Boolean);

  const buildRequest = useCallback(() => ({
    ...(editingId ? { id: editingId } : {}),
    name: name.trim(),
    dataSourceId,
    sourceSchema: selectedTable?.schema || null,
    sourceTable: selectedTable?.name || null,
    targetClass: targetClass.trim(),
    targetKind,
    keyTarget: keyTarget || null,
    incrementalColumn: incrementalColumn || null,
    edges: targetKind === 'VERTEX'
      ? edges
          .filter(e => e.edgeType.trim() && e.targetVertexType.trim() && e.sourceKeyColumn)
          .map(e => ({
            edgeType: e.edgeType.trim(),
            targetVertexType: e.targetVertexType.trim(),
            sourceKeyColumn: e.sourceKeyColumn,
            direction: e.direction || 'OUT',
          }))
      : [],
    mappings: rows
      .filter(r => r.target.trim() || r.sources.length > 0)
      .map(r => ({
        target: r.target.trim(),
        sources: r.sources,
        targetType: r.targetType || null,
        transform: serializeTransform(r),
      })),
    enabled,
  }), [editingId, name, dataSourceId, selectedTable, targetClass, targetKind, keyTarget,
       incrementalColumn, rows, edges, enabled]);

  // Debounced live validation (Req 3.5/3.7): fire 600 ms after the last edit
  // once the mapping is substantial enough for the server to judge.
  useEffect(() => {
    if (!editorOpen) return undefined;
    if (!dataSourceId || !selectedTable || !targetClass.trim()) { setValidation(null); return undefined; }
    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(async () => {
      setValidating(true);
      try {
        const r = await axios.post(`${BASE}/mappings/validate`, buildRequest());
        setValidation(r.data);
      } catch {
        setValidation(null);   // validation itself failing is not a user error
      }
      setValidating(false);
    }, 600);
    return () => clearTimeout(debounceRef.current);
  }, [editorOpen, dataSourceId, selectedTable, targetClass, buildRequest]);

  const resetEditor = () => {
    setEditingId(null); setName(''); setDataSourceId(''); setSnapshot(null);
    setTableKey(''); setTargetClass(''); setTargetKind('DOCUMENT'); setKeyTarget(''); setIncrementalColumn('');
    setRows([{ ...EMPTY_ROW }]); setEdges([]); setEnabled(true);
    setValidation(null); setEditorErr(null);
  };

  const addEdge = () => setEdges(es => [...es, { edgeType: '', direction: 'OUT', targetVertexType: '', sourceKeyColumn: '' }]);
  const setEdge = (i, patch) => setEdges(es => es.map((e, j) => j === i ? { ...e, ...patch } : e));
  const removeEdge = (i) => setEdges(es => es.filter((_, j) => j !== i));

  // Load existing graph vertex types (from okg-ingest) the first time a graph
  // target is selected, so the admin can reuse a type or type a new one.
  useEffect(() => {
    if (!editorOpen || targetKind !== 'VERTEX' || vertexTypes.length > 0) return;
    axios.get('/api/okg-ingest/admin/graph/vertex-types')
      .then(r => setVertexTypes(r.data?.vertexTypes || []))
      .catch(() => setVertexTypes([]));   // picker degrades to free-text entry
  }, [editorOpen, targetKind, vertexTypes.length]);

  const openCreate = () => { resetEditor(); setEditorOpen(true); };

  const openEdit = (m) => {
    resetEditor();
    setEditingId(m.id);
    setName(m.name || '');
    setDataSourceId(m.dataSourceId || '');
    setTableKey(`${m.sourceSchema || ''}|${m.sourceTable || ''}`);
    setTargetClass(m.targetClass || '');
    setTargetKind(m.targetKind || 'DOCUMENT');
    setEdges(Array.isArray(m.edges) ? m.edges.map(e => ({
      edgeType: e.edgeType || '', direction: e.direction || 'OUT',
      targetVertexType: e.targetVertexType || '', sourceKeyColumn: e.sourceKeyColumn || '',
    })) : []);
    setKeyTarget(m.keyTarget || '');
    setIncrementalColumn(m.incrementalColumn || '');
    setEnabled(m.enabled !== false);
    setRows((m.mappings || []).length > 0
      ? m.mappings.map(fm => ({
          target: fm.target || '',
          sources: fm.sources || [],
          targetType: fm.targetType || null,
          ...parseTransform(fm.transform),
        }))
      : [{ ...EMPTY_ROW }]);
    loadSnapshot(m.dataSourceId);
    setEditorOpen(true);
  };

  const onSourceChange = (dsId) => {
    setDataSourceId(dsId); setTableKey(''); setIncrementalColumn('');
    loadSnapshot(dsId);
  };

  const autoMap = () => {
    if (!selectedTable) return;
    setRows(sourceColumns.map(c => ({
      target: camelCase(c.name),
      sources: [c.name],
      targetType: null,
      transform: '', transformArg: '',
    })));
  };

  const setRow = (i, patch) => setRows(rs => rs.map((r, j) => j === i ? { ...r, ...patch } : r));
  const addRow = () => setRows(rs => [...rs, { ...EMPTY_ROW }]);
  const removeRow = (i) => setRows(rs => rs.length > 1 ? rs.filter((_, j) => j !== i) : [{ ...EMPTY_ROW }]);

  const save = async (asNew) => {
    setSaving(true); setEditorErr(null);
    try {
      const req = buildRequest();
      if (editingId && !asNew) await axios.put(`${BASE}/mappings/${editingId}`, req);
      else                     await axios.post(`${BASE}/mappings`, { ...req, id: undefined });
      toast.success(editingId && !asNew
        ? t('admin.screens.dataMapping.updated', 'Mapping updated.')
        : t('admin.screens.dataMapping.created', 'Mapping created.'));
      setEditorOpen(false); resetEditor(); load();
    } catch (e) {
      setEditorErr(apiErrorMessage(e, t('admin.screens.dataMapping.errSave', 'Save failed')));
    }
    setSaving(false);
  };

  const confirmDelete = async () => {
    setDeleting(true);
    try {
      await axios.delete(`${BASE}/mappings/${deleteTarget.id}`);
      toast.success(t('admin.screens.dataMapping.deleted', 'Mapping deleted.'));
      setDeleteTarget(null); load();
    } catch (e) {
      toast.error(apiErrorMessage(e, t('admin.screens.dataMapping.errDelete', 'Delete failed')));
    }
    setDeleting(false);
  };

  const dsName = (id) => dataSources.find(d => d.id === id)?.name || id;

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.dataMapping.scopeResource', 'data mappings')} />
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 0.5, gap: 1 }}>
        <Typography fontWeight={700} fontSize={15}>
          {t('admin.screens.dataMapping.title', 'Data Mapping')}
        </Typography>
        <Box sx={{ flex: 1 }} />
        <IconButton size='small' onClick={load} title={t('admin.common.refresh', 'Refresh')}>
          <RefreshIcon fontSize='small' />
        </IconButton>
        <Button size='small' variant='contained' startIcon={<AddIcon />} onClick={openCreate}>
          {t('admin.screens.dataMapping.add', 'New mapping')}
        </Button>
      </Box>
      <Typography fontSize={13} color='#C3D3F2' mb={2}>
        {t('admin.screens.dataMapping.intro',
          'Map source tables to platform entity classes: pick columns, rename properties, apply transforms. Mappings are validated live as you edit.')}
      </Typography>

      {err && <Alert severity='error' sx={{ mb: 2 }}>{err}</Alert>}

      {/* Saved mappings */}
      {loading ? <CircularProgress size={22} /> : (
        <Box sx={{ mb: 3 }}>
        <CollapsibleSection title={t('admin.screens.dataMapping.sectionTitle', 'Saved mappings')}
          count={mappings.length} detailsSx={{ p: 0 }}>
        <Paper variant='outlined'>
          <Table size='small'>
            <TableHead>
              <TableRow>
                <TableCell>{t('admin.screens.dataMapping.colName', 'Name')}</TableCell>
                <TableCell>{t('admin.screens.dataMapping.colSource', 'Source')}</TableCell>
                <TableCell>{t('admin.screens.dataMapping.colTarget', 'Target class')}</TableCell>
                <TableCell>{t('admin.screens.dataMapping.colEnabled', 'Enabled')}</TableCell>
                <TableCell>{t('admin.screens.dataMapping.colWarnings', 'Warnings')}</TableCell>
                <TableCell align='right'>{t('admin.common.actions', 'Actions')}</TableCell>
              </TableRow>
            </TableHead>
            <TableBody>
              {mappings.length === 0 && (
                <TableRow>
                  <TableCell colSpan={6}>
                    <Typography fontSize={12} color='#7D92C4' py={1}>
                      {t('admin.screens.dataMapping.empty', 'No mappings yet.')}
                    </Typography>
                  </TableCell>
                </TableRow>
              )}
              {mappings.map(m => (
                <TableRow key={m.id} hover>
                  <TableCell>
                    <Typography fontSize={13} fontWeight={600}>{m.name}</Typography>
                    <Typography fontSize={10} color='#7D92C4'>{dsName(m.dataSourceId)}</Typography>
                  </TableCell>
                  <TableCell>
                    <Typography fontSize={12} sx={{ fontFamily: 'monospace' }} color='#C3D3F2'>
                      {m.sourceSchema ? `${m.sourceSchema}.` : ''}{m.sourceTable}
                    </Typography>
                  </TableCell>
                  <TableCell>
                    <Chip size='small' variant='outlined' label={m.targetClass} sx={{ fontSize: 10 }} />
                  </TableCell>
                  <TableCell>
                    <Chip size='small' label={m.enabled !== false
                        ? t('admin.common.enabled', 'Enabled')
                        : t('admin.common.disabled', 'Disabled')}
                      sx={{ fontSize: 10, fontWeight: 700,
                        bgcolor: m.enabled !== false ? 'rgba(61, 220, 151, 0.14)' : '#0F2450',
                        color:   m.enabled !== false ? '#3DDC97' : '#C3D3F2' }} />
                  </TableCell>
                  <TableCell>
                    {(m.warnings || []).length > 0 ? (
                      <Tooltip title={m.warnings.join(' · ')}>
                        <Chip size='small' label={m.warnings.length}
                          sx={{ fontSize: 10, fontWeight: 700, bgcolor: 'rgba(255, 184, 77, 0.16)', color: '#FFB84D' }} />
                      </Tooltip>
                    ) : <Typography fontSize={12} color='#7D92C4'>—</Typography>}
                  </TableCell>
                  <TableCell align='right' sx={{ whiteSpace: 'nowrap' }}>
                    <Tooltip title={t('admin.common.edit', 'Edit')}>
                      <IconButton size='small' onClick={() => openEdit(m)}>
                        <EditIcon sx={{ fontSize: 16 }} />
                      </IconButton>
                    </Tooltip>
                    <Tooltip title={t('admin.common.delete', 'Delete')}>
                      <IconButton size='small' onClick={() => setDeleteTarget(m)}>
                        <DeleteIcon sx={{ fontSize: 16, color: '#FF6B81' }} />
                      </IconButton>
                    </Tooltip>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </Paper>
        </CollapsibleSection>
        </Box>
      )}

      {/* Editor */}
      {editorOpen && (
        <Paper variant='outlined' sx={{ p: 2 }}>
          <Typography fontWeight={700} fontSize={14} mb={2}>
            {editingId
              ? t('admin.screens.dataMapping.editTitle', 'Edit mapping')
              : t('admin.screens.dataMapping.createTitle', 'New mapping')}
          </Typography>

          {editorErr && <Alert severity='error' sx={{ mb: 2 }}>{editorErr}</Alert>}

          {/* Source (left) / target (right) config */}
          <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', mb: 2 }}>
            <Paper variant='outlined' sx={{ p: 1.5, flex: 1, minWidth: 300, bgcolor: '#0F2450' }}>
              <Typography fontSize={12} fontWeight={700} color='#C3D3F2' mb={1.5}>
                {t('admin.screens.dataMapping.sourceSection', 'Source')}
              </Typography>
              <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
                <FormControl size='small' fullWidth>
                  <InputLabel>{t('admin.screens.dataMapping.fDataSource', 'Data source')}</InputLabel>
                  <Select value={dataSourceId} label={t('admin.screens.dataMapping.fDataSource', 'Data source')}
                    onChange={e => onSourceChange(e.target.value)}>
                    {dataSources.map(d => (
                      <MenuItem key={d.id} value={d.id} sx={{ fontSize: 13 }}>{d.name}</MenuItem>
                    ))}
                  </Select>
                </FormControl>
                <FormControl size='small' fullWidth disabled={!snapshot}>
                  <InputLabel>{t('admin.screens.dataMapping.fTable', 'Source table')}</InputLabel>
                  <Select value={tableKey} label={t('admin.screens.dataMapping.fTable', 'Source table')}
                    onChange={e => setTableKey(e.target.value)}>
                    {tables.map(tb => {
                      const k = `${tb.schema || ''}|${tb.name}`;
                      return (
                        <MenuItem key={k} value={k} sx={{ fontSize: 13 }}>
                          {tb.schema ? `${tb.schema}.` : ''}{tb.name}
                        </MenuItem>
                      );
                    })}
                  </Select>
                </FormControl>
                {snapLoading && <CircularProgress size={16} />}
                {selectedTable && (
                  <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                    {sourceColumns.map(c => (
                      <Tooltip key={c.name} title={`${c.typeName}${c.size ? `(${c.size})` : ''}${c.nullable ? '' : ' · NOT NULL'}`}>
                        <Chip size='small' variant='outlined'
                          label={c.primaryKey ? `${c.name} · PK` : c.name}
                          sx={{ fontSize: 10, fontFamily: 'monospace',
                            borderColor: c.primaryKey ? 'rgba(91, 157, 255, 0.42)' : undefined,
                            bgcolor: c.primaryKey ? 'rgba(91, 157, 255, 0.14)' : undefined }} />
                      </Tooltip>
                    ))}
                  </Box>
                )}
              </Box>
            </Paper>

            <Paper variant='outlined' sx={{ p: 1.5, flex: 1, minWidth: 300, bgcolor: '#0F2450' }}>
              <Typography fontSize={12} fontWeight={700} color='#C3D3F2' mb={1.5}>
                {t('admin.screens.dataMapping.targetSection', 'Target')}
              </Typography>
              <Box sx={{ display: 'flex', flexDirection: 'column', gap: 1.5 }}>
                <TextField size='small' label={t('admin.screens.dataMapping.fName', 'Mapping name')}
                  value={name} onChange={e => setName(e.target.value)} fullWidth required />
                <FormControl size='small' fullWidth>
                  <InputLabel>{t('admin.screens.dataMapping.fTargetKind', 'Target kind')}</InputLabel>
                  <Select value={targetKind} label={t('admin.screens.dataMapping.fTargetKind', 'Target kind')}
                    onChange={e => setTargetKind(e.target.value)}>
                    <MenuItem value='DOCUMENT' sx={{ fontSize: 13 }}>
                      {t('admin.screens.dataMapping.kindDocument', 'Document type')}
                    </MenuItem>
                    <MenuItem value='VERTEX' sx={{ fontSize: 13 }}>
                      {t('admin.screens.dataMapping.kindVertex', 'Graph vertex (ontology)')}
                    </MenuItem>
                  </Select>
                </FormControl>
                <TextField size='small'
                  label={targetKind === 'VERTEX'
                    ? t('admin.screens.dataMapping.fVertexType', 'Vertex type')
                    : t('admin.screens.dataMapping.fTargetClass', 'Target class')}
                  value={targetClass} onChange={e => setTargetClass(e.target.value)} fullWidth required
                  placeholder={targetKind === 'VERTEX' ? 'EntClient' : 'Employee'}
                  helperText={targetKind === 'VERTEX'
                    ? t('admin.screens.dataMapping.vertexHint', 'Pick an existing vertex type below, or type a new name to define one.')
                    : undefined} />
                {targetKind === 'VERTEX' && vertexTypes.length > 0 && (
                  <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                    {vertexTypes.map(vt => (
                      <Chip key={vt} size='small'
                        variant={vt === targetClass ? 'filled' : 'outlined'}
                        color={vt === targetClass ? 'primary' : 'default'}
                        label={vt} onClick={() => setTargetClass(vt)}
                        sx={{ fontSize: 10, cursor: 'pointer' }} />
                    ))}
                  </Box>
                )}
                <Box sx={{ display: 'flex', gap: 1.5 }}>
                  <FormControl size='small' fullWidth>
                    <InputLabel>{targetKind === 'VERTEX'
                      ? t('admin.screens.dataMapping.fExternalId', 'External ID property')
                      : t('admin.screens.dataMapping.fKeyTarget', 'Key property')}</InputLabel>
                    <Select value={mappedTargets.includes(keyTarget) ? keyTarget : ''}
                      label={targetKind === 'VERTEX'
                        ? t('admin.screens.dataMapping.fExternalId', 'External ID property')
                        : t('admin.screens.dataMapping.fKeyTarget', 'Key property')}
                      onChange={e => setKeyTarget(e.target.value)}>
                      <MenuItem value='' sx={{ fontSize: 13, color: '#7D92C4' }}>
                        {t('admin.screens.dataMapping.none', '(none)')}
                      </MenuItem>
                      {mappedTargets.map(mt => (
                        <MenuItem key={mt} value={mt} sx={{ fontSize: 13 }}>{mt}</MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                  <FormControl size='small' fullWidth disabled={!selectedTable}>
                    <InputLabel>{t('admin.screens.dataMapping.fIncremental', 'Incremental column')}</InputLabel>
                    <Select value={sourceColumns.some(c => c.name === incrementalColumn) ? incrementalColumn : ''}
                      label={t('admin.screens.dataMapping.fIncremental', 'Incremental column')}
                      onChange={e => setIncrementalColumn(e.target.value)}>
                      <MenuItem value='' sx={{ fontSize: 13, color: '#7D92C4' }}>
                        {t('admin.screens.dataMapping.none', '(none)')}
                      </MenuItem>
                      {sourceColumns.map(c => (
                        <MenuItem key={c.name} value={c.name} sx={{ fontSize: 13 }}>{c.name}</MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                </Box>
                <FormControlLabel
                  control={<Switch size='small' checked={enabled} onChange={e => setEnabled(e.target.checked)} />}
                  label={<Typography fontSize={13}>{t('admin.common.enabled', 'Enabled')}</Typography>} />
              </Box>
            </Paper>
          </Box>

          {/* Relationships (graph vertex targets only) */}
          {targetKind === 'VERTEX' && (
            <Paper variant='outlined' sx={{ p: 1.5, mb: 2, bgcolor: '#0B1533' }}>
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
                <Typography fontSize={12} fontWeight={700} color='#C3D3F2'>
                  {t('admin.screens.dataMapping.edgesSection', 'Relationships')}
                </Typography>
                <Tooltip title={t('admin.screens.dataMapping.edgesHelp',
                  'Link each row’s vertex to another vertex whose external ID is in a source column. OUT = this → other; IN = other → this.')}>
                  <InfoOutlinedIcon sx={{ fontSize: 15, color: '#7D92C4' }} />
                </Tooltip>
                <Box sx={{ flex: 1 }} />
                <Button size='small' startIcon={<AddIcon sx={{ fontSize: 14 }} />} onClick={addEdge}>
                  {t('admin.screens.dataMapping.addEdge', 'Add relationship')}
                </Button>
              </Box>
              {edges.length === 0 && (
                <Typography fontSize={12} color='#7D92C4'>
                  {t('admin.screens.dataMapping.noEdges', 'No relationships. Add one to create edges between vertices.')}
                </Typography>
              )}
              {edges.map((e, i) => (
                <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
                  <TextField size='small' label={t('admin.screens.dataMapping.fEdgeType', 'Relationship type')}
                    value={e.edgeType} onChange={ev => setEdge(i, { edgeType: ev.target.value })}
                    sx={{ minWidth: 180, flex: 1 }} placeholder='HAS_CLIENT' />
                  <FormControl size='small' sx={{ minWidth: 100 }}>
                    <InputLabel>{t('admin.screens.dataMapping.fDirection', 'Direction')}</InputLabel>
                    <Select value={e.direction} label={t('admin.screens.dataMapping.fDirection', 'Direction')}
                      onChange={ev => setEdge(i, { direction: ev.target.value })}>
                      <MenuItem value='OUT' sx={{ fontSize: 13 }}>{t('admin.screens.dataMapping.dirOut', 'OUT (this → other)')}</MenuItem>
                      <MenuItem value='IN' sx={{ fontSize: 13 }}>{t('admin.screens.dataMapping.dirIn', 'IN (other → this)')}</MenuItem>
                    </Select>
                  </FormControl>
                  <TextField size='small' label={t('admin.screens.dataMapping.fEdgeTargetType', 'Other vertex type')}
                    value={e.targetVertexType} onChange={ev => setEdge(i, { targetVertexType: ev.target.value })}
                    sx={{ minWidth: 160, flex: 1 }} placeholder='EntClient'
                    inputProps={{ list: 'edge-vertex-types' }} />
                  <FormControl size='small' sx={{ minWidth: 160, flex: 1 }} disabled={!selectedTable}>
                    <InputLabel>{t('admin.screens.dataMapping.fEdgeKeyCol', 'Related key column')}</InputLabel>
                    <Select value={sourceColumns.some(c => c.name === e.sourceKeyColumn) ? e.sourceKeyColumn : ''}
                      label={t('admin.screens.dataMapping.fEdgeKeyCol', 'Related key column')}
                      onChange={ev => setEdge(i, { sourceKeyColumn: ev.target.value })}>
                      {sourceColumns.map(c => (
                        <MenuItem key={c.name} value={c.name} sx={{ fontSize: 13 }}>{c.name}</MenuItem>
                      ))}
                    </Select>
                  </FormControl>
                  <IconButton size='small' onClick={() => removeEdge(i)}>
                    <DeleteIcon sx={{ fontSize: 16, color: '#FF6B81' }} />
                  </IconButton>
                </Box>
              ))}
              {vertexTypes.length > 0 && (
                <datalist id='edge-vertex-types'>
                  {vertexTypes.map(vt => <option key={vt} value={vt} />)}
                </datalist>
              )}
            </Paper>
          )}

          {/* Field mapping rows */}
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1 }}>
            <Typography fontSize={12} fontWeight={700} color='#C3D3F2'>
              {t('admin.screens.dataMapping.fieldsSection', 'Field mappings')}
            </Typography>
            <Box sx={{ flex: 1 }} />
            <Button size='small' startIcon={<AutoFixHighIcon sx={{ fontSize: 14 }} />}
              onClick={autoMap} disabled={!selectedTable}>
              {t('admin.screens.dataMapping.autoMap', 'Auto-map')}
            </Button>
            <Button size='small' startIcon={<AddIcon sx={{ fontSize: 14 }} />} onClick={addRow}>
              {t('admin.screens.dataMapping.addRow', 'Add row')}
            </Button>
          </Box>

          {rows.map((r, i) => {
            const trDef = TRANSFORMS.find(x => x.value === r.transform);
            return (
              <Box key={i} sx={{ display: 'flex', alignItems: 'center', gap: 1, mb: 1, flexWrap: 'wrap' }}>
                <FormControl size='small' sx={{ minWidth: 200, flex: 1.4 }}>
                  <InputLabel>{t('admin.screens.dataMapping.fSources', 'Source column(s)')}</InputLabel>
                  <Select multiple value={r.sources}
                    onChange={e => setRow(i, { sources: e.target.value })}
                    input={<OutlinedInput label={t('admin.screens.dataMapping.fSources', 'Source column(s)')} />}
                    renderValue={sel => (
                      <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
                        {sel.map(v => <Chip key={v} size='small' label={v} sx={{ fontSize: 10, height: 18 }} />)}
                      </Box>
                    )}>
                    {sourceColumns.map(c => (
                      <MenuItem key={c.name} value={c.name} sx={{ fontSize: 13 }}>{c.name}</MenuItem>
                    ))}
                    {/* Keep values selectable even when the snapshot lacks them (stale mapping). */}
                    {r.sources.filter(s => !sourceColumns.some(c => c.name === s)).map(s => (
                      <MenuItem key={s} value={s} sx={{ fontSize: 13, color: '#FF6B81' }}>{s}</MenuItem>
                    ))}
                  </Select>
                </FormControl>
                <ArrowForwardIcon sx={{ fontSize: 16, color: '#7D92C4' }} />
                <TextField size='small' label={t('admin.screens.dataMapping.fTarget', 'Target property')}
                  value={r.target} onChange={e => setRow(i, { target: e.target.value })}
                  sx={{ minWidth: 150, flex: 1 }} />
                <FormControl size='small' sx={{ minWidth: 110 }}>
                  <InputLabel>{t('admin.screens.dataMapping.fType', 'Type')}</InputLabel>
                  <Select value={r.targetType || ''} label={t('admin.screens.dataMapping.fType', 'Type')}
                    onChange={e => setRow(i, { targetType: e.target.value || null })}>
                    <MenuItem value='' sx={{ fontSize: 13, color: '#7D92C4' }}>
                      {t('admin.screens.dataMapping.auto', '(auto)')}
                    </MenuItem>
                    {TARGET_TYPES.map(tt => <MenuItem key={tt} value={tt} sx={{ fontSize: 13 }}>{tt}</MenuItem>)}
                  </Select>
                </FormControl>
                <FormControl size='small' sx={{ minWidth: 130 }}>
                  <InputLabel>{t('admin.screens.dataMapping.fTransform', 'Transform')}</InputLabel>
                  <Select value={r.transform} label={t('admin.screens.dataMapping.fTransform', 'Transform')}
                    onChange={e => setRow(i, { transform: e.target.value, transformArg: '' })}>
                    <MenuItem value='' sx={{ fontSize: 13, color: '#7D92C4' }}>
                      {t('admin.screens.dataMapping.none', '(none)')}
                    </MenuItem>
                    {TRANSFORMS.map(tr => (
                      <MenuItem key={tr.value} value={tr.value} sx={{ fontSize: 13 }}>{tr.value}</MenuItem>
                    ))}
                  </Select>
                </FormControl>
                {trDef?.arg && (
                  <TextField size='small' label={t('admin.screens.dataMapping.fArg', 'Arg')}
                    value={r.transformArg} onChange={e => setRow(i, { transformArg: e.target.value })}
                    placeholder={trDef.hint} sx={{ width: 130 }} />
                )}
                <IconButton size='small' onClick={() => removeRow(i)}>
                  <DeleteIcon sx={{ fontSize: 16, color: '#7D92C4' }} />
                </IconButton>
              </Box>
            );
          })}

          {/* Live validation results */}
          {validating && (
            <Typography fontSize={11} color='#7D92C4' mb={1}>
              {t('admin.screens.dataMapping.validating', 'Validating…')}
            </Typography>
          )}
          {validation && (validation.errors || []).length > 0 && (
            <Alert severity='error' sx={{ mb: 1 }}>
              {validation.errors.map((e2, i) => <div key={i}>{e2}</div>)}
            </Alert>
          )}
          {validation && (validation.warnings || []).length > 0 && (
            <Alert severity='warning' sx={{ mb: 1 }}>
              {validation.warnings.map((w, i) => <div key={i}>{w}</div>)}
            </Alert>
          )}
          {validation && validation.valid && (validation.warnings || []).length === 0 && (
            <Alert severity='success' sx={{ mb: 1 }}>
              {t('admin.screens.dataMapping.valid', 'Mapping is valid.')}
            </Alert>
          )}

          <Divider sx={{ my: 2 }} />
          <Box sx={{ display: 'flex', gap: 1 }}>
            <Button size='small' variant='contained' onClick={() => save(false)}
              disabled={saving || !name.trim() || !dataSourceId || !selectedTable || !targetClass.trim()}>
              {saving ? <CircularProgress size={14} /> : t('admin.common.save', 'Save')}
            </Button>
            {editingId && (
              <Button size='small' onClick={() => save(true)} disabled={saving}>
                {t('admin.screens.dataMapping.saveAsNew', 'Save as new')}
              </Button>
            )}
            <Button size='small' onClick={() => { setEditorOpen(false); resetEditor(); }} disabled={saving}>
              {t('admin.common.cancel', 'Cancel')}
            </Button>
          </Box>
        </Paper>
      )}

      {/* Delete confirm */}
      <Dialog open={!!deleteTarget} onClose={() => !deleting && setDeleteTarget(null)} maxWidth='xs' fullWidth>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>
          {t('admin.screens.dataMapping.deleteTitle', 'Delete mapping?')}
        </DialogTitle>
        <DialogContent>
          <Typography fontSize={13}>
            {t('admin.screens.dataMapping.deleteBody',
              '"{{name}}" will be removed. Jobs that use it will stop working.',
              { name: deleteTarget?.name })}
          </Typography>
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button size='small' onClick={() => setDeleteTarget(null)} disabled={deleting}>
            {t('admin.common.cancel', 'Cancel')}
          </Button>
          <Button size='small' color='error' variant='contained' onClick={confirmDelete} disabled={deleting}>
            {deleting ? <CircularProgress size={14} /> : t('admin.common.delete', 'Delete')}
          </Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
