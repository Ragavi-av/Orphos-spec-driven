import { useState, useEffect, useRef, useCallback, useSyncExternalStore } from 'react';
import {
  Paper, Box, Typography, Chip, TextField,
  IconButton, CircularProgress,
  Select, MenuItem, Dialog, DialogTitle, DialogContent, DialogActions,
  Button, Tooltip, InputAdornment,
} from '@mui/material';
import AutoAwesomeIcon   from '@mui/icons-material/AutoAwesome';
import SettingsIcon      from '@mui/icons-material/Settings';
import CheckCircleIcon   from '@mui/icons-material/CheckCircle';
import VisibilityIcon    from '@mui/icons-material/Visibility';
import VisibilityOffIcon from '@mui/icons-material/VisibilityOff';
import AddIcon           from '@mui/icons-material/Add';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import { useAuth } from 'eip-shell-context/AuthProvider';
import OrphosSparkle from 'eip-shell-context/OrphosSparkle';
import OrphosOrb from 'eip-shell-context/OrphosOrb';
import { useVoiceStatus, useVoiceDictation, useClickOrDoubleClick } from 'eip-shell-context/voice';
import { identityFrom, migrateLocalSessions } from './sessionStore';
import * as runtime from './copilotRuntime';
import { modelFamily } from './modelFamily';

const PROVIDER_KEY    = 'orphos.ai.providerId';
const MODELS_KEY      = 'orphos.ai.providerModels';
const USER_PICKED_KEY = 'orphos.ai.providerUserPicked';

// Placeholder shown only until /api/nl-query/providers responds. It names no
// model on purpose: the in-house model is operator-configured (OLLAMA_CHAT_MODEL)
// and every hardcoded guess here has gone stale in turn — "Llama 3.2", then
// "Granite 3.1", while the route actually served Gemma 3. The real model arrives
// with the providers response and the label is derived from it.
const LOADING_PROVIDERS = [
  { id: 'inhouse', label: 'In-house model', internal: true, available: true,
    description: 'Loading providers configured for your tenant…' },
];

const MODEL_HINTS = {
  gemma:  { placeholder: 'gemma-3-27b-it',   helper: 'Examples: gemma-3-27b-it, gemini-2.0-flash, gemini-1.5-pro' },
  claude: { placeholder: 'claude-opus-4-7',  helper: 'Examples: claude-opus-4-7, claude-sonnet-4-6, claude-haiku-4-5-20251001' },
};

function loadModels() {
  try { return JSON.parse(localStorage.getItem(MODELS_KEY) || '{}'); } catch { return {}; }
}
function saveModels(next) {
  try { localStorage.setItem(MODELS_KEY, JSON.stringify(next)); } catch {}
}

/**
 * Greeting bucket for the current hour.
 *
 * Resolved in the TENANT's timezone (branding profile, threaded through the
 * remote's `context`) and not the viewer's browser: a tenant in Asia read from
 * Europe should be greeted on its own clock. Falls back to local time when the
 * tenant has set no timezone or the stored value is not a zone this engine
 * knows, so a bad profile value degrades to a plausible greeting rather than
 * throwing inside render.
 */
function greetingKey(tz) {
  let hour;
  try {
    hour = Number(new Intl.DateTimeFormat('en-GB', {
      hour: 'numeric', hour12: false, timeZone: tz || undefined,
    }).format(new Date()));
  } catch {
    hour = new Date().getHours();
  }
  // Some engines render midnight as "24" under hour12:false.
  if (!Number.isFinite(hour)) hour = new Date().getHours();
  hour %= 24;

  if (hour < 12) return 'morning';
  if (hour < 17) return 'afternoon';
  return 'evening';
}

/**
 * @param {boolean}  [hideProviderRow] the shell is rendering the model control
 *                   in its dock bar instead, so the in-panel picker row is
 *                   suppressed to avoid showing the same control twice
 * @param {function} [onModelLabel]    publishes the active provider's label up
 *                   to the shell for that chip
 * @param {boolean}  [embedded]        the shell's dock supplies the card around
 *                   this panel, so drop the local border and corners
 */
