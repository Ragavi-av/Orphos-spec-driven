import { useState, useRef } from 'react';
import {
  Box, Typography, Paper, Button, Alert, CircularProgress,
  TextField, Chip, LinearProgress, Divider, Tooltip,
} from '@mui/material';
import UploadFileIcon  from '@mui/icons-material/UploadFile';
import LinkIcon        from '@mui/icons-material/Link';
import CheckCircleIcon from '@mui/icons-material/CheckCircle';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import TenantScopeBanner from 'eip-shell-context/TenantScopeBanner';
import { useToast } from 'eip-shell-context/ToastProvider';

// Keep in sync with the backend: SupportedFormats and TranscriptionClient's
// doc-ingest.whisper.formats. Audio is transcribed via the Whisper sidecar.
const ACCEPT_TYPES = '.pdf,.docx,.doc,.txt,.md,.csv,.xlsx,.pptx,.html,.htm,.png,.jpg,.jpeg,.tiff,.mp3,.wav,.m4a,.mp4,.aac,.ogg,.oga,.opus,.flac,.webm,.amr,.3gp';
const MAX_MB = 50;

function UploadZone({ onDrop, dragging, setDragging }) {
  const { t } = useTranslation();
  return (
    <Box
      onDragOver={e => { e.preventDefault(); setDragging(true); }}
      onDragLeave={() => setDragging(false)}
      onDrop={e => { e.preventDefault(); setDragging(false); onDrop(e.dataTransfer.files); }}
      sx={{
        border: `2px dashed ${dragging ? '#2F6FF0' : '#2E4A8C'}`,
        borderRadius: 2,
        p: 4,
        textAlign: 'center',
        bgcolor: dragging ? 'rgba(91, 157, 255, 0.14)' : '#0F2450',
        cursor: 'pointer',
        transition: 'all 0.2s',
      }}
    >
      <UploadFileIcon sx={{ fontSize: 40, color: dragging ? '#8DBCFF' : '#7D92C4', mb: 1 }} />
      <Typography fontWeight={600} fontSize={14} color='#FFFFFF'>
        {t('admin.screens.docSettings.dragDrop', 'Drag & drop files here')}
      </Typography>
      <Typography fontSize={12} color='#7D92C4' mt={0.5}>
        {t('admin.screens.docSettings.acceptedTypes', 'PDF, Word, Excel, PowerPoint, images, plain text — up to {{max}} MB each', { max: MAX_MB })}
      </Typography>
    </Box>
  );
}

