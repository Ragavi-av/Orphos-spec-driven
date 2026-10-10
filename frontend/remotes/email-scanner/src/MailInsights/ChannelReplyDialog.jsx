import { useEffect, useState } from 'react';
import {
  Box, Typography, Button, TextField, Alert, CircularProgress,
  Dialog, DialogTitle, DialogContent, DialogActions,
} from '@mui/material';
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome';
import SendIcon from '@mui/icons-material/Send';
import { commsAgentDraft, commsAgentSend } from './mailApi';

/**
 * Communications agent dialog behind the "AI: Draft reply…" kebab action on
 * channel items (Slack / Microsoft Teams / …).
 *
 * Flow: the executive optionally types an instruction → the agent drafts (LLM
 * via the tenant's AI provider) → the reply is editable → explicit Send posts it
 * back to the originating channel through that channel's connector. Nothing is
 * posted without pressing Send. Unlike the email dialog there is no recipient /
 * subject — the reply goes to the channel the message came from.
 */
export default function ChannelReplyDialog({ item, open, onClose, onDone }) {
  const [instruction, setInstruction] = useState('');
  const [drafting, setDrafting] = useState(false);
  const [sending, setSending] = useState(false);
  const [err, setErr] = useState(null);
  const [draft, setDraft] = useState(null);   // null until generated / composed
  const [bodyText, setBodyText] = useState('');

  useEffect(() => {
    if (!open) return;
    setInstruction(''); setErr(null); setDraft(null); setBodyText('');
  }, [open, item?.id]);

  const generate = async () => {
    setDrafting(true); setErr(null);
    try {
      const d = await commsAgentDraft(item.id, instruction);
      setDraft(d);
      setBodyText(d.bodyText || '');
    } catch (e) {
      setErr(e?.response?.data?.error || 'Drafting failed — you can still compose manually.');
      setDraft({});
    }
    setDrafting(false);
  };

  const send = async () => {
    setSending(true); setErr(null);
    try {
      await commsAgentSend(item.id, bodyText);
      onDone?.();
      onClose();
    } catch (e) {
      setErr(e?.response?.data?.error || 'Send failed');
    }
    setSending(false);
  };

  const canSend = !sending && bodyText.trim();

  return (
    <Dialog open={open} onClose={onClose} maxWidth='sm' fullWidth
      onClick={e => e.stopPropagation()}>
      <DialogTitle sx={{ fontSize: 14.5, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 1 }}>
        <AutoAwesomeIcon sx={{ fontSize: 18, color: '#00B0F0' }} />
        AI: Draft a channel reply
      </DialogTitle>
      <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, pt: '8px !important' }}>
        <Box sx={{ p: 1, bgcolor: '#1A2247', borderRadius: 1, border: '1px solid rgba(202, 220, 252, 0.18)' }}>
          <Typography fontSize={12} fontWeight={600} noWrap>{item?.title}</Typography>
          {item?.senderDisplay && (
            <Typography fontSize={11} color='#9AA5C8'>From: {item.senderDisplay}</Typography>
          )}
        </Box>

        <TextField size='small' fullWidth multiline minRows={2} autoFocus
          label='What should the reply say? (e.g. "acknowledge and say I will review by EOD")'
          value={instruction} onChange={e => setInstruction(e.target.value)}
          sx={{ '& textarea': { fontSize: 12.5 } }} />

        <Box>
          <Button size='small' variant='outlined' startIcon={drafting
            ? <CircularProgress size={13} /> : <AutoAwesomeIcon sx={{ fontSize: 15 }} />}
            disabled={drafting} onClick={generate} sx={{ textTransform: 'none', fontSize: 12 }}>
            {draft ? 'Regenerate draft' : 'Generate draft'}
          </Button>
        </Box>

        {err && <Alert severity='error' sx={{ fontSize: 12 }}>{err}</Alert>}

        {draft && (
          <>
            <TextField size='small' fullWidth required multiline minRows={6}
              label='Reply text'
              value={bodyText} onChange={e => setBodyText(e.target.value)}
              sx={{ '& textarea': { fontSize: 12.5 } }} />
            <Typography fontSize={11} color='#9AA5C8'>
              Posted back to the channel this message came from, through your organisation's
              configured connector.
            </Typography>
          </>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button size='small' onClick={onClose} sx={{ textTransform: 'none' }}>Cancel</Button>
        <Button size='small' variant='contained' disabled={!draft || !canSend} onClick={send}
          startIcon={sending ? <CircularProgress size={13} /> : <SendIcon sx={{ fontSize: 15 }} />}
          sx={{ textTransform: 'none' }}>
          Send reply
        </Button>
      </DialogActions>
    </Dialog>
  );
}
