import { useEffect, useState } from 'react';
import {
  Box, Typography, Button, TextField, Alert, CircularProgress, Chip,
  Dialog, DialogTitle, DialogContent, DialogActions,
} from '@mui/material';
import AutoAwesomeIcon from '@mui/icons-material/AutoAwesome';
import SendIcon from '@mui/icons-material/Send';
import { agentDraft, agentSend } from './mailApi';

/**
 * Email-task agent dialog behind the "AI: Draft reply…" / "AI: Schedule
 * meeting…" kebab actions.
 *
 * Flow: the executive types an instruction → the agent drafts (LLM via the
 * tenant's AI provider) → every field is editable → explicit Send delivers
 * through the tenant's SMTP configuration (Communications tab). Nothing is
 * sent without the user pressing Send.
 */
export default function AgentActionDialog({ item, action, open, onClose, onDone }) {
  const isMeeting = action === 'MEETING';

  const [instruction, setInstruction] = useState('');
  const [drafting, setDrafting] = useState(false);
  const [sending, setSending] = useState(false);
  const [err, setErr] = useState(null);
  const [note, setNote] = useState(null);
  const [draft, setDraft] = useState(null); // null until generated / composed

  const [to, setTo] = useState('');
  const [cc, setCc] = useState('');
  const [subject, setSubject] = useState('');
  const [bodyText, setBodyText] = useState('');
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [location, setLocation] = useState('');

  // Reset per item/action so a previous draft never leaks into a new dialog.
  useEffect(() => {
    if (!open) return;
    setInstruction(''); setErr(null); setNote(null); setDraft(null);
    setTo(''); setCc(''); setSubject(''); setBodyText('');
    setStart(''); setEnd(''); setLocation('');
  }, [open, item?.id, action]);

  const generate = async () => {
    setDrafting(true); setErr(null);
    try {
      const d = await agentDraft(item.id, action, instruction);
      setDraft(d);
      setSubject(d.subject || '');
      setBodyText(d.bodyText || '');
      setStart(d.start || '');
      setEnd(d.end || '');
      setLocation(d.location || '');
      setNote(d.note || null);
    } catch (e) {
      setErr(e?.response?.data?.error || 'Drafting failed — you can still compose manually.');
      setDraft({}); // fall through to the editable form
    }
    setDrafting(false);
  };

  const splitAddresses = s => s.split(',').map(x => x.trim()).filter(Boolean);

  const send = async () => {
    setSending(true); setErr(null);
    try {
      await agentSend(item.id, {
        action,
        to: splitAddresses(to),
        cc: splitAddresses(cc),
        subject, bodyText,
        start: isMeeting ? start : '',
        end: isMeeting ? end : '',
        location: isMeeting ? location : '',
      });
      onDone?.();
      onClose();
    } catch (e) {
      setErr(e?.response?.data?.error || 'Send failed');
    }
    setSending(false);
  };

  const canSend = !sending && splitAddresses(to).length > 0 && subject.trim() && bodyText.trim()
    && (!isMeeting || start);

  return (
    <Dialog open={open} onClose={onClose} maxWidth='sm' fullWidth
      onClick={e => e.stopPropagation()}>
      <DialogTitle sx={{ fontSize: 14.5, fontWeight: 700, display: 'flex', alignItems: 'center', gap: 1 }}>
        <AutoAwesomeIcon sx={{ fontSize: 18, color: '#00B0F0' }} />
        {isMeeting ? 'AI: Schedule a meeting' : 'AI: Draft a reply'}
      </DialogTitle>
      <DialogContent sx={{ display: 'flex', flexDirection: 'column', gap: 1.5, pt: '8px !important' }}>
        <Box sx={{ p: 1, bgcolor: '#1A2247', borderRadius: 1, border: '1px solid rgba(202, 220, 252, 0.18)' }}>
          <Typography fontSize={12} fontWeight={600} noWrap>{item?.title}</Typography>
          {item?.senderDisplay && (
            <Typography fontSize={11} color='#9AA5C8'>From: {item.senderDisplay}</Typography>
          )}
        </Box>

        <TextField size='small' fullWidth multiline minRows={2} autoFocus
          label={isMeeting
            ? 'What should the agent set up? (e.g. "30-min sync next Tuesday 10:00 with the sender")'
            : 'What should the reply say? (e.g. "confirm I will approve the payment by Friday")'}
          value={instruction} onChange={e => setInstruction(e.target.value)}
          sx={{ '& textarea': { fontSize: 12.5 } }} />

        <Box>
          <Button size='small' variant='outlined' startIcon={drafting
            ? <CircularProgress size={13} /> : <AutoAwesomeIcon sx={{ fontSize: 15 }} />}
            disabled={drafting} onClick={generate} sx={{ textTransform: 'none', fontSize: 12 }}>
            {draft ? 'Regenerate draft' : 'Generate draft'}
          </Button>
          {note && <Chip size='small' label={note} sx={{ ml: 1, fontSize: 10.5, height: 20 }} />}
        </Box>

        {err && <Alert severity='error' sx={{ fontSize: 12 }}>{err}</Alert>}

        {draft && (
          <>
            <TextField size='small' fullWidth required
              label='To (comma-separated email addresses)'
              placeholder='person@example.com'
              value={to} onChange={e => setTo(e.target.value)}
              helperText={item?.senderDisplay
                ? `Replying to "${item.senderDisplay}" — enter their email address.` : undefined}
              sx={{ '& input': { fontSize: 12.5 } }} />
            <TextField size='small' fullWidth label='Cc (optional)'
              value={cc} onChange={e => setCc(e.target.value)}
              sx={{ '& input': { fontSize: 12.5 } }} />
            <TextField size='small' fullWidth required label='Subject'
              value={subject} onChange={e => setSubject(e.target.value)}
              sx={{ '& input': { fontSize: 12.5 } }} />
            {isMeeting && (
              <Box sx={{ display: 'flex', gap: 1.5 }}>
                <TextField size='small' type='datetime-local' required label='Starts'
                  InputLabelProps={{ shrink: true }}
                  value={start} onChange={e => setStart(e.target.value)}
                  sx={{ flex: 1, '& input': { fontSize: 12.5 } }} />
                <TextField size='small' type='datetime-local' label='Ends'
                  InputLabelProps={{ shrink: true }}
                  value={end} onChange={e => setEnd(e.target.value)}
                  sx={{ flex: 1, '& input': { fontSize: 12.5 } }} />
              </Box>
            )}
            {isMeeting && (
              <TextField size='small' fullWidth label='Location / meeting link (optional)'
                value={location} onChange={e => setLocation(e.target.value)}
                sx={{ '& input': { fontSize: 12.5 } }} />
            )}
            <TextField size='small' fullWidth required multiline minRows={6}
              label={isMeeting ? 'Invitation text / agenda' : 'Reply body'}
              value={bodyText} onChange={e => setBodyText(e.target.value)}
              sx={{ '& textarea': { fontSize: 12.5 } }} />
            <Typography fontSize={11} color='#9AA5C8'>
              {isMeeting
                ? 'Sent as a calendar invite (.ics) through your organisation\'s configured email — recipients can accept or decline; responses go to your address.'
                : 'Sent through your organisation\'s configured email with replies routed to your address.'}
            </Typography>
          </>
        )}
      </DialogContent>
      <DialogActions sx={{ px: 3, pb: 2 }}>
        <Button size='small' onClick={onClose} sx={{ textTransform: 'none' }}>Cancel</Button>
        <Button size='small' variant='contained' disabled={!draft || !canSend} onClick={send}
          startIcon={sending ? <CircularProgress size={13} /> : <SendIcon sx={{ fontSize: 15 }} />}
          sx={{ textTransform: 'none' }}>
          {isMeeting ? 'Send invite' : 'Send reply'}
        </Button>
      </DialogActions>
    </Dialog>
  );
}