export default function DocIngestSettingsTab() {
  const { t } = useTranslation();
  const toast = useToast();
  const fileRef = useRef(null);
  const [uploads, setUploads] = useState([]);   // { name, status, error, documentId }
  const [dragging, setDragging] = useState(false);
  const [urlVal, setUrlVal] = useState('');
  const [urlStatus, setUrlStatus] = useState(null);  // null | 'loading' | 'ok' | error string
  const [docType, setDocType] = useState('');
  const [refNum, setRefNum] = useState('');

  const addFiles = async (fileList) => {
    const files = Array.from(fileList).filter(f => f.size <= MAX_MB * 1024 * 1024);
    const tooLarge = Array.from(fileList).filter(f => f.size > MAX_MB * 1024 * 1024);
    if (tooLarge.length) {
      setUploads(u => [...u, ...tooLarge.map(f => ({
        name: f.name, status: 'error', error: t('admin.screens.docSettings.exceedsLimit', 'Exceeds {{max}} MB limit', { max: MAX_MB }),
      }))]);
    }
    let accepted = 0;
    for (const file of files) {
      const row = { name: file.name, status: 'uploading' };
      setUploads(u => [row, ...u]);
      const form = new FormData();
      form.append('file', file);
      if (docType) form.append('documentType', docType);
      if (refNum)  form.append('referenceNumber', refNum);
      try {
        const r = await axios.post('/api/doc-ingest/upload', form, {
          headers: { 'Content-Type': 'multipart/form-data' },
        });
        setUploads(u => u.map(x => x === row
          ? { ...x, status: 'accepted', documentId: r.data.documentId } : x));
        accepted += 1;
      } catch (e) {
        const msg = e?.response?.data?.message || e.message || t('admin.screens.docSettings.uploadFailed', 'Upload failed');
        setUploads(u => u.map(x => x === row ? { ...x, status: 'error', error: msg } : x));
      }
    }
    if (accepted > 0) {
      toast.success(t('admin.screens.docSettings.uploadAccepted', '{{count}} file(s) accepted for ingestion. They will appear in the document list once processing completes.', { count: accepted }));
    }
  };

  const submitUrl = async () => {
    const url = urlVal.trim();
    if (!url) return;
    setUrlStatus('loading');
    try {
      await axios.post('/api/doc-ingest/url', null, {
        params: { url, documentType: docType || undefined, referenceNumber: refNum || undefined },
      });
      setUrlStatus('ok');
      setUrlVal('');
    } catch (e) {
      setUrlStatus(e?.response?.data?.message || t('admin.screens.docSettings.urlFailed', 'URL ingestion failed'));
    }
  };

  return (
    <Box>
      <TenantScopeBanner resource={t('admin.screens.docSettings.scopeResource', 'document ingest')} />

      <Box sx={{ mb: 2.5 }}>
        <Typography fontWeight={700} fontSize={15} color='#FFFFFF'>{t('admin.screens.docSettings.title', 'Document Ingestion')}</Typography>
        <Typography fontSize={12} color='#7D92C4'>
          {t('admin.screens.docSettings.subtitle', 'Upload files or submit URLs to index them for document search. OCR is applied to scanned PDFs automatically.')}
        </Typography>
      </Box>

      {/* Optional metadata */}
      <Paper variant='outlined' sx={{ p: 2, mb: 2, borderRadius: 2 }}>
        <Typography fontWeight={600} fontSize={13} color='#C3D3F2' mb={1.5}>
          {t('admin.screens.docSettings.optionalMetadata', 'Optional metadata (applies to all items in this batch)')}
        </Typography>
        <Box sx={{ display: 'flex', gap: 2, flexWrap: 'wrap' }}>
          <TextField size='small' label={t('admin.screens.docSettings.documentType', 'Document type')}
            placeholder={t('admin.screens.docSettings.documentTypePlaceholder', 'e.g. Policy, Report, Contract')}
            value={docType} onChange={e => setDocType(e.target.value)}
            sx={{ flex: 1, minWidth: 200, '& input': { fontSize: 12 } }} />
          <TextField size='small' label={t('admin.screens.docSettings.referenceNumber', 'Reference number')}
            placeholder={t('admin.screens.docSettings.referenceNumberPlaceholder', 'e.g. POL-2024-001')}
            value={refNum} onChange={e => setRefNum(e.target.value)}
            sx={{ flex: 1, minWidth: 200, '& input': { fontSize: 12 } }} />
        </Box>
      </Paper>

      {/* File upload */}
      <Paper variant='outlined' sx={{ p: 2, mb: 2, borderRadius: 2 }}>
        <Typography fontWeight={600} fontSize={13} color='#C3D3F2' mb={1.5}>
          {t('admin.screens.docSettings.fileUpload', 'File Upload')}
        </Typography>
        <UploadZone onDrop={addFiles} dragging={dragging} setDragging={setDragging} />
        <Box sx={{ mt: 1.5, textAlign: 'center' }}>
          <Button size='small' variant='outlined'
            startIcon={<UploadFileIcon />}
            onClick={() => fileRef.current?.click()}
            sx={{ textTransform: 'none', fontSize: 12 }}>
            {t('admin.screens.docSettings.browseFiles', 'Browse files')}
          </Button>
          <input ref={fileRef} type='file' multiple accept={ACCEPT_TYPES} style={{ display: 'none' }}
            onChange={e => { addFiles(e.target.files); e.target.value = ''; }} />
        </Box>
      </Paper>

      {/* URL ingest */}
      <Paper variant='outlined' sx={{ p: 2, mb: 2, borderRadius: 2 }}>
        <Typography fontWeight={600} fontSize={13} color='#C3D3F2' mb={1.5}>
          {t('admin.screens.docSettings.ingestFromUrl', 'Ingest from URL')}
        </Typography>
        <Box sx={{ display: 'flex', gap: 1, alignItems: 'flex-start' }}>
          <TextField size='small' fullWidth
            placeholder='https://example.com/document.pdf'
            value={urlVal} onChange={e => { setUrlVal(e.target.value); setUrlStatus(null); }}
            sx={{ '& input': { fontSize: 12 } }} />
          <Button variant='contained' size='small' startIcon={<LinkIcon />}
            disabled={!urlVal.trim() || urlStatus === 'loading'}
            onClick={submitUrl}
            sx={{ textTransform: 'none', fontSize: 12, whiteSpace: 'nowrap', flexShrink: 0 }}>
            {urlStatus === 'loading' ? <CircularProgress size={14} color='inherit' /> : t('admin.screens.docSettings.ingest', 'Ingest')}
          </Button>
        </Box>
        {urlStatus === 'ok' && (
          <Alert severity='success' sx={{ mt: 1, fontSize: 12 }} onClose={() => setUrlStatus(null)}>
            {t('admin.screens.docSettings.urlAccepted', 'URL accepted for ingestion. It will appear in the document list once processing completes.')}
          </Alert>
        )}
        {urlStatus && urlStatus !== 'loading' && urlStatus !== 'ok' && (
          <Alert severity='error' sx={{ mt: 1, fontSize: 12 }} onClose={() => setUrlStatus(null)}>
            {urlStatus}
          </Alert>
        )}
      </Paper>

      {/* Upload log */}
      {uploads.length > 0 && (
        <Paper variant='outlined' sx={{ borderRadius: 2, overflow: 'hidden' }}>
          <Box sx={{ px: 2, py: 1.5, bgcolor: '#0F2450', borderBottom: '1px solid #243A72' }}>
            <Typography fontWeight={600} fontSize={13} color='#C3D3F2'>
              {t('admin.screens.docSettings.uploadLog', 'Upload log ({{count}})', { count: uploads.length })}
            </Typography>
          </Box>
          {uploads.map((u, i) => (
            <Box key={i} sx={{
              display: 'flex', alignItems: 'center', gap: 1.5,
              px: 2, py: 1,
              borderBottom: i < uploads.length - 1 ? '1px solid #243A72' : 'none',
            }}>
              {u.status === 'uploading'
                ? <CircularProgress size={14} />
                : u.status === 'accepted'
                ? <CheckCircleIcon sx={{ fontSize: 16, color: '#3DDC97' }} />
                : <Box sx={{ fontSize: 16, color: '#FF6B81' }}>✕</Box>
              }
              <Typography fontSize={12} color='#FFFFFF' sx={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                {u.name}
              </Typography>
              <Chip
                label={u.status === 'uploading' ? t('admin.screens.docSettings.uploading', 'Uploading…') : u.status === 'accepted' ? t('admin.screens.docSettings.accepted', 'Accepted') : u.error}
                size='small'
                sx={{
                  fontSize: 10,
                  bgcolor: u.status === 'accepted' ? 'rgba(61, 220, 151, 0.14)' : u.status === 'uploading' ? 'rgba(91, 157, 255, 0.14)' : 'rgba(255, 0, 0, 0.14)',
                  color: u.status === 'accepted' ? '#3DDC97' : u.status === 'uploading' ? '#8DBCFF' : '#FF6B81',
                }}
              />
            </Box>
          ))}
        </Paper>
      )}
    </Box>
  );
}
