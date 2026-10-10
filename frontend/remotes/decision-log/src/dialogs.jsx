import { useState } from 'react';
import {
  Dialog, DialogTitle, DialogContent, DialogActions, Button, TextField, MenuItem,
  Stack, Alert, Typography, Tab, Tabs, Box, Chip,
} from '@mui/material';
import { useTranslation } from 'react-i18next';
import { createDecision, parseDocumentText, parseDocumentFile, importDocumentText, importDocumentFile } from './api';

const DOMAINS = ['IT', 'FINANCE', 'HR', 'OPERATIONS', 'SALES', 'LEGAL', 'OTHER'];
const PRIORITIES = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'];
const CLASSIFICATIONS = ['STRATEGIC', 'OPERATIONAL', 'TACTICAL', 'POLICY', 'FINANCIAL', 'PERSONNEL'];

/** Manual decision entry — the structured leg of Req 1. */
export function NewDecisionDialog({ open, onClose, onCreated }) {
  const { t } = useTranslation();
  const [form, setForm] = useState({ domain: 'IT', priorityLevel: 'MEDIUM', status: 'PENDING' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const set = (key) => (e) => setForm(f => ({ ...f, [key]: e.target.value }));

  const submit = async () => {
    if (!form.title?.trim()) return;
    setBusy(true);
    try {
      const created = await createDecision(form);
      setForm({ domain: 'IT', priorityLevel: 'MEDIUM', status: 'PENDING' });
      onCreated(created);
    } catch {
      setError(t('decisionLog.loadError'));
    }
    setBusy(false);
  };

  return (
    <Dialog open={open} onClose={onClose} maxWidth="sm" fullWidth>
      <DialogTitle>{t('decisionLog.actions.newDecision')}</DialogTitle>
      <DialogContent>
        {error && <Alert severity="warning" sx={{ mb: 1 }}>{error}</Alert>}
        <Stack spacing={2} mt={1}>
          <TextField label={t('decisionLog.fields.title')} value={form.title || ''}
            onChange={set('title')} required fullWidth size="small" />
          <TextField label={t('decisionLog.fields.description')} value={form.description || ''}
            onChange={set('description')} fullWidth size="small" multiline minRows={2} />
          <TextField label={t('decisionLog.fields.rationale')} value={form.rationale || ''}
            onChange={set('rationale')} fullWidth size="small" multiline minRows={2} />
          <Stack direction="row" spacing={2}>
            <TextField select label={t('decisionLog.fields.domain')} value={form.domain}
              onChange={set('domain')} size="small" sx={{ flex: 1 }}>
              {DOMAINS.map(x => <MenuItem key={x} value={x}>{x}</MenuItem>)}
            </TextField>
            <TextField label={t('decisionLog.fields.area')} value={form.decisionArea || ''}
              onChange={set('decisionArea')} size="small" sx={{ flex: 1 }} />
          </Stack>
          <Stack direction="row" spacing={2}>
            <TextField select label={t('decisionLog.fields.priority')} value={form.priorityLevel}
              onChange={set('priorityLevel')} size="small" sx={{ flex: 1 }}>
              {PRIORITIES.map(x => <MenuItem key={x} value={x}>{x}</MenuItem>)}
            </TextField>
            <TextField select label={t('decisionLog.fields.classification')}
              value={form.classificationType || ''} onChange={set('classificationType')}
              size="small" sx={{ flex: 1 }}>
              {CLASSIFICATIONS.map(x => <MenuItem key={x} value={x}>{x}</MenuItem>)}
            </TextField>
          </Stack>
          <Stack direction="row" spacing={2}>
            <TextField label={t('decisionLog.fields.decidedBy')} value={form.decidedBy || ''}
              onChange={set('decidedBy')} size="small" sx={{ flex: 1 }} />
            <TextField type="date" label={t('decisionLog.fields.dueDate')} value={form.dueDate || ''}
              onChange={set('dueDate')} size="small" sx={{ flex: 1 }} InputLabelProps={{ shrink: true }} />
          </Stack>
        </Stack>
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('decisionLog.actions.cancel')}</Button>
        <Button variant="contained" disabled={busy || !form.title?.trim()} onClick={submit}>
          {t('decisionLog.actions.save')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}

/**
 * Document import (Req 12): paste or upload → parse preview (with per-line
 * warnings/errors) → import as a decision.
 */
export function ImportDialog({ open, onClose, onImported }) {
  const { t } = useTranslation();
  const [tab, setTab] = useState(0);
  const [content, setContent] = useState('');
  const [file, setFile] = useState(null);
  const [preview, setPreview] = useState(null);
  const [issues, setIssues] = useState([]);
  const [fatal, setFatal] = useState(false);
  const [busy, setBusy] = useState(false);

  const reset = () => { setPreview(null); setIssues([]); setFatal(false); };

  const handleErrors = (e) => {
    const body = e?.response?.data;
    setPreview(null);
    setFatal(true);
    setIssues(Array.isArray(body?.issues) ? body.issues : [{ line: 0, message: t('decisionLog.loadError') }]);
  };

  const parse = async () => {
    setBusy(true); reset();
    try {
      const result = tab === 0 ? await parseDocumentText(content) : await parseDocumentFile(file);
      setPreview(result.decision);
      setIssues(result.warnings || []);
    } catch (e) { handleErrors(e); }
    setBusy(false);
  };

  const doImport = async () => {
    setBusy(true);
    try {
      const result = tab === 0 ? await importDocumentText(content) : await importDocumentFile(file);
      setContent(''); setFile(null); reset();
      onImported(result.decision);
    } catch (e) { handleErrors(e); }
    setBusy(false);
  };

  const ready = tab === 0 ? content.trim().length > 0 : !!file;

  return (
    <Dialog open={open} onClose={onClose} maxWidth="md" fullWidth>
      <DialogTitle>{t('decisionLog.import.title')}</DialogTitle>
      <DialogContent>
        <Typography variant="body2" color="text.secondary" mb={1}>{t('decisionLog.import.hint')}</Typography>
        <Tabs value={tab} onChange={(_, v) => { setTab(v); reset(); }} sx={{ mb: 1 }}>
          <Tab label={t('decisionLog.import.paste')} />
          <Tab label={t('decisionLog.import.upload')} />
        </Tabs>
        {tab === 0 ? (
          <TextField fullWidth multiline minRows={8} maxRows={16} value={content}
            onChange={e => { setContent(e.target.value); reset(); }}
            placeholder={'# Decision: …\n\n- Status: PENDING\n- Domain: IT\n\n## Description\n…'} />
        ) : (
          <Button variant="outlined" component="label">
            {file ? file.name : t('decisionLog.import.upload')}
            <input hidden type="file" accept=".pdf,.doc,.docx,.txt,.md,.markdown,.json"
              onChange={e => { setFile(e.target.files?.[0] || null); reset(); }} />
          </Button>
        )}

        {issues.length > 0 && (
          <Alert severity={fatal ? 'error' : 'warning'} sx={{ mt: 2 }}>
            <Typography variant="subtitle2">
              {fatal ? t('decisionLog.import.errors') : t('decisionLog.import.warnings')}
            </Typography>
            {issues.map((i, idx) => (
              <Typography key={idx} variant="body2">
                {i.line > 0 ? `${t('decisionLog.import.line', { n: i.line })}: ` : ''}{i.message}
              </Typography>
            ))}
          </Alert>
        )}

        {preview && (
          <Box mt={2} p={2} sx={{ border: 1, borderColor: 'divider', borderRadius: 1 }}>
            <Typography variant="subtitle2" gutterBottom>{t('decisionLog.import.preview')}</Typography>
            <Typography variant="body1" fontWeight={500}>{preview.title}</Typography>
            <Stack direction="row" spacing={1} my={1} flexWrap="wrap" useFlexGap>
              {[preview.status, preview.domain, preview.priorityLevel, preview.classificationType]
                .filter(Boolean).map(x => <Chip key={x} size="small" label={x} />)}
            </Stack>
            {preview.description && (
              <Typography variant="body2" sx={{ whiteSpace: 'pre-wrap' }}>{preview.description}</Typography>
            )}
          </Box>
        )}
      </DialogContent>
      <DialogActions>
        <Button onClick={onClose}>{t('decisionLog.actions.cancel')}</Button>
        <Button disabled={busy || !ready} onClick={parse}>{t('decisionLog.actions.parse')}</Button>
        <Button variant="contained" disabled={busy || !ready || fatal} onClick={doImport}>
          {t('decisionLog.actions.import')}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
