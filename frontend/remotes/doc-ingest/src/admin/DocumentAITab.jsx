import { useCallback, useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Box, Paper, Typography, Switch, Alert, CircularProgress } from '@mui/material';
import axios from 'eip-shell-context/axiosAuth';
import { useToast } from 'eip-shell-context/ToastProvider';
import PromptKindsEditor from 'eip-shell-context/PromptKindsEditor';

// The document AI prompt kind owned by this tab. It lives in the same
// versioned nl-query prompt store as the AI Co-pilot prompts and is consumed
// by doc-ingest-service's DocumentAiEnricher (via nl-query's internal prompt
// endpoint) when AI metadata enrichment is enabled below.
const KINDS = [
  { kind: 'doc-processing', label: 'Document Metadata Enrichment',
    blurb: 'Classifies a freshly ingested document and proposes metadata for the fields the uploader left blank '
      + '(type, category, department, title). Keep the JSON schema and the {{FILE_NAME}} and {{EXCERPT}} placeholders.' },
];

/**
 * Administration → Document Ingestion → Document AI.
 *
 * Two parts: the per-tenant opt-in switch for AI metadata enrichment during
 * ingestion (stored in DocSettings.ingestion.aiMetadata), and the shared
 * prompt editor scoped to the doc-processing kind. Which provider/model runs
 * the enrichment is assigned in Data & AI → AI Providers (Document AI
 * Enrichment).
 */
export default function DocumentAITab() {
  const { t } = useTranslation();
  const toast = useToast();

  const [settings, setSettings] = useState(null);  // full DocSettings blob
  const [loading, setLoading]   = useState(true);
  const [saving, setSaving]     = useState(false);
  const [err, setErr]           = useState(null);

  const load = useCallback(async () => {
    setLoading(true); setErr(null);
    try {
      const r = await axios.get('/api/doc-ingest/admin/settings');
      setSettings(r.data || {});
    } catch (e) {
      setErr(e?.response?.data?.message || e?.message
        || t('admin.screens.docAi.loadError', 'Failed to load ingestion settings.'));
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const enabled = !!settings?.ingestion?.aiMetadata;

  const toggle = async (next) => {
    setSaving(true); setErr(null);
    try {
      // PUT replaces the whole settings blob, so carry every loaded field and
      // flip only the flag — the other tabs (Ingestion Defaults, Search
      // Settings) own the rest of it.
      const r = await axios.put('/api/doc-ingest/admin/settings', {
        ...settings,
        ingestion: { ...(settings?.ingestion || {}), aiMetadata: next },
      });
      setSettings(r.data || settings);
      toast.success(next
        ? t('admin.screens.docAi.enabledToast', 'AI metadata enrichment enabled for new uploads.')
        : t('admin.screens.docAi.disabledToast', 'AI metadata enrichment disabled.'));
    } catch (e) {
      setErr(e?.response?.data?.message || e?.message
        || t('admin.screens.docAi.saveError', 'Save failed.'));
    }
    setSaving(false);
  };

  return (
    <Box>
      <Paper variant='outlined' sx={{ borderRadius: 2, p: 2, mb: 2.5, display: 'flex',
        alignItems: 'center', justifyContent: 'space-between', gap: 2 }}>
        <Box>
          <Typography fontWeight={700} fontSize={14} color='#FFFFFF'>
            {t('admin.screens.docAi.toggleTitle', 'AI metadata enrichment')}
          </Typography>
          <Typography fontSize={12} color='#C3D3F2'>
            {t('admin.screens.docAi.toggleBlurb',
              'When enabled, newly ingested documents are classified by the tenant\'s AI provider and any blank '
              + 'type, category, department or title fields are filled automatically. Best-effort — ingestion '
              + 'never fails because of it, and fields the uploader set are never overwritten.')}
          </Typography>
        </Box>
        {loading
          ? <CircularProgress size={20} />
          : <Switch checked={enabled} disabled={saving} onChange={e => toggle(e.target.checked)} />}
      </Paper>

      {err && <Alert severity='error' sx={{ mb: 2, fontSize: 13 }}>{err}</Alert>}

      <PromptKindsEditor
        kinds={KINDS}
        scopeResource={t('admin.screens.docAi.scopeResource', 'document AI prompts')}
        title={t('admin.screens.docAi.title', 'Document AI Prompts')}
        intro={t('admin.screens.docAi.intro',
          'Edit the prompt used to classify ingested documents. Saving creates a new version; you can roll back '
          + 'from the prompt\'s history. Changes apply without a restart. Pick the provider/model under '
          + 'Data & AI → AI Providers.')}
        placeholderHint={t('admin.screens.docAi.placeholderHint',
          'Placeholders {{FILE_NAME}} and {{EXCERPT}} are substituted at runtime — keep them in the prompt.')}
      />
    </Box>
  );
}
