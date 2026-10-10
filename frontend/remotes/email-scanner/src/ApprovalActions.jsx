import { useState } from 'react';
import { Box, Button, Chip, Typography } from '@mui/material';
import GavelIcon from '@mui/icons-material/Gavel';
import { useTranslation } from 'react-i18next';
import axios from 'axios';

// Decision pairs per approvalType — the same set the mobile app offers, and the
// values mobile-bff's ApprovalService accepts.
const CHOICES = {
  APPROVE_REJECT: [['APPROVE', 'approve', 'Approve'], ['REJECT', 'reject', 'Reject']],
  YES_NO:         [['YES', 'yes', 'Yes'], ['NO', 'no', 'No']],
  GO_AHEAD:       [['GO', 'go', 'Go ahead'], ['HOLD', 'hold', 'Hold']],
};

// Approve/reject strip under a task that has a linked approval. Writes to the
// shared mobile-bff approval record, so a decision made here is the decision the
// mobile Approvals screen shows — and the BFF then closes the task for both.
export default function ApprovalActions({ approval, onDecided }) {
  const { t } = useTranslation();
  const [busy, setBusy]   = useState(false);
  const [error, setError] = useState(null);

  const [yes, no] = CHOICES[approval.approvalType] || CHOICES.APPROVE_REJECT;

  const decide = async (decision) => {
    setBusy(true);
    setError(null);
    try {
      await axios.post(`/api/mobile/approvals/${encodeURIComponent(approval.id)}/decision`, { decision });
      onDecided?.();
    } catch (e) {
      setError(e?.response?.status === 410
        ? t('tasks.approval.expired', 'This approval has expired and can no longer be actioned.')
        : t('tasks.approval.failed', 'Could not record the decision. Try again.'));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, px: 2, pb: 1.25, flexWrap: 'wrap' }}>
      <Chip size='small' icon={<GavelIcon sx={{ fontSize: 12 }} />}
        label={t('tasks.approval.required', 'Approval required')}
        sx={{ fontSize: 10, fontWeight: 700, bgcolor: 'rgba(155, 124, 255, 0.16)', color: '#B9A6FF',
              '& .MuiChip-icon': { color: '#B9A6FF' } }} />
      <Box sx={{ flex: 1 }} />
      {error && <Typography fontSize={11} color='#FF6B6B'>{error}</Typography>}
      <Button size='small' variant='outlined' disabled={busy} onClick={() => decide(no[0])}
        sx={{ fontSize: 11, textTransform: 'none', py: 0.25 }}>
        {t(`tasks.approval.${no[1]}`, no[2])}
      </Button>
      <Button size='small' variant='contained' disabled={busy} onClick={() => decide(yes[0])}
        sx={{ fontSize: 11, textTransform: 'none', py: 0.25 }}>
        {t(`tasks.approval.${yes[1]}`, yes[2])}
      </Button>
    </Box>
  );
}
