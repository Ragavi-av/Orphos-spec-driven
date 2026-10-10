import { useState, useEffect, useCallback } from 'react';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress,
  Table, TableHead, TableRow, TableCell, TableBody,
  Chip, Tooltip, Dialog, DialogTitle, DialogContent, DialogActions,
} from '@mui/material';
import RefreshIcon    from '@mui/icons-material/Refresh';
import AutorenewIcon  from '@mui/icons-material/Autorenew';
import DeleteIcon     from '@mui/icons-material/Delete';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import ErrorIcon      from '@mui/icons-material/Error';
import HourglassIcon  from '@mui/icons-material/HourglassEmpty';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import CollapsibleSection from 'eip-shell-context/CollapsibleSection';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { useToast } from 'eip-shell-context/ToastProvider';

const STATUS_CHIP = {
  COMPLETED:  { labelKey: 'statusCompleted',  labelEn: 'Completed',  bg: 'rgba(61, 220, 151, 0.18)', color: '#3DDC97' },
  PROCESSING: { labelKey: 'statusProcessing', labelEn: 'Processing', bg: 'rgba(91, 157, 255, 0.18)', color: '#8DBCFF' },
  ACCEPTED:   { labelKey: 'statusQueued',     labelEn: 'Queued',     bg: 'rgba(255, 184, 77, 0.18)', color: '#FFB84D' },
  SKIPPED:    { labelKey: 'statusSkipped',    labelEn: 'Skipped',    bg: '#243A72', color: '#C3D3F2' },
  FAILED:     { labelKey: 'statusFailed',     labelEn: 'Failed',     bg: 'rgba(255, 107, 129, 0.18)', color: '#FF6B81' },
};

function relTime(ts) {
  if (!ts) return '—';
  const d = Date.now() - new Date(ts).getTime();
  if (d < 60000)  return `${Math.floor(d/1000)}s ago`;
  if (d < 3600000) return `${Math.floor(d/60000)}m ago`;
  if (d < 86400000) return `${Math.floor(d/3600000)}h ago`;
  return new Date(ts).toLocaleDateString();
}

function fmtBytes(b) {
  if (b == null) return '—';
  if (b < 1024) return `${b} B`;
  if (b < 1048576) return `${(b/1024).toFixed(1)} KB`;
  return `${(b/1048576).toFixed(1)} MB`;
}