export default function AICopilot({ externalQuery, tenantTimezone,
                                    hideProviderRow = false, onModelLabel,
                                    embedded = false }) {
  const { t, i18n } = useTranslation();
  // Identity only namespaces the LEGACY localStorage key during migration —
  // the server scopes history to the token's sub (see sessionStore.js).
  const keycloak = useAuth();
  const identity = identityFrom(keycloak);
  // Default follow-up suggestions, reactive to the active language.
  const DEFAULT_SUGGESTIONS = [
    t('copilot.suggest.risks'),
    t('copilot.suggest.aws'),
    t('copilot.suggest.slas'),
  ];
  // Locale for timestamp formatting follows the UI language.
  const dateLocale = (i18n.language || 'en').startsWith('ja') ? 'ja-JP' : 'en-GB';

  // ── Session state ──────────────────────────────────────────────────────────
  // The conversation lives in copilotRuntime, a module singleton, NOT in this
  // component: the shell's dock unmounts this panel whenever the user moves to
  // Documents, History, the collapsed rail or the Admin section, and any query
  // in flight used to be lost with the state. Now the request resolves into the
  // store regardless, and re-mounting here just re-subscribes to it.
  const session   = useSyncExternalStore(runtime.subscribe, runtime.getSnapshot);
  const messages  = session.messages;
  const question  = session.draft;
  const loading   = messages.some(m => m.loading);
  const scrollRef = useRef(null);

  // ── Provider state ─────────────────────────────────────────────────────────
  const [providers, setProviders]     = useState(LOADING_PROVIDERS);
  const [tenantDefault, setTenantDefault] = useState(null);
  const [userPicked, setUserPicked]   = useState(() => localStorage.getItem(USER_PICKED_KEY) === '1');
  const [providerId, setProviderId]   = useState(() => localStorage.getItem(PROVIDER_KEY) || 'inhouse');
  const [models, setModels]           = useState(loadModels);
  const [myKeys, setMyKeys]           = useState({});
  const [keyBackendUp, setKeyBackendUp] = useState(true);

  // ── Settings dialog ────────────────────────────────────────────────────────
  const [settingsOpen, setSettingsOpen] = useState(false);
  // Provider picker, shown as a dialog when the shell owns the model chip.
  const [pickerOpen, setPickerOpen] = useState(false);
  // Whether focus is anywhere inside the composer group. Gates the fresh-panel
  // starter suggestions, which should appear only when the user reaches for the
  // input rather than sitting under it permanently.
  const [composerActive, setComposerActive] = useState(false);
  const [draftKey, setDraftKey]     = useState('');
  const [draftModel, setDraftModel] = useState('');
  const [showKey, setShowKey]       = useState(false);
  const [saving, setSaving]         = useState(false);
  const [saveError, setSaveError]   = useState(null);

  // ── Provider / key loading ─────────────────────────────────────────────────
  const refreshMyKeys = useCallback(() =>
    axios.get('/api/nl-query/me/keys')
      .then(res => {
        setMyKeys(res.data?.providers || {});
        setKeyBackendUp(res.data?.backendConfigured !== false);
      })
      .catch(() => { setMyKeys({}); setKeyBackendUp(false); }),
  []);

  useEffect(() => {
    axios.get('/api/nl-query/providers')
      .then(res => {
        const list = Array.isArray(res.data?.providers) ? res.data.providers : [];
        if (list.length === 0) return;
        setProviders(list);
        const td = res.data?.tenantDefault || res.data?.default || null;
        setTenantDefault(td);
        if (!userPicked && td && list.some(p => p.id === td)) {
          setProviderId(td);
        } else if (!list.some(p => p.id === providerId)) {
          setProviderId(td && list.some(p => p.id === td) ? td : list[0].id);
        }
      })
      .catch(() => {});
    refreshMyKeys();
  }, [refreshMyKeys]); // eslint-disable-line react-hooks/exhaustive-deps

  const handleProviderChange = (id) => {
    setProviderId(id);
    setUserPicked(true);
    localStorage.setItem(PROVIDER_KEY, id);
    localStorage.setItem(USER_PICKED_KEY, '1');
  };

  // ── External query injection ───────────────────────────────────────────────
  // Dedup lives in the runtime (claimExternalQuery), not in a per-mount ref —
  // see the note there.
  useEffect(() => {
    if (!runtime.claimExternalQuery(externalQuery)) return;
    ask(externalQuery.query, externalQuery.label || null);
  }, [externalQuery]); // eslint-disable-line react-hooks/exhaustive-deps

  // ── Auto-scroll to latest message ─────────────────────────────────────────
  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages]);

  // ── One-time migration of legacy localStorage history to the server ───────
  useEffect(() => {
    migrateLocalSessions(identity).catch(() => { /* retried on next load */ });
  }, [identity]);

  // Session history is persisted by copilotRuntime on every message change —
  // it has to happen there rather than in an effect here, so an answer that
  // lands while this panel is unmounted is still written to history.

  // ── Derived provider state ─────────────────────────────────────────────────
  const activeProvider = providers.find(p => p.id === providerId) || providers[0];
  const isExternal     = activeProvider && !activeProvider.internal;

  // The model this provider will actually call: a personal override beats the
  // tenant/server-resolved model reported by /api/nl-query/providers.
  const activeModel = (isExternal ? models[providerId] : null) || activeProvider?.model || null;

  // Publish the active model's label so the shell's dock bar can show it.
  // Derived from the model, NOT from the provider id or its static label: the
  // 'gemma' route serves gemini-* models and 'inhouse-hi' serves Qwen2.5, so
  // the id would name the wrong family (see modelFamily.js).
  // Keyed on the label alone, not on the callback: the shell rebuilds its
  // context object on every render, and depending on the function identity
  // would fire this on each one.
  const modelLabel = activeModel
    ? (activeProvider?.internal
        ? `${t('copilot.internalProvider', 'In-house')} · ${modelFamily(activeModel, providerId)}`
        : modelFamily(activeModel, providerId))
    : (activeProvider?.label || providerId);
  useEffect(() => {
    if (onModelLabel) onModelLabel(modelLabel);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [modelLabel]);

  // The shell's model chip cannot reach into this remote, so it asks by event.
  // A window event rather than a context callback because the request travels
  // shell → remote, the opposite direction from every other channel here.
  useEffect(() => {
    const open = () => setPickerOpen(true);
    window.addEventListener('orphos:copilot-open-model', open);
    return () => window.removeEventListener('orphos:copilot-open-model', open);
  }, []);
  const userHasKey     = !!myKeys[providerId]?.configured;
  const tenantHasKey   = !!activeProvider?.tenantKeyConfigured;
  const needsKey       = isExternal && !activeProvider.available && !userHasKey && !tenantHasKey;

  // ── Ask ────────────────────────────────────────────────────────────────────
  // Everything past validation is handed to the runtime store, so the request
  // and its answer are not tied to this component being mounted.
  const ask = (q, sourceLabel = null) => {
    const query = (typeof q === 'string' ? q : question).trim();
    if (!query) return;

    if (needsKey) {
      runtime.appendMessage({
        id: runtime.genId(), question: query,
        answer: `The "${activeProvider.label}" provider needs an API key. Open settings to save one.`,
        followups: DEFAULT_SUGGESTIONS,
        provider: activeProvider?.label || providerId,
        timestamp: new Date().toISOString(), loading: false, source: sourceLabel,
      });
      openSettings();
      return;
    }

    runtime.ask({
      query,
      providerId,
      // Footer names the model that answered, derived from the model itself
      // rather than the route id (see modelFamily.js).
      providerLabel: modelLabel,
      model: isExternal ? (models[providerId] || null) : null,
      sourceLabel,
      fallbackFollowups: DEFAULT_SUGGESTIONS,
    });
  };

  // ── Session management ─────────────────────────────────────────────────────
  const startNewSession = useCallback(() => runtime.startNewSession(), []);

  // ── Voice dictation ────────────────────────────────────────────────────────
  // The ORB carries both actions: single click submits, double click toggles
  // dictation. Voice is a separately managed platform functionality, so the
  // double-click gesture only exists once it is live for this tenant — see
  // eip-shell-context/voice.js.
  const { voiceEnabled } = useVoiceStatus();
  const [voiceError, setVoiceError] = useState(null);

  const { recording, transcribing, toggle: toggleDictation } = useVoiceDictation({
    // The transcript lands in the DRAFT, never straight into a question:
    // speech-to-text mishears names and numbers, and the user must be able to
    // fix it before it is asked. Appended rather than replacing, so dictating
    // after typing (or twice in a row) adds to the thought instead of eating it.
    onTranscript: (text) => {
      setVoiceError(null);
      const existing = (runtime.getSnapshot().draft || '').trim();
      runtime.setDraft(existing ? `${existing} ${text}` : text);
    },
    onError: setVoiceError,
  });

  // The orb RESTS white for everyone and returns there once a question is sent.
  // Dark is not a badge for 'this tenant has voice' — it is the sign that voice is
  // engaged right now, so the composer looks identical between questions whether or
  // not dictation is available. The trade-off, accepted deliberately: a white orb no
  // longer distinguishes 'no voice here' from 'voice idle'. The tooltip carries that
  // instead, and the double-click gesture simply does nothing where voice is off.
  const orbVariant = recording    ? 'darkSelected'   // mic live, pulsing
                   : transcribing ? 'dark'           // speech being converted
                   :                'white';         // resting — including after a question is asked

  // While the mic is live a stray single click must not fire the half-dictated
  // draft off to the model — the user is still talking.
  const orbHandlers = useClickOrDoubleClick(
    () => { if (!recording && !transcribing) ask(); },
    voiceEnabled ? toggleDictation : null,
  );

  const orbTooltip = recording    ? t('copilot.voice.stop', 'Recording — double-click to stop')
                   : transcribing ? t('copilot.voice.transcribing', 'Transcribing…')
                   : voiceEnabled ? t('copilot.voice.ready', 'Click to send · double-click to dictate')
                   :                t('copilot.send', 'Send');

  // ── Settings dialog helpers ────────────────────────────────────────────────
  const openSettings = () => {
    setDraftKey(''); setDraftModel(models[providerId] || '');
    setShowKey(false); setSaveError(null); setSettingsOpen(true);
  };

  const saveDraft = () => {
    setSaveError(null);
    const trimmedModel = draftModel.trim();
    const nextModels = { ...models };
    if (trimmedModel) nextModels[providerId] = trimmedModel;
    else              delete nextModels[providerId];
    setModels(nextModels);
    saveModels(nextModels);

    const trimmedKey = draftKey.trim();
    if (!trimmedKey) { setSettingsOpen(false); return; }

    setSaving(true);
    axios.put(`/api/nl-query/me/keys/${providerId}`, { apiKey: trimmedKey })
      .then(() => refreshMyKeys())
      .then(() => setSettingsOpen(false))
      .catch(err => {
        const msg = err.response?.data?.error
          || (err.response?.status === 503
              ? 'Server-side key storage is not configured (OPENBAO_TOKEN is missing).'
              : 'Could not save the key. Try again or contact your admin.');
        setSaveError(msg);
      })
      .finally(() => setSaving(false));
  };

  const clearKey = () => {
    setSaveError(null); setSaving(true);
    axios.delete(`/api/nl-query/me/keys/${providerId}`)
      .then(() => refreshMyKeys())
      .then(() => setDraftKey(''))
      .catch(err => setSaveError(err.response?.data?.error || 'Could not clear the saved key.'))
      .finally(() => setSaving(false));
  };

  // ── Derived UI values ──────────────────────────────────────────────────────
  const lastMsg  = messages[messages.length - 1];
  // The model's own follow-ups once it has offered some, the generic starters
  // otherwise — so the sheet always has something to show, including on a fresh
  // panel. Empty only while an answer is in flight, where the previous turn's
  // follow-ups no longer refer to what is on screen.
  const followups = loading ? []
    : (lastMsg && !lastMsg.loading && lastMsg.followups?.length > 0)
      ? lastMsg.followups
      : DEFAULT_SUGGESTIONS;

  // Follow-ups are ALWAYS the standing "Ask next" block above the composer, in
  // normal flow. The design's alternative — a sheet floating over the composer,
  // summoned by focus or hover — was removed: on a short panel it overlapped the
  // answer bubble it was meant to follow up on, hiding the very text the reader
  // was in the middle of.
  const sessionTime = new Date(session.startedAt).toLocaleTimeString(dateLocale, { hour: '2-digit', minute: '2-digit' });

  // ── Render ─────────────────────────────────────────────────────────────────
  // When embedded, the shell's dock draws the card and its footer bar, so this
  // panel drops its own border and corners — otherwise the bar sits outside a
  // card that visibly ends above it.
  return (
    <Paper elevation={0} sx={{
      border: embedded ? 'none' : '1px solid #243A72',
      borderRadius: embedded ? 0 : undefined,
      // The assistant surface — a half-step violet off the dashboard navy, so
      // the conversation reads as a different kind of surface rather than as
      // more card. The header strip sits one step above it.
      bgcolor: '#161C46',
      height: '100%', display: 'flex', flexDirection: 'column', overflow: 'hidden',
    }}>

      {/* ── Header ── */}
      <Box sx={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 1, px: '12px', py: '7px', borderBottom: '1px solid #2C3A72', bgcolor: '#1D2456', flexShrink: 0 }}>
        <Box sx={{ display: 'flex', alignItems: 'center', gap: '9px', minWidth: 0 }}>
          <Box sx={{ width: 22, height: 22, borderRadius: '7px', flexShrink: 0, bgcolor: '#2F6FF0', display: 'grid', placeItems: 'center' }}>
            <OrphosSparkle size={12} color='#FFFFFF' spark='#CFE2FF' />
          </Box>
          <Box sx={{ minWidth: 0 }}>
            <Typography fontWeight={660} fontSize={13} letterSpacing='-0.1px' color="#EAF0FF" noWrap>{t('copilot.title')}</Typography>
            <Typography fontSize={10} color="#7D92C4" noWrap>{t('copilot.since', { time: sessionTime })}</Typography>
          </Box>
        </Box>
        <Box sx={{ display: 'flex', gap: 0.5, alignItems: 'center' }}>
          <Tooltip title={t('copilot.newSession')}>
            <IconButton size="small" onClick={startNewSession}
              sx={{ width: 22, height: 22, borderRadius: '7px', border: '1px solid #2E4A8C', bgcolor: '#0F2450', color: '#A8BBE6', '&:hover': { color: '#FFFFFF', bgcolor: '#123A6B' } }}>
              <AddIcon sx={{ fontSize: 16 }} />
            </IconButton>
          </Tooltip>
        </Box>
      </Box>

      {/* ── Provider picker row ── */}
      <Box sx={{ display: hideProviderRow ? 'none' : 'flex', alignItems: 'center', gap: 1, px: 2, py: 1, borderBottom: '1px solid #243A72', flexShrink: 0 }}>
        <Typography fontSize={10} color="#C3D3F2" sx={{ flexShrink: 0 }}>{t('copilot.answerWith')}</Typography>
        <Select size="small"
          value={providers.some(p => p.id === providerId) ? providerId : (providers[0]?.id || '')}
          onChange={e => handleProviderChange(e.target.value)}
          sx={{ flex: 1, minWidth: 0, fontSize: 12, height: 28, '& .MuiSelect-select': { py: 0.5, textOverflow: 'ellipsis', overflow: 'hidden', whiteSpace: 'nowrap' } }}
        >
          {providers.map(p => {
            // Name the model the route resolves to — the route's own label can
            // disagree with it (the 'gemma' route serves gemini-* models).
            const model = (!p.internal ? models[p.id] : null) || p.model || null;
            return (
              <MenuItem key={p.id} value={p.id} sx={{ fontSize: 12 }}>
                {model ? modelFamily(model, p.id) : p.label}
                {p.internal ? '' : ' · ' + t('copilot.externalProvider', 'external')}
                {!p.internal && (myKeys[p.id]?.configured || p.tenantKeyConfigured) ? ' ✓' : ''}
                {p.id === tenantDefault ? ' · ' + t('copilot.tenantDefault', 'default') : ''}
              </MenuItem>
            );
          })}
        </Select>
        {isExternal && (
          <Tooltip title={t('copilot.configureForAccount')}>
            <span>
              <IconButton size="small" onClick={openSettings}
                sx={{ color: needsKey ? '#FF6B81' : (userHasKey ? '#3DDC97' : '#C3D3F2') }}>
                {userHasKey
                  ? <CheckCircleIcon sx={{ fontSize: 16 }} />
                  : <SettingsIcon    sx={{ fontSize: 16 }} />}
              </IconButton>
            </span>
          </Tooltip>
        )}
      </Box>

      {/* ── Key / status banners ── */}
      <Box sx={{ px: 2, pt: 1, display: 'flex', flexDirection: 'column', gap: 0.5, flexShrink: 0 }}>
        {needsKey && (
          <Box sx={{ px: 1.2, py: 0.6, borderRadius: 1.5, bgcolor: 'rgba(255, 0, 0, 0.14)', border: '1px solid rgba(255, 0, 0, 0.42)' }}>
            <Typography fontSize={10} color="#FF6B81" fontWeight={600}>
              No API key linked for {activeProvider?.label}. Click the gear to add one.
            </Typography>
          </Box>
        )}
        {isExternal && userHasKey && !needsKey && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, px: 1.2, py: 0.5, borderRadius: 1.5, bgcolor: 'rgba(61, 220, 151, 0.14)', border: '1px solid rgba(61, 220, 151, 0.42)' }}>
            <CheckCircleIcon sx={{ fontSize: 12, color: '#3DDC97' }} />
            <Typography fontSize={10} color="#3DDC97" fontWeight={600} noWrap>Using your saved {activeProvider?.label} key</Typography>
          </Box>
        )}
        {isExternal && !userHasKey && tenantHasKey && (
          <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, px: 1.2, py: 0.5, borderRadius: 1.5, bgcolor: 'rgba(91, 157, 255, 0.14)', border: '1px solid rgba(91, 157, 255, 0.42)' }}>
            <CheckCircleIcon sx={{ fontSize: 12, color: '#8DBCFF' }} />
            <Typography fontSize={10} color="#8DBCFF" fontWeight={600} noWrap>Using tenant {activeProvider?.label} key (set by your admin)</Typography>
          </Box>
        )}
      </Box>

      {/* ── Message thread (scrollable) ── */}
      {/* Scrollbar hidden, scrolling kept: `overflowY: auto` still scrolls by
          wheel, trackpad, touch, keyboard and the auto-scroll effect above —
          only the visible track is suppressed, in each engine's own way. */}
      <Box ref={scrollRef} sx={{
        flex: 1, overflowY: 'auto', px: 1.5, py: 1.5,
        display: 'flex', flexDirection: 'column', gap: 2,
        scrollbarWidth: 'none',            // Firefox
        msOverflowStyle: 'none',           // legacy Edge/IE
        '&::-webkit-scrollbar': { display: 'none' },   // Chrome, Safari, Edge
      }}>

        {/* Welcome state — shown before first question */}
        {messages.length === 0 && (
          <Box sx={{ bgcolor: '#123A6B', border: '1px solid #245C9E', borderRadius: '12px', px: '13px', py: '12px' }}>
            <Typography fontSize={12.8} color="#DBE9FF" lineHeight={1.6}>
              {t('copilot.welcome', {
                greeting: t(`copilot.greeting.${greetingKey(tenantTimezone)}`),
              })}
            </Typography>
          </Box>
        )}

        {/* Messages */}
        {messages.map((msg) => (
          <Box key={msg.id}>
            {/* External source badge */}
            {msg.source && (
              <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.5, px: 1.2, py: 0.4, borderRadius: 1.5, mb: 0.75, bgcolor: 'rgba(91, 157, 255, 0.14)', border: '1px solid rgba(91, 157, 255, 0.42)', width: 'fit-content' }}>
                <AutoAwesomeIcon sx={{ fontSize: 11, color: '#8DBCFF' }} />
                <Typography fontSize={10} color="#8DBCFF" fontWeight={600}>Context: {msg.source}</Typography>
              </Box>
            )}

            {/* Question bubble (right-aligned) */}
            <Box sx={{ display: 'flex', justifyContent: 'flex-end', mb: '10px' }}>
              <Box sx={{ bgcolor: '#1D3672', border: '1px solid #2F4F96', color: '#EAF0FF', borderRadius: '12px', px: '13px', py: '11px', ml: '40px' }}>
                <Typography fontSize={12.5} lineHeight={1.5}>{msg.question}</Typography>
              </Box>
            </Box>

            {/* Answer bubble (left-aligned) */}
            <Box sx={{ bgcolor: '#123A6B', border: '1px solid #245C9E', borderRadius: '12px', px: '13px', py: '12px' }}>
              {msg.loading ? (
                <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
                  <CircularProgress size={14} sx={{ flexShrink: 0 }} />
                  <Typography fontSize={12} color="#C3D3F2" fontStyle="italic">
                    {t('copilot.thinking', { detail: isExternal ? activeProvider?.label : t('copilot.cpuHint') })}
                  </Typography>
                </Box>
              ) : (
                <Typography fontSize={12.8} color="#DBE9FF" lineHeight={1.6} sx={{ whiteSpace: 'pre-wrap' }}>
                  {msg.answer}
                </Typography>
              )}
            </Box>

            {/* Trust signal (grounding band, confidence, sources) removed by
                request — the /ask envelope still carries msg.trust, so putting
                <TrustBadge trust={msg.trust} /> back here restores it. */}

            {/* Timestamp + provider */}
            {!msg.loading && (
              <Typography fontSize={10} color="#7D92C4" mt={0.4} textAlign="right">
                {new Date(msg.timestamp).toLocaleTimeString(dateLocale, { hour: '2-digit', minute: '2-digit' })} · {msg.provider}
              </Typography>
            )}
          </Box>
        ))}
      </Box>

      {/* ── Standing "Ask next" block — only once a conversation exists ──
              A fresh panel shows the greeting alone: follow-ups there would be
              generic starters, not follow-ups, and they crowd the composer
              before there is anything to follow up ON. It appears with the
              first answer (during the request `followups` is empty, so the
              block stays down while the answer is in flight). */}
      {messages.length > 0 && followups.length > 0 && (
        <Box sx={{ px: 1.5, pb: 0.75, flexShrink: 0 }}>
          <Typography fontSize={10} color="#7D92C4" mb={0.5}>{t('copilot.askNext')}</Typography>
          <Box sx={{ display: 'flex', gap: 0.5, flexWrap: 'wrap' }}>
            {followups.map((s, i) => (
              <Chip key={i} label={s} size="small"
                onClick={() => ask(s)}
                disabled={loading}
                sx={{ fontSize: 10.5, height: 'auto', py: '3px', borderRadius: '7px', cursor: 'pointer',
                      bgcolor: '#0D2249', color: '#9DC0F5', border: '1px solid #2E4A8C',
                      '&:hover': { bgcolor: '#123A6B', borderColor: '#245C9E' },
                      '&:disabled': { opacity: 0.4 } }}
              />
            ))}
          </Box>
        </Box>
      )}

      {/* ── Input row ── */}
      {/* onFocus/onBlur sit on the WRAPPER, not the text field: React's are
          focusin/focusout, which bubble, so focus moving from the field to a
          starter row below keeps the group "focused" and the list open. Closing
          on the field's own blur would unmount the rows before a click on one
          could land. `relatedTarget` is where focus went — null when it left the
          document entirely, which also counts as leaving. */}
      <Box sx={{ px: 1.5, pb: 1.5, flexShrink: 0 }}
        onFocus={() => setComposerActive(true)}
        onBlur={e => {
          if (!e.currentTarget.contains(e.relatedTarget)) setComposerActive(false);
        }}
      >
          {/* One framed composer with the send button inside it, as the design
              draws it — a bordered field beside a separately bordered button read
              as two controls that happened to be adjacent. */}
          <Box sx={{
            display: 'flex', alignItems: 'center', gap: 1,
            bgcolor: '#0F2450', border: '1px solid #2E4A8C', borderRadius: '12px',
            pl: '13px', pr: '9px', py: '8px',
          }}>
            <TextField fullWidth variant="standard"
              placeholder={recording
                ? t('copilot.voice.listening', 'Listening… double-click the orb to finish')
                : transcribing
                  ? t('copilot.voice.transcribing', 'Transcribing…')
                  : t('copilot.placeholder')}
              value={question}
              onChange={e => runtime.setDraft(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); ask(); } }}
              InputProps={{ disableUnderline: true }}
              sx={{ '& .MuiInput-input': { fontSize: 12.5, color: '#EAF0FF', p: 0 } }}
            />
            {/* The ORB replaces the old arrow. It is the mark for "this goes to
                Ask Orphos", and its variant IS the voice state: white when
                voice is off for this tenant, dark when dictation is available,
                the glow variant while the mic is live. The button keeps no
                background of its own — the artwork carries the shape, and a
                blue rounded square behind it read as a tile with a sticker on
                it. `onDoubleClick` never fires without the deferred single
                click being cancelled first; see useClickOrDoubleClick. */}
            <Tooltip title={orbTooltip}>
              <span>
                <IconButton size="small"
                  {...orbHandlers}
                  disabled={loading || transcribing}
                  aria-label={orbTooltip}
                  aria-pressed={recording}
                  sx={{
                    width: 32, height: 32, flexShrink: 0, p: '4px', borderRadius: '9px',
                    bgcolor: 'transparent',
                    '&:hover': { bgcolor: 'rgba(91, 157, 255, 0.14)' },
                    '&:disabled': { opacity: 0.45 },
                    // Cancels the text-selection flash a double click leaves on
                    // the surrounding composer.
                    userSelect: 'none',
                  }}>
                  <OrphosOrb variant={orbVariant} size={24} />
                </IconButton>
              </span>
            </Tooltip>
          </Box>

          {/* Dictation failures are surfaced here rather than as a toast: the
              user is looking at the composer, and the fix (allow the mic, speak
              again) is a composer action. */}
          {voiceError && (
            <Typography fontSize={10.5} color="#FF6B81" sx={{ pt: 0.75 }}>
              {voiceError}
            </Typography>
          )}

          {/* ── Starter suggestions — fresh panel, composer focused ──
                  Plain rows UNDER the composer, per the design reference. Shown
                  only once the user has actually reached for the composer: on an
                  empty panel these are starters rather than follow-ups, so they
                  are an answer to "what can I ask?" and only worth the space
                  after that question is asked. Once a turn exists they give way
                  to the "Ask next" chips above the composer instead. */}
          {messages.length === 0 && composerActive && followups.length > 0 && (
            <Box sx={{ pt: 1, display: 'flex', flexDirection: 'column', alignItems: 'flex-start' }}>
              {followups.map((s, i) => (
                <Box key={i} component="button" type="button"
                  onClick={() => ask(s)} disabled={loading}
                  sx={{
                    border: 'none', bgcolor: 'transparent', p: 0, py: '3px',
                    font: 'inherit', fontSize: 11.5, lineHeight: 1.5,
                    color: '#A8BBE6', textAlign: 'left', cursor: 'pointer',
                    '&:hover':    { color: '#FFFFFF' },
                    '&:disabled': { color: '#4D6299', cursor: 'default' },
                  }}>
                  {s}
                </Box>
              ))}
            </Box>
          )}
      </Box>

      {/* ── Provider settings dialog ── */}
      {/* Provider picker — the dialog behind the shell's model chip. Same list
          the in-panel Select shows; only the container differs. */}
      <Dialog open={pickerOpen} onClose={() => setPickerOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle sx={{ fontSize: 14 }}>{t('copilot.answerWith')}</DialogTitle>
        <DialogContent dividers sx={{ p: 0 }}>
          {providers.map(p => {
            const selected = p.id === providerId;
            const keyed = !p.internal && (myKeys[p.id]?.configured || p.tenantKeyConfigured);
            const model = (!p.internal ? models[p.id] : null) || p.model || null;
            return (
              <Box key={p.id} component="button"
                onClick={() => { handleProviderChange(p.id); setPickerOpen(false); }}
                sx={{
                  display: 'block', width: '100%', textAlign: 'left', cursor: 'pointer',
                  px: 2, py: 1.25, border: 'none', fontFamily: 'inherit',
                  bgcolor: selected ? 'rgba(91, 157, 255, 0.14)' : 'transparent',
                  borderLeft: `3px solid ${selected ? '#8DBCFF' : 'transparent'}`,
                  '&:hover': { bgcolor: 'rgba(91, 157, 255, 0.10)' },
                }}>
                <Typography fontSize={12.5} fontWeight={selected ? 700 : 500} color="#FFFFFF">
                  {model ? modelFamily(model, p.id) : p.label}
                </Typography>
                <Typography fontSize={10.5} color="#7D92C4">
                  {/* The exact model, so the row is unambiguous when a route's
                      name and its configured model belong to different families. */}
                  {model ? model + ' · ' : ''}
                  {p.internal ? t('copilot.internalProvider', 'In-house')
                              : t('copilot.externalProvider', 'external')}
                  {keyed ? ' · ' + t('copilot.keyLinked', 'key linked') : ''}
                  {p.id === tenantDefault ? ' · ' + t('copilot.tenantDefault', 'default') : ''}
                </Typography>
              </Box>
            );
          })}
        </DialogContent>
        <DialogActions>
          {isExternal && (
            <Button size="small" onClick={() => { setPickerOpen(false); openSettings(); }}
              sx={{ textTransform: 'none', fontSize: 12 }}>
              <SettingsIcon sx={{ fontSize: 15, mr: 0.5 }} />
              {t('copilot.configureProvider', 'Configure key')}
            </Button>
          )}
          <Button size="small" onClick={() => setPickerOpen(false)}
            sx={{ textTransform: 'none', fontSize: 12 }}>
            {t('common.close', 'Close')}
          </Button>
        </DialogActions>
      </Dialog>

      <Dialog open={settingsOpen} onClose={() => setSettingsOpen(false)} fullWidth maxWidth="xs">
        <DialogTitle sx={{ fontSize: 15, fontWeight: 700 }}>Configure {activeProvider?.label}</DialogTitle>
        <DialogContent>
          <Typography fontSize={12} color="#C3D3F2" sx={{ mb: 2 }}>
            {activeProvider?.description}
            <br />
            Your key is saved to your Orphos profile in the secure vault, scoped to your SSO identity. It is never returned to the browser.
            {!keyBackendUp && (
              <Box component="span" sx={{ color: '#FF6B81', fontWeight: 600, display: 'block', mt: 0.5 }}>
                Server-side key storage is not configured — ask an admin to set OPENBAO_TOKEN.
              </Box>
            )}
          </Typography>

          {userHasKey && (
            <Box sx={{ mb: 2, px: 1.2, py: 0.8, borderRadius: 1.5, bgcolor: 'rgba(61, 220, 151, 0.14)', border: '1px solid rgba(61, 220, 151, 0.42)', display: 'flex', alignItems: 'center', gap: 1 }}>
              <CheckCircleIcon sx={{ fontSize: 14, color: '#3DDC97' }} />
              <Typography fontSize={11} color="#3DDC97" fontWeight={600}>
                A key is already saved. Enter a new one to replace it, or clear it below.
              </Typography>
            </Box>
          )}

          <TextField
            label={userHasKey ? 'Replace API key (optional)' : 'API key'}
            type={showKey ? 'text' : 'password'}
            value={draftKey}
            onChange={e => setDraftKey(e.target.value)}
            disabled={!keyBackendUp}
            fullWidth size="small" sx={{ mb: 2 }}
            InputProps={{
              endAdornment: (
                <InputAdornment position="end">
                  <IconButton size="small" onClick={() => setShowKey(s => !s)}>
                    {showKey ? <VisibilityOffIcon fontSize="small" /> : <VisibilityIcon fontSize="small" />}
                  </IconButton>
                </InputAdornment>
              ),
            }}
          />
          <TextField
            label={t('copilot.settings.modelOptional')}
            value={draftModel}
            onChange={e => setDraftModel(e.target.value)}
            placeholder={MODEL_HINTS[providerId]?.placeholder || ''}
            helperText={MODEL_HINTS[providerId]?.helper || t('copilot.settings.modelHelper')}
            fullWidth size="small"
          />

          {saveError && (
            <Typography fontSize={11} color="#FF6B81" sx={{ mt: 1.5 }}>{saveError}</Typography>
          )}
        </DialogContent>
        <DialogActions sx={{ px: 3, pb: 2 }}>
          <Button onClick={clearKey} color="error" size="small" disabled={saving || !userHasKey}>
            {t('copilot.settings.clearKey')}
          </Button>
          <Box sx={{ flex: 1 }} />
          <Button onClick={() => setSettingsOpen(false)} size="small" disabled={saving}>
            {t('common.cancel')}
          </Button>
          <Button onClick={saveDraft} variant="contained" size="small" disabled={saving}>
            {saving ? t('copilot.settings.saving') : t('common.save')}
          </Button>
        </DialogActions>
      </Dialog>
    </Paper>
  );
}
