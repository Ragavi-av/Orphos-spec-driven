import { useCallback, useEffect, useState } from 'react';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress,
  TextField, Autocomplete, MenuItem, Select, FormControl, InputLabel,
  Checkbox, FormControlLabel, Divider,
} from '@mui/material';
import RefreshIcon    from '@mui/icons-material/Refresh';
import RestartAltIcon from '@mui/icons-material/RestartAlt';
import { useTranslation } from 'react-i18next';
import axios from 'eip-shell-context/axiosAuth';
import { useToast } from 'eip-shell-context/ToastProvider';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';

const LANGUAGES = [
  { value: 'auto', labelKey: 'langAuto',    labelEn: 'Auto (match question)' },
  { value: 'en',   labelKey: 'langEnglish', labelEn: 'English' },
  { value: 'ta',   labelKey: 'langTamil',   labelEn: 'Tamil' },
  { value: 'hi',   labelKey: 'langHindi',   labelEn: 'Hindi' },
  { value: 'es',   labelKey: 'langSpanish', labelEn: 'Spanish' },
  { value: 'fr',   labelKey: 'langFrench',  labelEn: 'French' },
  { value: 'de',   labelKey: 'langGerman',  labelEn: 'German' },
  { value: 'ar',   labelKey: 'langArabic',  labelEn: 'Arabic' },
  { value: 'zh',   labelKey: 'langChinese', labelEn: 'Chinese' },
];

const MATCHING = [
  { value: 'SEMANTIC',     labelKey: 'matchSemantic',    labelEn: 'Semantic (default)' },
  { value: 'EXACT_PHRASE', labelKey: 'matchExactPhrase', labelEn: 'Exact phrase' },
  { value: 'ALL_KEYWORDS', labelKey: 'matchAllKeywords', labelEn: 'All keywords' },
];

const RETRIEVAL = [
  { value: 'SERVER_DEFAULT', labelKey: 'retrievalServerDefault', labelEn: 'Server default (hybrid)' },
  { value: 'HYBRID',         labelKey: 'retrievalHybrid',        labelEn: 'Hybrid (vector + BM25, RRF)' },
  { value: 'DENSE',          labelKey: 'retrievalDense',         labelEn: 'Dense (vector only)' },
];

const MODEL_SUGGESTIONS = ['server-default', 'claude-opus-4-8', 'claude-sonnet-4-6', 'claude-haiku-4-5'];
const DOCTYPE_SUGGESTIONS = ['Policy', 'Report', 'Contract', 'Invoice', 'Manual', 'Form', 'Memo', 'Circular'];

function Section({ title, children }) {
  return (
    <Paper variant='outlined' sx={{ p: 2.5, borderRadius: 2, mb: 2 }}>
      <Typography fontSize={11} fontWeight={700} color='#7D92C4' letterSpacing='0.06em' mb={2}>
        {title}
      </Typography>
      {children}
    </Paper>
  );
}

/**
 * Tenant admin / ingestion-admin UI for document search settings. Saved per
 * tenant (server-side) and applied to every search in the tenant; individual
 * requests may still override any field.
 */