export default function DocIngestListTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const [docs,    setDocs]    = useState([]);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState(null);
  const [deleting, setDeleting] = useState(null);   // documentId being confirmed
  const [deleteErr, setDeleteErr] = useState(null);
  const [reindexing, setReindexing] = useState(false);
  const [notice, setNotice] = useState(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const r = await axios.get('/api/doc-ingest/documents');
      setDocs(Array.isArray(r.data) ? r.data : []);
    } catch (e) {
      setError(e?.response?.data?.message || t('admin.screens.docList.loadError', 'Could not load document list.'));
    }
    setLoading(false);
  }, []);

  useEffect(() => { load(); }, [load]);

  const reindexAll = async () => {
    setReindexing(true); setError(null); setNotice(null);
    try {
      const r = await axios.post('/api/doc-ingest/admin/reindex');
      setNotice(t('admin.screens.docList.reindexNotice', 'Re-indexed {{count}} document(s) with the updated embedding pipeline.', { count: r.data?.reindexed ?? 0 }));
      toast.success(t('admin.screens.docList.reindexSuccess', 'Re-indexed {{count}} document(s) successfully.', { count: r.data?.reindexed ?? 0 }));
      await load();
    } catch (e) {
      setError(e?.response?.data?.message || e?.response?.data?.error || t('admin.screens.docList.reindexError', 'Re-index failed.'));
    }
    setReindexing(false);
  };

  const confirmDelete = async () => {
    if (!deleting) return;
    try {
      await axios.delete(`/api/doc-ingest/admin/documents/${deleting}`);
      setDocs(d => d.filter(x => x.documentId !== deleting));
      setDeleting(null);
      toast.success(t('admin.screens.docList.deleteSuccess', 'Document deleted.'));
    } catch (e) {
      setDeleteErr(e?.response?.data?.message || t('admin.screens.docList.deleteError', 'Delete failed.'));
    }
  };

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.docList.scopeResource', 'ingested documents')} />

      <Box sx={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', mb: 2 }}>
        <Box>
          <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>{t('admin.screens.docList.title', 'Ingested Documents')}</Typography>
          <Typography fontSize={12} color='#7D92C4'>
            {t('admin.screens.docList.subtitle', 'Documents indexed into the vector store. Processing is asynchronous — refresh to see updates.')}
          </Typography>
        </Box>
        <Box sx={{ display: 'flex', gap: 1 }}>
          <Tooltip title={t('admin.screens.docList.reindexTooltip', 'Re-embed all stored documents with the current embedding pipeline (use after a retrieval-quality fix)')}>
            <span>
              <Button size='small' startIcon={<AutorenewIcon />} onClick={reindexAll}
                disabled={reindexing}
                sx={{ textTransform: 'none', fontSize: 12 }}>
                {reindexing ? t('admin.screens.docList.reindexing', 'Re-indexing…') : t('admin.screens.docList.reindexAll', 'Re-index all')}
              </Button>
            </span>
          </Tooltip>
          <Button size='small' startIcon={<RefreshIcon />} onClick={load}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.docList.refresh', 'Refresh')}</Button>
        </Box>
      </Box>

      {notice && (
        <Alert severity='success' sx={{ mb: 2, fontSize: 13 }} onClose={() => setNotice(null)}>{notice}</Alert>
      )}
      {error && (
        <Alert severity='error' sx={{ mb: 2, fontSize: 13 }} onClose={() => setError(null)}>{error}</Alert>
      )}
      {deleteErr && (
        <Alert severity='error' sx={{ mb: 2, fontSize: 13 }} onClose={() => setDeleteErr(null)}>{deleteErr}</Alert>
      )}

      {loading ? (
        <Box sx={{ p: 4, textAlign: 'center' }}><CircularProgress size={28} /></Box>
      ) : (
        <CollapsibleSection title={t('admin.screens.docList.sectionTitle', 'Ingested documents')}
          count={docs.length} detailsSx={{ p: 0 }}>
        <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
          <Table size='small'>
            <TableHead>
              <TableRow sx={{ bgcolor: '#0F2450' }}>
                {[
                  { k: 'colDocument', en: 'Document' },
                  { k: 'colType',     en: 'Type' },
                  { k: 'colFiling',   en: 'Filing' },
                  { k: 'colActor',    en: 'Actor' },
                  { k: 'colStatus',   en: 'Status' },
                  { k: 'colChunks',   en: 'Chunks' },
                  { k: 'colSize',     en: 'Size' },
                  { k: 'colStorage',  en: 'Storage' },
                  { k: 'colUploaded', en: 'Uploaded' },
                  { k: 'colActions',  en: 'Actions' },
                ].map(h => (
                  <TableCell key={h.k} sx={{ fontWeight: 700, fontSize: 12, color: '#C3D3F2',
                    borderBottom: '2px solid #243A72', py: 1.5, whiteSpace: 'nowrap' }}>
                    {t(`admin.screens.docList.${h.k}`, h.en)}
                  </TableCell>
                ))}
              </TableRow>
            </TableHead>
            <TableBody>
              {docs.length === 0 ? (
                <TableRow>
                  <TableCell colSpan={10} sx={{ textAlign: 'center', py: 4, color: '#7D92C4', fontSize: 13 }}>
                    {t('admin.screens.docList.emptyState', 'No documents ingested yet. Use the Document Ingestion tab to upload files.')}
                  </TableCell>
                </TableRow>
              ) : docs.map((doc, i) => {
                const stDef = STATUS_CHIP[doc.status];
                const st = stDef
                  ? { label: t(`admin.screens.docList.${stDef.labelKey}`, stDef.labelEn), bg: stDef.bg, color: stDef.color }
                  : { label: doc.status, bg: '#243A72', color: '#C3D3F2' };
                return (
                  <TableRow key={doc.documentId} sx={{
                    bgcolor: i % 2 === 0 ? '#152652' : '#0F2450',
                    '&:hover': { bgcolor: 'rgba(91, 157, 255, 0.14)' },
                  }}>
                    <TableCell sx={{ maxWidth: 220 }}>
                      <Tooltip title={doc.fileName || doc.documentId}>
                        <Typography fontSize={12} fontWeight={600} color='#FFFFFF'
                          sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {doc.title || doc.fileName || doc.documentId}
                        </Typography>
                      </Tooltip>
                      {doc.referenceNumber && (
                        <Typography fontSize={10} color='#7D92C4'>{doc.referenceNumber}</Typography>
                      )}
                      <Tooltip title={t('admin.screens.docList.documentIdTooltip', 'Document ID: {{id}}', { id: doc.documentId })}>
                        <Typography fontSize={10} color='#7D92C4' fontFamily='monospace'>
                          id: {doc.documentId?.slice(0, 8)}…
                        </Typography>
                      </Tooltip>
                    </TableCell>
                    <TableCell>
                      {doc.documentType ? (
                        <Chip label={doc.documentType} size='small'
                          sx={{ fontSize: 10, bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#8DBCFF' }} />
                      ) : (
                        <Typography fontSize={12} color='#7D92C4'>—</Typography>
                      )}
                    </TableCell>
                    <TableCell sx={{ fontSize: 12, color: '#C3D3F2', maxWidth: 160 }}>
                      {(doc.department || doc.category || doc.folder) ? (
                        <Tooltip title={[
                          doc.folder     && t('admin.screens.docList.filingFolder', 'Folder: {{value}}', { value: doc.folder }),
                          doc.department && t('admin.screens.docList.filingDept', 'Dept: {{value}}', { value: doc.department }),
                          doc.category   && t('admin.screens.docList.filingCategory', 'Category: {{value}}', { value: doc.category }),
                        ].filter(Boolean).join(' · ')}>
                          <Typography fontSize={11} color='#C3D3F2'
                            sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {[doc.department, doc.category].filter(Boolean).join(' · ') || doc.folder}
                          </Typography>
                        </Tooltip>
                      ) : (
                        <Typography fontSize={12} color='#7D92C4'>—</Typography>
                      )}
                    </TableCell>
                    <TableCell sx={{ fontSize: 12, color: '#C3D3F2', maxWidth: 140 }}>
                      {doc.uploadedBy ? (
                        <Tooltip title={doc.uploadedBy}>
                          <Typography fontSize={11} color='#C3D3F2'
                            sx={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                            {doc.uploadedBy}
                          </Typography>
                        </Tooltip>
                      ) : (
                        <Typography fontSize={12} color='#7D92C4'>—</Typography>
                      )}
                    </TableCell>
                    <TableCell>
                      <Chip label={st.label} size='small'
                        sx={{ fontSize: 10, bgcolor: st.bg, color: st.color }} />
                      {doc.error && (
                        <Tooltip title={doc.error}>
                          <ErrorIcon sx={{ fontSize: 13, color: '#FF6B81', ml: 0.5, verticalAlign: 'middle' }} />
                        </Tooltip>
                      )}
                    </TableCell>
                    <TableCell sx={{ fontSize: 12, color: '#C3D3F2' }}>
                      {doc.chunksStored > 0 ? doc.chunksStored : '—'}
                    </TableCell>
                    <TableCell sx={{ fontSize: 12, color: '#C3D3F2' }}>
                      {fmtBytes(doc.fileSizeBytes)}
                    </TableCell>
                    <TableCell sx={{ fontSize: 12, color: '#C3D3F2', maxWidth: 160 }}>
                      {doc.storageBackend ? (
                        <Tooltip title={doc.storageLocation
                          ? `${doc.storageBackend} · ${doc.storageLocation}`
                          : doc.storageBackend}>
                          <Chip label={doc.storageBackend} size='small'
                            sx={{ fontSize: 10, bgcolor: '#0F2450', color: '#C3D3F2' }} />
                        </Tooltip>
                      ) : (
                        <Typography fontSize={12} color='#7D92C4'>—</Typography>
                      )}
                    </TableCell>
                    <TableCell sx={{ fontSize: 12, color: '#C3D3F2', whiteSpace: 'nowrap' }}>
                      {relTime(doc.submittedAt)}
                    </TableCell>
                    <TableCell>
                      <Tooltip title={t('admin.screens.docList.deleteTooltip', 'Delete document and its vectors')}>
                        <Button size='small' color='error' startIcon={<DeleteIcon sx={{ fontSize: 13 }} />}
                          onClick={() => setDeleting(doc.documentId)}
                          sx={{ textTransform: 'none', fontSize: 11, minWidth: 0, px: 1 }}>
                          {t('admin.screens.docList.delete', 'Delete')}
                        </Button>
                      </Tooltip>
                    </TableCell>
                  </TableRow>
                );
              })}
            </TableBody>
          </Table>
        </Paper>
        </CollapsibleSection>
      )}

      <Typography fontSize={11} color='#7D92C4' mt={1}>
        {t('admin.screens.docList.footerCount', '{{count}} document(s) — persisted in ArcadeDB (survives service restarts).', { count: docs.length })}
      </Typography>

      {/* Delete confirm dialog */}
      <Dialog open={!!deleting} onClose={() => setDeleting(null)} maxWidth='xs' fullWidth>
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>{t('admin.screens.docList.deleteDialogTitle', 'Delete document?')}</DialogTitle>
        <DialogContent>
          <Typography fontSize={13} color='#C3D3F2'>
            {t('admin.screens.docList.deleteDialogBody', 'This permanently removes the document file and all its vector embeddings from Qdrant. The action cannot be undone.')}
          </Typography>
        </DialogContent>
        <DialogActions>
          <Button size='small' onClick={() => setDeleting(null)}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.docList.cancel', 'Cancel')}</Button>
          <Button size='small' variant='contained' color='error'
            onClick={confirmDelete}
            sx={{ textTransform: 'none', fontSize: 12 }}>{t('admin.screens.docList.delete', 'Delete')}</Button>
        </DialogActions>
      </Dialog>
    </Box>
  );
}
