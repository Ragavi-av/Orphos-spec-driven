import { useState } from 'react';
import { Box, Typography, Tabs, Tab, Button, Stack, Snackbar, Alert } from '@mui/material';
import AddIcon from '@mui/icons-material/Add';
import UploadFileIcon from '@mui/icons-material/UploadFile';
import SyncIcon from '@mui/icons-material/Sync';
import { useTranslation } from 'react-i18next';
import LogView from './LogView';
import SearchView from './SearchView';
import AnalyticsView from './AnalyticsView';
import DetailDrawer from './DetailDrawer';
import { NewDecisionDialog, ImportDialog } from './dialogs';
import { syncNow } from './api';

/**
 * Decision Log RAG — root UI. Three internal tabs (Decisions / Search /
 * Analytics), a detail drawer shared by all of them, and the create / import
 * entry points. Backed by decision-log-service (/api/decisions).
 */
export default function DecisionLog() {
  const { t } = useTranslation();
  const [tab, setTab] = useState(0);
  const [selectedId, setSelectedId] = useState(null);
  const [showNew, setShowNew] = useState(false);
  const [showImport, setShowImport] = useState(false);
  const [reloadKey, setReloadKey] = useState(0);
  const [toast, setToast] = useState(null);

  const reload = () => setReloadKey(k => k + 1);

  const handleSync = async () => {
    try {
      const stats = await syncNow();
      setToast({
        severity: 'success',
        msg: `Sync: ${stats.decisionsDetected ?? 0} detected, ${stats.rowsMigrated ?? 0} migrated`,
      });
      reload();
    } catch {
      setToast({ severity: 'error', msg: t('decisionLog.loadError') });
    }
  };

  return (
    <Box p={3}>
      <Stack direction="row" alignItems="center" justifyContent="space-between" flexWrap="wrap" gap={1}>
        <Typography variant="h5">{t('decisionLog.title')}</Typography>
        <Stack direction="row" spacing={1}>
          <Button size="small" startIcon={<SyncIcon />} onClick={handleSync}>
            {t('decisionLog.actions.syncNow')}
          </Button>
          <Button size="small" startIcon={<UploadFileIcon />} onClick={() => setShowImport(true)}>
            {t('decisionLog.actions.importDoc')}
          </Button>
          <Button size="small" variant="contained" startIcon={<AddIcon />} onClick={() => setShowNew(true)}>
            {t('decisionLog.actions.newDecision')}
          </Button>
        </Stack>
      </Stack>

      <Tabs value={tab} onChange={(_, v) => setTab(v)} sx={{ mb: 2, mt: 1 }}>
        <Tab label={t('decisionLog.tabs.log')} />
        <Tab label={t('decisionLog.tabs.search')} />
        <Tab label={t('decisionLog.tabs.analytics')} />
      </Tabs>

      {tab === 0 && <LogView key={`log-${reloadKey}`} onSelect={setSelectedId} />}
      {tab === 1 && <SearchView key={`search-${reloadKey}`} onSelect={setSelectedId} />}
      {tab === 2 && <AnalyticsView key={`analytics-${reloadKey}`} />}

      <DetailDrawer
        decisionId={selectedId}
        onClose={() => setSelectedId(null)}
        onChanged={reload}
      />
      <NewDecisionDialog
        open={showNew}
        onClose={() => setShowNew(false)}
        onCreated={(d) => { setShowNew(false); reload(); if (d?.id) setSelectedId(d.id); }}
      />
      <ImportDialog
        open={showImport}
        onClose={() => setShowImport(false)}
        onImported={(d) => { setShowImport(false); reload(); if (d?.id) setSelectedId(d.id); }}
      />
      <Snackbar open={!!toast} autoHideDuration={5000} onClose={() => setToast(null)}>
        {toast ? <Alert severity={toast.severity} onClose={() => setToast(null)}>{toast.msg}</Alert> : <span />}
      </Snackbar>
    </Box>
  );
}