export default function SearchSettingsTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const [full, setFull]       = useState(null);   // full DocSettings (keep ingestion section intact on save)
  const [s, setS]             = useState(null);   // editable search section
  const [loading, setLoading] = useState(true);
  const [saving, setSaving]   = useState(false);
  const [error, setError]     = useState(null);
  const [info, setInfo]       = useState(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null); setInfo(null);
    try {
      const r = await axios.get('/api/doc-ingest/admin/settings');
      setFull(r.data);
      setS(r.data?.search || {});
    } catch (e) {
      setError(e?.response?.data?.message || e?.message || t('admin.screens.searchSettings.loadError', 'Failed to load search settings.'));
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const set = (k, v) => { setS(prev => ({ ...prev, [k]: v })); setInfo(null); };

  const save = async () => {
    setSaving(true); setError(null); setInfo(null);
    try {
      const r = await axios.put('/api/doc-ingest/admin/settings', { ...full, search: s });
      setFull(r.data);
      setS(r.data?.search || {});
      setInfo(t('admin.screens.searchSettings.saveInfo', 'Search settings saved. They apply to every search in this tenant.'));
      toast.success(t('admin.screens.searchSettings.saveSuccess', 'Search settings saved successfully.'));
    } catch (e) {
      setError(e?.response?.data?.message || e?.message || t('admin.screens.searchSettings.saveError', 'Save failed.'));
    }
    setSaving(false);
  };

  const reset = async () => {
    if (!window.confirm(t('admin.screens.searchSettings.resetConfirm', 'Reset ingestion + search settings to platform defaults?'))) return;
    setSaving(true); setError(null); setInfo(null);
    try {
      await axios.delete('/api/doc-ingest/admin/settings');
      setInfo(t('admin.screens.searchSettings.resetInfo', 'Settings reset to platform defaults.'));
      await load();
      toast.success(t('admin.screens.searchSettings.resetSuccess', 'Settings reset to platform defaults successfully.'));
    } catch (e) {
      setError(e?.response?.data?.message || e?.message || t('admin.screens.searchSettings.resetError', 'Reset failed.'));
    }
    setSaving(false);
  };

  if (loading) return <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={24} /></Box>;

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.searchSettings.scopeResource', 'search settings')} />

      <Box sx={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', mb: 2 }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>{t('admin.screens.searchSettings.title', 'Search Settings')}</Typography>
          <Typography fontSize={12} color='#7D92C4'>
            {t('admin.screens.searchSettings.subtitle', 'Defaults for every document search in this tenant. Saved server-side.')}
          </Typography>
        </Box>
        <Button size='small' startIcon={<RefreshIcon />} onClick={load} disabled={saving}
          sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.searchSettings.refresh', 'Refresh')}</Button>
      </Box>

      {error && <Alert severity='error'   sx={{ mb: 2, fontSize: 13 }}>{error}</Alert>}
      {info  && <Alert severity='success' sx={{ mb: 2, fontSize: 13 }}>{info}</Alert>}

      {/* ── Model & language ── */}
      <Section title={t('admin.screens.searchSettings.sectionModelLanguage', 'MODEL & LANGUAGE')}>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
          <Autocomplete freeSolo options={MODEL_SUGGESTIONS}
            value={s?.model || 'server-default'} onInputChange={(_, v) => set('model', v)}
            sx={{ flex: 1, minWidth: 240 }}
            renderInput={p => <TextField {...p} size='small' label={t('admin.screens.searchSettings.model', 'Model')}
              helperText={t('admin.screens.searchSettings.modelHelper', 'Server default uses the tenant’s configured AI provider')} />} />
          <FormControl size='small' sx={{ flex: 1, minWidth: 240 }}>
            <InputLabel>{t('admin.screens.searchSettings.answerLanguage', 'Answer language')}</InputLabel>
            <Select label={t('admin.screens.searchSettings.answerLanguage', 'Answer language')} value={s?.answerLanguage || 'auto'}
              onChange={e => set('answerLanguage', e.target.value)}>
              {LANGUAGES.map(l => <MenuItem key={l.value} value={l.value} sx={{ fontSize: 13 }}>{t(`admin.screens.searchSettings.${l.labelKey}`, l.labelEn)}</MenuItem>)}
            </Select>
          </FormControl>
        </Box>
      </Section>

      {/* ── Matching criteria ── */}
      <Section title={t('admin.screens.searchSettings.sectionMatchingCriteria', 'MATCHING CRITERIA')}>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', mb: 1 }}>
          <FormControl size='small' sx={{ flex: 1, minWidth: 240 }}>
            <InputLabel>{t('admin.screens.searchSettings.passageMatching', 'Passage matching')}</InputLabel>
            <Select label={t('admin.screens.searchSettings.passageMatching', 'Passage matching')} value={s?.passageMatching || 'SEMANTIC'}
              onChange={e => set('passageMatching', e.target.value)}>
              {MATCHING.map(m => <MenuItem key={m.value} value={m.value} sx={{ fontSize: 13 }}>{t(`admin.screens.searchSettings.${m.labelKey}`, m.labelEn)}</MenuItem>)}
            </Select>
          </FormControl>
          <Box sx={{ flex: 1, minWidth: 240 }} />
        </Box>
        <FormControlLabel
          control={<Checkbox size='small' checked={!!s?.caseSensitive}
            onChange={e => set('caseSensitive', e.target.checked)} />}
          label={<Typography fontSize={13}>{t('admin.screens.searchSettings.caseSensitive', 'Case-sensitive matching')}</Typography>} />
        <FormControlLabel
          control={<Checkbox size='small' checked={s?.ignoreWhitespace !== false}
            onChange={e => set('ignoreWhitespace', e.target.checked)} />}
          label={<Typography fontSize={13}>{t('admin.screens.searchSettings.ignoreWhitespace', 'Ignore extra spaces / line wraps')}</Typography>} />
        <Typography fontSize={11.5} color='#7D92C4' mt={1}>
          {t('admin.screens.searchSettings.matchingHelper', 'Exact phrase / all keywords filter the retrieved passages — useful for circular numbers and scheme names. Semantic leaves ranking to the vector search.')}
        </Typography>
      </Section>

      {/* ── Retrieval & ranking ── */}
      <Section title={t('admin.screens.searchSettings.sectionRetrievalRanking', 'RETRIEVAL & RANKING')}>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap', mb: 1 }}>
          <FormControl size='small' sx={{ flex: 1, minWidth: 200 }}>
            <InputLabel>{t('admin.screens.searchSettings.retrievalMode', 'Retrieval mode')}</InputLabel>
            <Select label={t('admin.screens.searchSettings.retrievalMode', 'Retrieval mode')} value={s?.retrievalMode || 'SERVER_DEFAULT'}
              onChange={e => set('retrievalMode', e.target.value)}>
              {RETRIEVAL.map(r => <MenuItem key={r.value} value={r.value} sx={{ fontSize: 13 }}>{t(`admin.screens.searchSettings.${r.labelKey}`, r.labelEn)}</MenuItem>)}
            </Select>
          </FormControl>
          <TextField size='small' type='number' label={t('admin.screens.searchSettings.retrievalTopK', 'Retrieval top-K (1–50)')}
            sx={{ flex: 1, minWidth: 160 }}
            value={s?.retrievalTopK ?? 20}
            onChange={e => set('retrievalTopK', clampInt(e.target.value, 1, 50, 20))}
            inputProps={{ min: 1, max: 50 }} />
          <TextField size='small' type='number' label={t('admin.screens.searchSettings.maxCitations', 'Max citations')}
            sx={{ flex: 1, minWidth: 160 }}
            value={s?.maxCitations ?? 5}
            onChange={e => set('maxCitations', clampInt(e.target.value, 1, 20, 5))}
            inputProps={{ min: 1, max: 20 }} />
        </Box>
        <FormControlLabel
          control={<Checkbox size='small' checked={s?.crossEncoderRerank !== false}
            onChange={e => set('crossEncoderRerank', e.target.checked)} />}
          label={<Typography fontSize={13}>{t('admin.screens.searchSettings.crossEncoderRerank', 'Cross-encoder re-ranking (more precise, slower)')}</Typography>} />
        <Typography fontSize={11.5} color='#7D92C4' mt={1}>
          {t('admin.screens.searchSettings.retrievalHelper', 'Hybrid fuses vector similarity with BM25 keyword search (RRF) — best for circular numbers, scheme names and clause references. Dense is the fallback. Re-ranking activates when a cross-encoder endpoint is configured for the service.')}
        </Typography>
      </Section>

      {/* ── Filters ── */}
      <Section title='FILTERS'>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
          <Autocomplete freeSolo options={DOCTYPE_SUGGESTIONS}
            value={s?.documentTypeFilter || ''} onInputChange={(_, v) => set('documentTypeFilter', v)}
            sx={{ flex: 1, minWidth: 240 }}
            renderInput={p => <TextField {...p} size='small' label='Document type'
              placeholder='Any' helperText='Blank = any type' />} />
          <TextField size='small' type='date' label='Effective after'
            sx={{ flex: 1, minWidth: 240 }}
            InputLabelProps={{ shrink: true }}
            value={s?.effectiveAfter || ''}
            onChange={e => set('effectiveAfter', e.target.value)} />
        </Box>
      </Section>

      <Divider sx={{ my: 1.5 }} />
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 2 }}>
        <Button size='small' startIcon={<RestartAltIcon />} onClick={reset} disabled={saving}
          sx={{ textTransform: 'none', fontSize: 12, color: '#FF6B81' }}>Reset to defaults</Button>
        <Typography fontSize={12} color='#7D92C4' sx={{ flex: 1 }}>
          Settings apply to every search in this tenant and are saved on the server.
        </Typography>
        <Button onClick={save} variant='contained' size='small' disabled={saving}
          sx={{ textTransform: 'none', fontSize: 13 }}>
          {saving ? 'Saving…' : 'Save settings'}
        </Button>
      </Box>
    </Box>
  );
}

function clampInt(raw, lo, hi, fallback) {
  const n = parseInt(raw, 10);
  if (Number.isNaN(n)) return fallback;
  return Math.max(lo, Math.min(hi, n));
}
