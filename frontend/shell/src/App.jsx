import { useState, useMemo, useEffect, useRef, useCallback } from 'react';
import { Box, CssBaseline, GlobalStyles, ThemeProvider, createTheme, Tabs, Tab, Snackbar, Alert, Tooltip, IconButton, Typography, Switch } from '@mui/material';
import ChevronRightIcon from '@mui/icons-material/ChevronRight';
import ArrowBackIcon    from '@mui/icons-material/ArrowBack';
import HistoryIcon      from '@mui/icons-material/History';
import TuneIcon         from '@mui/icons-material/Tune';
import DescriptionOutlinedIcon from '@mui/icons-material/DescriptionOutlined';
import { useTranslation } from 'react-i18next';
import { useAuth }       from 'eip-shell-context/AuthProvider';
import { useBranding, brandingAssetUrl } from 'eip-shell-context/BrandingProvider';
import { tenantThemeOptions, DEFAULT_PALETTE } from 'eip-shell-context/tenantTheme';
import { ORPHOS_TOKENS, ORPHOS_COLORS, orphosAnim } from 'eip-shell-context/orphosTheme';
import HeaderBar, { HEADER_COLORS } from './components/HeaderBar/HeaderBar';
import useSectionPref from 'eip-shell-context/useSectionPref';
import DashboardTabStrip from './components/DashboardTabs/DashboardTabStrip';
import OrphosSparkle from 'eip-shell-context/OrphosSparkle';
import PluginLoader       from './PluginLoader';
import { usePlugin, usePluginTabs } from './PluginRegistry';
import GuestPage         from './components/GuestPage/GuestPage';
import AdminPage         from './components/AdminPage/AdminPage';
import TenantHealthBanner from './components/TenantHealthBanner/TenantHealthBanner';
import DashboardGreeting from './components/DashboardGreeting/DashboardGreeting';
import { useRole }       from 'eip-shell-context/useRole';
import { useDashboardLayout } from 'eip-shell-context/DashboardLayoutProvider';
import { useCountry } from 'eip-shell-context/country';
import { bootstrapGet } from 'eip-shell-context/webBff';
import { toGrantMap, mergeEffect } from './components/AdminPage/accessCatalog';

// Tabs whose content should remount (re-fetch) when the header's refresh
// button is clicked — matches the pre-federation behavior where
// TasksForAction/NotificationsPanel were keyed by `refreshKey`.
const REFRESH_KEYED_TABS = new Set(['tasks-for-action', 'notifications']);

// Platform palette lives in eip-shell-context/tenantTheme so the shell and every
// remote fall back to the same values. Imported rather than redeclared — a local
// copy is how this drifted out of sync with the theme in the first place.

/** Build an MUI theme from a tenant-supplied branding theme view. Falls back
 *  to the platform defaults when a field is missing — this means a half-
 *  populated profile still renders cleanly instead of breaking colors. */
function buildTheme(brandingTheme, brandingProfile) {
  // Theme options live in the shared eip-shell-context/tenantTheme so the shell
  // AND every Module Federation remote build an identical theme — typography
  // (font family + size) and colours apply on every screen and tab, not just
  // the shell (remotes apply it via RemoteThemeProvider from context.themeParams).
  return createTheme(tenantThemeOptions({
    mode: brandingTheme?.mode,
    colors: brandingTheme?.colors,
    fontFamily: brandingProfile?.fontFamily,
    fontSize: brandingProfile?.fontSize,
  }));
}

/**
 * Tab body.
 *
 * The tab strip sits BELOW its body, not above it — the panel rounds its top
 * corners and drops its bottom edge, and the strip closes the shape off
 * underneath. This is the v3 JP artboard's layout and it is deliberate: the
 * strip is a dock the panel rests on, so collapsing the panel leaves the strip
 * parked at the foot of the dashboard rather than stranding a row of headers
 * over nothing. Reading order still works — the content is what you came for,
 * and the strip is the control you reach for afterwards.
 *
 * (The previous arrangement had this inverted, as top folder tabs. That was a
 * misreading of the artboard, whose panel carries `border-radius:12px 12px 0 0`
 * and `border-bottom:none` — chrome that only makes sense above a strip.)
 */
function TabPanel({ value, index, closing, children }) {
  if (value !== index) return null;
  return (
    <Box sx={{
      bgcolor: ORPHOS_TOKENS.bg.surface,
      border: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
      borderBottom: 'none',
      borderRadius: '12px 12px 0 0',
      px: '13px', pt: '2px', pb: 1,
      // Holds a readable height even for a tab whose content is one empty-state
      // line, so the dock does not jump up the page between tabs.
      minHeight: 150,
      // The panel grows upward off the strip it rests on, so it rises in and
      // sinks back out — the artboard's tabsAnim, orphRise/orphRiseOut.
      animation: orphosAnim('rise', closing),
    }}>
      {children}
    </Box>
  );
}

// Fallback header height (px), used only for the first paint before the real
// header has been measured.
//
// This was a hardcoded 80 that the sticky copilot dock offset itself against,
// with a comment requiring it to match the rendered HeaderBar. Nothing enforced
// that: the header's real height moves with the theme's font, the avatar size
// and the tenant's tagline, so the two drifted apart and the dock sat at the
// wrong offset while scrolling. It is now measured at runtime — see useHeaderHeight.
const HEADER_H_FALLBACK = 80;

/**
 * Live height of the sticky header.
 *
 * Observed rather than assumed, so the dock stays aligned when the font, the
 * avatar or a tenant's tagline changes the header's height. ResizeObserver also
 * catches the reflow when a long tagline wraps at narrow widths, which a
 * one-time measurement on mount would miss.
 */
function useHeaderHeight(ref) {
  const [h, setH] = useState(HEADER_H_FALLBACK);
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(([entry]) => {
      const next = Math.round(entry.contentRect.height);
      if (next > 0) setH(next);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [ref]);
  return h;
}

// Copilot dock sizing — persisted per-user in localStorage so the width the
// user drags to survives reloads.
const COPILOT_MIN_W   = 320;
const COPILOT_MAX_W   = 720;
const COPILOT_DEF_W   = 380;
const COPILOT_RAIL_W  = 52;   // collapsed width — the labelled Ask Orphos rail
const LS_WIDTH_KEY    = 'orphos.copilot.width';
const LS_COLLAPSED_KEY = 'orphos.copilot.collapsed';

// The co-pilot keeps answering while its panel is not rendered — its
// conversation lives in a module store inside the ai-copilot remote
// (remotes/ai-copilot/src/copilotRuntime.js), not in the panel's component
// state, so unmounting this dock's panel no longer discards a running query.

// "Bring the co-pilot into view" — raised alongside a query the user should
// see answered (the dashboard greeting's Ask Copilot button). Handing the panel
// a question is useless while the dock sits collapsed or shows document
// search, so the dock expands and the panel switches back to chat. A window
// event rather than a prop because the two pieces of state that have to move
// live in CopilotDock and DockPanel, neither of which the caller can reach.
const COPILOT_FOCUS_EVENT = 'orphos:copilot-focus';

/** Resizable / collapsible dock that hosts the AI co-pilot side-panel.
 *  - Drag the left edge to resize (clamped so it always stays inside the
 *    viewport, which is what was causing the panel to render off-screen).
 *  - Collapse to a thin rail to reclaim horizontal space. */
function CopilotDock({ context, headerH = HEADER_H_FALLBACK }) {
  const { t } = useTranslation();
  const [width, setWidth] = useState(() => {
    const saved = Number(localStorage.getItem(LS_WIDTH_KEY));
    return saved >= COPILOT_MIN_W && saved <= COPILOT_MAX_W ? saved : COPILOT_DEF_W;
  });
  const [collapsed, setCollapsed] = useState(
    () => localStorage.getItem(LS_COLLAPSED_KEY) === '1'
  );
  const dragRef = useRef(null);
  const dockRef = useRef(null);

  // Someone handed the panel a question — uncollapse so the answer is visible,
  // and pull the dock into view for the narrow viewports where the two-column
  // body has wrapped it below the fold. `block: 'nearest'` keeps this a no-op
  // on a desktop layout, where the sticky dock is already on screen.
  useEffect(() => {
    const onFocus = () => {
      setCollapsed(false);
      // After the expand has painted, or the rail is what gets scrolled to.
      requestAnimationFrame(() => {
        dockRef.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
      });
    };
    window.addEventListener(COPILOT_FOCUS_EVENT, onFocus);
    return () => window.removeEventListener(COPILOT_FOCUS_EVENT, onFocus);
  }, []);

  useEffect(() => { localStorage.setItem(LS_WIDTH_KEY, String(width)); }, [width]);
  useEffect(() => {
    localStorage.setItem(LS_COLLAPSED_KEY, collapsed ? '1' : '0');
  }, [collapsed]);

  // Clamp width against the viewport so the dock never overflows the window
  // (leave room for the left column + page padding).
  const clamp = useCallback((w) => {
    const vpMax = Math.max(COPILOT_MIN_W, Math.floor(window.innerWidth * 0.6));
    return Math.min(Math.max(w, COPILOT_MIN_W), Math.min(COPILOT_MAX_W, vpMax));
  }, []);

  useEffect(() => {
    const onResize = () => setWidth(w => clamp(w));
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [clamp]);

  const onDragStart = useCallback((e) => {
    e.preventDefault();
    dragRef.current = { startX: e.clientX, startW: width };
    const onMove = (ev) => {
      // Handle sits on the LEFT edge → dragging left widens the panel.
      const delta = dragRef.current.startX - ev.clientX;
      setWidth(clamp(dragRef.current.startW + delta));
    };
    const onUp = () => {
      window.removeEventListener('mousemove', onMove);
      window.removeEventListener('mouseup', onUp);
      document.body.style.userSelect = '';
      document.body.style.cursor = '';
    };
    document.body.style.userSelect = 'none';
    document.body.style.cursor = 'col-resize';
    window.addEventListener('mousemove', onMove);
    window.addEventListener('mouseup', onUp);
  }, [width, clamp]);

  // The dock pins at `headerH + 16` (below), so the subtracted total is that
  // offset plus whatever gap is left under the card. 32 makes the bottom gap
  // 16px — symmetric with the body's own `p: 2` padding — instead of the 48
  // that left a 32px dead band under the panel with nothing in it.
  const dockHeight = `calc(100vh - ${headerH + 32}px)`;

  if (collapsed) {
    // A labelled rail rather than a lone chevron: collapsed, the dock has to
    // still say what is behind it, or the co-pilot is invisible to anyone who
    // did not collapse it themselves. The whole rail is the target.
    return (
      <Box
        ref={dockRef}
        component="button"
        type="button"
        onClick={() => setCollapsed(false)}
        aria-label={t('app.copilotExpand', 'Expand AI Copilot')}
        sx={{
          width: COPILOT_RAIL_W, flexShrink: 0, p: 0,
          position: 'sticky', top: headerH + 16, height: dockHeight,
          display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 2,
          py: 2.25, cursor: 'pointer',
          bgcolor: ORPHOS_TOKENS.bg.assist,
          border: `1px solid ${ORPHOS_TOKENS.line.assist}`,
          borderRadius: 2,
          // Collapsing is the panel leaving toward the edge, so the rail it
          // leaves behind arrives from the same direction.
          animation: orphosAnim('slide'),
          '&:hover': { bgcolor: ORPHOS_TOKENS.bg.assistRaised },
        }}
      >
        <Box sx={{
          width: 30, height: 30, borderRadius: '9px', flexShrink: 0,
          bgcolor: ORPHOS_COLORS.primary, display: 'grid', placeItems: 'center',
        }}>
          <OrphosSparkle size={16} color='#FFFFFF' spark='#CFE2FF' />
        </Box>
        <Typography sx={{
          writingMode: 'vertical-rl', fontSize: 11.5, fontWeight: 600,
          letterSpacing: '1.6px', textTransform: 'uppercase',
          color: ORPHOS_TOKENS.text.bright,
        }}>
          {t('app.copilotTitle', 'Ask Orphos')}
        </Typography>
        {/* Reachable-and-idle light, mirroring the header's session dot. */}
        <Box sx={{
          width: 7, height: 7, borderRadius: '50%', mt: 'auto', flexShrink: 0,
          bgcolor: ORPHOS_TOKENS.status.success.graphic,
        }} />
      </Box>
    );
  }

  return (
    <Box ref={dockRef} sx={{
      width, flexShrink: 0, position: 'sticky', top: headerH + 16,
      height: dockHeight, display: 'flex', alignItems: 'stretch',
      // The rail and the panel are different elements, so React remounts on
      // toggle and the animation plays once per expand — it does NOT replay
      // while the user drags the panel wider.
      animation: orphosAnim('slide'),
    }}>
      {/* Drag-to-resize handle + collapse button (left rail) */}
      <Box sx={{
        width: 18, flexShrink: 0, display: 'flex', flexDirection: 'column',
        alignItems: 'center',
      }}>
        <Tooltip title={t('app.copilotCollapse', 'Collapse')} placement="left">
          <IconButton
            size="small"
            onClick={() => setCollapsed(true)}
            sx={{
              mt: 0.5, width: 22, height: 22, color: ORPHOS_TOKENS.text.muted,
              '&:hover': { color: ORPHOS_TOKENS.text.link, bgcolor: ORPHOS_TOKENS.state.hover },
            }}
          >
            <ChevronRightIcon sx={{ fontSize: 16 }} />
          </IconButton>
        </Tooltip>
        <Box
          onMouseDown={onDragStart}
          sx={{
            flex: 1, width: '100%', cursor: 'col-resize',
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            '&:hover .grip, &:active .grip': { bgcolor: ORPHOS_COLORS.primary },
          }}
        >
          <Box className="grip" sx={{ width: 3, height: 40, borderRadius: 2, bgcolor: ORPHOS_TOKENS.line.assist, transition: 'background-color .15s' }} />
        </Box>
      </Box>

      {/* Panel */}
      <Box sx={{ flex: 1, minWidth: 0, height: '100%' }}>
        <DockPanel context={context} />
      </Box>
    </Box>
  );
}

/**
 * What the co-pilot dock is showing.
 *
 *   copilot  — the AI chat panel (default)
 *   search   — document search, same component the withdrawn Document Search
 *              tab rendered, in its compact layout
 *   history  — past AI conversations and past document searches, side by side
 *
 * The shell owns this rather than any one remote because the three views come
 * from two different plugins: ai-copilot owns the chat and its history,
 * doc-ingest owns search and its history. Each keeps its own code and data; the
 * shell only decides which fills the dock.
 */
function DockPanel({ context }) {
  const { t } = useTranslation();
  const [mode, setMode] = useState('copilot');
  const [historyTab, setHistoryTab] = useState('ai');
  const [docQuery, setDocQuery] = useState('');
  const [modelLabel, setModelLabel] = useState('');
  // Size of the tenant's ingested corpus, for the hint under the dock bar.
  // null until known, so the line never renders a placeholder count.
  const [docCount, setDocCount] = useState(null);

  // A question was handed to the chat, so show the chat — the dock may well
  // have been left on document search or history from an earlier visit.
  useEffect(() => {
    const onFocus = () => setMode('copilot');
    window.addEventListener(COPILOT_FOCUS_EVENT, onFocus);
    return () => window.removeEventListener(COPILOT_FOCUS_EVENT, onFocus);
  }, []);

  // How many documents the tenant has ingested. Read once when the dock mounts:
  // the corpus does not change while a session is open, and the hint it feeds is
  // ambient rather than something the user acts on.
  //
  // /documents/count, NOT /documents: the listing is gated on the admin
  // `doc-list` capability because it discloses every filename, so reading the
  // count off it 403'd for the executive roles this dock exists for. The count
  // endpoint rides on `doc-search` instead and returns a single integer.
  //
  // Failure stays silent — a user without document search simply gets no line,
  // which is the right outcome and better than an error in a 10px footnote.
  useEffect(() => {
    let cancelled = false;
    bootstrapGet('documentCount', '/api/doc-ingest/documents/count')
      .then(r => {
        const n = Number(r.data?.count);
        if (!cancelled && Number.isFinite(n)) setDocCount(n);
      })
      .catch(() => {});
    return () => { cancelled = true; };
  }, []);

  const searchMode = mode === 'search';

  /** Replay a past search: load the query into search mode and leave history. */
  const replayDocSearch = useCallback((query) => {
    setDocQuery(query);
    setMode('search');
  }, []);

  // The remotes read these off the context they already receive, so the dock
  // needs no new prop channel to drive them.
  // The copilot publishes which model it is answering with, so the dock bar can
  // show it without the shell knowing anything about provider resolution.
  const onModelLabel = useCallback((label) => setModelLabel(label || ''), []);

  const dockContext = useMemo(() => ({
    ...context,
    docSearchQuery: docQuery,
    onReplayDocSearch: replayDocSearch,
    // Tells the copilot to drop its own picker row — the chip below replaces it.
    modelChipInShell: true,
    // ...and to drop its own card border: the dock draws one card around the
    // panel AND the footer bar, so they read as one surface.
    dockEmbedded: true,
    onModelLabel,
  }), [context, docQuery, replayDocSearch, onModelLabel]);

  /** Shared look for the three controls in the dock's bottom bar. */
  const barItemSx = (active) => ({
    display: 'flex', alignItems: 'center', gap: 0.5, flexShrink: 0,
    px: 0.75, py: 0.35, borderRadius: 1.5, cursor: 'pointer', minWidth: 0,
    border: `1px solid ${active ? ORPHOS_TOKENS.status.info.border : ORPHOS_TOKENS.line.strong}`,
    bgcolor: active ? ORPHOS_TOKENS.status.info.bg : 'transparent',
    color: active ? ORPHOS_TOKENS.text.link : ORPHOS_TOKENS.text.bright,
    '&:hover': { borderColor: ORPHOS_TOKENS.status.info.border, color: ORPHOS_TOKENS.text.link },
  });

  return (
    // The dock is the card. The copilot panel inside drops its own border (see
    // its `embedded` prop) so the footer bar below sits INSIDE the same surface
    // instead of floating under a card that ends above it.
    <Box sx={{
      display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0,
      border: `1px solid ${ORPHOS_TOKENS.line.assist}`, borderRadius: 2,
      // The assistant fill, not the dashboard card fill: the design gives Ask
      // Orphos its own violet-shifted surface so it reads as a different kind
      // of thing from the panels it sits beside. The copilot paints its own
      // background down to the hairline above the footer bar, so the bar keeps
      // this tone and the divider reads as the boundary between the
      // conversation and the controls.
      bgcolor: ORPHOS_TOKENS.bg.assist, overflow: 'hidden',
    }}>

      {/* ── Title, only for the shell's own views ──
          The copilot remote draws its own header, so a title here in chat mode
          would show it twice. History and search have no header of their own,
          and a view you navigate into needs a plain way back out. */}
      {mode !== 'copilot' && (
        <Box sx={{
          display: 'flex', alignItems: 'center', gap: 0.5, px: 1, py: 0.5, flexShrink: 0,
        }}>
          <Tooltip title={t('dock.backToCopilot', 'Back to AI Copilot')}>
            <IconButton size="small" onClick={() => setMode('copilot')}
              sx={{ width: 24, height: 24, flexShrink: 0, color: ORPHOS_TOKENS.text.muted,
                    '&:hover': { color: ORPHOS_TOKENS.text.link, bgcolor: ORPHOS_TOKENS.state.hover } }}>
              <ArrowBackIcon sx={{ fontSize: 16 }} />
            </IconButton>
          </Tooltip>
          <Typography fontSize={11.5} fontWeight={700} color="#FFFFFF" noWrap
            sx={{ flex: 1, minWidth: 0 }}>
            {mode === 'history' ? t('dock.history', 'History')
                                : t('dock.docSearch', 'Document search')}
          </Typography>
        </Box>
      )}

      {/* ── Body ── */}
      <Box sx={{ flex: 1, minHeight: 0, overflow: 'hidden' }}>
        {mode === 'history' ? (
          <Box sx={{ height: '100%', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
            <Box sx={{ display: 'flex', gap: 0.75, px: 1, pb: 1, flexShrink: 0 }}>
              {[['ai', t('dock.aiHistory', 'AI chat history')],
                ['doc', t('dock.docHistory', 'Document search')]].map(([key, label]) => (
                <Box key={key} component="button" onClick={() => setHistoryTab(key)}
                  sx={{
                    px: 1, py: 0.4, borderRadius: 1.5, cursor: 'pointer',
                    fontSize: 10.5, fontWeight: 700, fontFamily: 'inherit',
                    color: historyTab === key ? '#FFFFFF' : ORPHOS_TOKENS.text.muted,
                    bgcolor: historyTab === key ? ORPHOS_COLORS.primary : 'transparent',
                    border: `1px solid ${historyTab === key ? ORPHOS_COLORS.primary : ORPHOS_TOKENS.line.strong}`,
                  }}>
                  {label}
                </Box>
              ))}
            </Box>
            {/* overflowX hidden + minWidth 0: the history rows were laid out for
                a full-width tab, where a long session label had room. In the
                dock they are wider than the panel, and without this the list
                scrolls sideways and clips its own text at both edges. The rules
                below give the label something to ellipsise against instead. */}
            <Box sx={{
              flex: 1, minWidth: 0, minHeight: 0,
              overflowY: 'auto', overflowX: 'hidden', px: 1,
              '& .MuiAccordionSummary-content': { minWidth: 0 },
              '& .MuiAccordionSummary-content > *': { minWidth: 0 },
            }}>
              {historyTab === 'ai' ? (
                <PluginLoader pluginId="ai-copilot" slot="copilot-history" context={dockContext} />
              ) : (
                <PluginLoader pluginId="doc-ingest" slot="search-history" context={dockContext} />
              )}
            </Box>
          </Box>
        ) : searchMode ? (
          <Box sx={{ height: '100%', px: 1, pb: 1, minWidth: 0, minHeight: 0, overflowX: 'hidden' }}>
            <PluginLoader pluginId="doc-ingest" slot="dock-search" context={dockContext} />
          </Box>
        ) : (
          <PluginLoader pluginId="ai-copilot" slot="side-panel" context={dockContext} />
        )}
      </Box>

      {/* ── Mode bar, pinned to the foot of the dock ──
          Document search is a labelled switch rather than an icon: which mode
          the panel is in changes what typing into it does, and a two-state
          control that announces its state is the honest way to show that. */}
      <Box sx={{
        display: 'flex', alignItems: 'center', gap: 0.5, flexShrink: 0,
        px: 1, py: 0.75, borderTop: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
      }}>
        <Box role="switch" aria-checked={searchMode}
          onClick={() => setMode(m => (m === 'search' ? 'copilot' : 'search'))}
          sx={{ ...barItemSx(searchMode), pr: 0.25 }}>
          <DescriptionOutlinedIcon sx={{ fontSize: 14, flexShrink: 0 }} />
          <Typography fontSize={11} fontWeight={600} color="inherit" noWrap sx={{ minWidth: 0 }}>
            {t('dock.docSearchShort', 'Documents')}
          </Typography>
          <Switch checked={searchMode} size="small" tabIndex={-1}
            inputProps={{ 'aria-label': t('dock.docSearch', 'Document search') }}
            sx={{ flexShrink: 0, pointerEvents: 'none' }} />
        </Box>

        <Box sx={{ flex: 1, minWidth: 0 }} />

        <Tooltip title={t('dock.historyTip', 'History')}>
          <Box onClick={() => setMode(m => (m === 'history' ? 'copilot' : 'history'))}
            sx={barItemSx(mode === 'history')}>
            <HistoryIcon sx={{ fontSize: 14 }} />
            <Typography fontSize={11} fontWeight={600} color="inherit" noWrap>
              {t('dock.history', 'History')}
            </Typography>
          </Box>
        </Tooltip>

        {/* Model chip. The label is published up by the copilot; clicking asks
            it to open its own provider picker, since provider resolution,
            per-tenant defaults and API keys all live in that remote. Hidden
            until the copilot has reported a model, so it never shows a guess. */}
        {modelLabel && (
          <Tooltip title={t('dock.modelTip', 'Change the answering model')}>
            <Box onClick={() => window.dispatchEvent(new Event('orphos:copilot-open-model'))}
              sx={{ ...barItemSx(false), maxWidth: 130 }}>
              <TuneIcon sx={{ fontSize: 14, flexShrink: 0 }} />
              <Typography fontSize={11} fontWeight={600} color="inherit" noWrap
                sx={{ minWidth: 0 }}>
                {modelLabel}
              </Typography>
            </Box>
          </Tooltip>
        )}
      </Box>

      {/* Corpus hint. The design hangs one dim line under the dock bar naming
          how much the co-pilot is reading from. NOT gated on the Documents
          control: that switches this dock between chat and search, whereas the
          ingested corpus is federated into every answer regardless (see
          RetrievalFederator's DOCUMENT source) — so tying the line to the toggle
          would imply a switch that does not exist. Hidden at zero, where "0
          documents included in answers" is noise rather than information. */}
      {docCount > 0 && (
        <Typography sx={{
          fontSize: 10, color: ORPHOS_TOKENS.text.hint,
          // flexShrink:0 is load-bearing, not tidiness: this is the last child
          // of a height-constrained flex column, so at the default shrink of 1
          // it collapses to zero height and the line silently never appears.
          flexShrink: 0,
          px: 1, pb: 0.75, mt: '-2px',
        }} noWrap>
          {/* `{{n}}` + "(s)", the counting convention already used across this
              codebase — `count` would send i18next looking for _one/_other
              forms that no locale here defines, and Japanese has no plural
              suffix to append anyway. */}
          {t('dock.docsIncluded', '{{n}} ingested document(s) included in answers',
             { n: docCount })}
        </Typography>
      )}
    </Box>
  );
}

export default function App() {
  // Declared before any conditional return (the admin page returns early) so the
  // hook order stays stable across renders.
  const headerRef = useRef(null);
  const headerH   = useHeaderHeight(headerRef);
  const [refreshKey, setRefreshKey]     = useState(0);
  const [tab, setTab]                   = useState(0);
  const [copilotQuery, setCopilotQuery] = useState(null);
  const [trackerCount, setTrackerCount] = useState(0);
  /**
   * Hand a `{ label, query }` to the co-pilot AND move the user to it.
   *
   * The plain setter is enough for callers whose own UI already sits next to
   * the dock, but the dashboard greeting is at the top of the page and its
   * button promises the answer — so the dock has to expand and switch to chat
   * rather than quietly accepting the query behind a collapsed rail.
   */
  const askCopilot = useCallback((q) => {
    setCopilotQuery(q);
    window.dispatchEvent(new Event(COPILOT_FOCUS_EVENT));
  }, []);
  const [forbiddenToast, setForbiddenToast] = useState(false);
  const { t }                           = useTranslation();
  const keycloak                        = useAuth();
  // Read the dashboard layout here in the host, where it is genuinely connected
  // to <DashboardLayoutProvider> (bootstrap.jsx). It is then handed to the
  // widgets remote via the `context` prop below — the widgets remote bundles
  // its own copy of eip-shell-context, so its useDashboardLayout() hook is NOT
  // wired to the host provider and its `save` would be a no-op (toggles/drag
  // did nothing). Passing the live value down mirrors how setCopilotQuery
  // crosses the same boundary.
  const dashboardLayout                 = useDashboardLayout();
  const { isGuest, isAdmin, isPlatformAdmin, isIngestionAdmin, isDataAiAdmin, isSecopsAdmin, availableRoles, role, roleDisplay, activeRole, roles } = useRole();

  // Who may open the Admin section: full admins, platform admins, and the
  // scoped ingestion_admin / data_ai_admin (AdminPage narrows their view to
  // the Document Ingestion / Data & AI group respectively).
  //
  // Stricter gating: admin affordances (the gear, the Admin page) are HIDDEN
  // while the user is "acting as" a non-admin dashboard role (CEO/CTO/CFO/
  // Executive/Viewer) via the role picker. A user who also holds the admin role
  // must switch back to the Admin role to manage settings, so the CEO preview is
  // a true non-admin view. Platform admins (no dashboard) and the scoped
  // ingestion_admin (no dashboard role to preview) are unaffected.
  const actingAsNonAdmin = activeRole && activeRole !== 'admin';
  const canAdmin = isPlatformAdmin || (!actingAsNonAdmin && (isAdmin || isIngestionAdmin || isDataAiAdmin || isSecopsAdmin));

  // Default landing: a full admin opens straight into the Admin section first;
  // the "← Back to Dashboard" control inside AdminPage flips to the dashboard and
  // the header gear returns. Platform admins are always locked into the admin
  // view (handled below); everyone else lands on the dashboard. Lazy init runs
  // once per mount, so a later "Back to Dashboard" choice sticks for the session.
  //
  // A *purely* scoped admin (ingestion_admin / data_ai_admin with NO dashboard
  // role) also opens straight into Admin — they have no dashboard to land on,
  // so dropping them on the empty dashboard and making them hunt for the gear
  // is poor UX. They see only their own group inside AdminPage.
  const scopedAdminOnly = (isIngestionAdmin || isDataAiAdmin || isSecopsAdmin) && !isAdmin && availableRoles.length === 0;
  const [showAdmin, setShowAdmin] = useState(() => (isAdmin || scopedAdminOnly) && !isPlatformAdmin);
  const branding                         = useBranding();
  const theme = useMemo(() => buildTheme(branding?.theme, branding?.profile),
    [branding?.theme, branding?.profile]);

  // The tenant's primary country (Admin → Branding) resolves the zone,
  // currency, notation and default language together. Only the ZONE is threaded
  // through React: language reaches every consumer through i18next, and the
  // currency and notation are read straight off the money/locale singletons at
  // format time, but the greeting and the remotes take a zone as a prop. Blank
  // → the profile's own timezone field, for tenants saved before the country
  // existed.
  const { timezone: countryTz } = useCountry();
  const effectiveTimezone = countryTz || branding?.profile?.timezone || '';

  // If the admin role is revoked while the page is open (token refresh drops
  // the claim, admin manually removed in Azure), drop the admin view on the
  // next render. AdminGuard would also catch this, but resetting state keeps
  // App.jsx's own routing flag in sync with the JWT.
  useEffect(() => {
    if (showAdmin && !canAdmin) setShowAdmin(false);
  }, [canAdmin, showAdmin]);

  // axiosAuth emits this when an /api/admin/** call returns 403. Bounce the
  // user back to the dashboard and surface a non-blocking toast - much better
  // than empty tabs that silently fail to load.
  useEffect(() => {
    const handler = () => {
      setShowAdmin(false);
      setForbiddenToast(true);
    };
    window.addEventListener('orphos:admin-forbidden', handler);
    return () => window.removeEventListener('orphos:admin-forbidden', handler);
  }, []);

  // ── Tab visibility rules ─────────────────────────────────────────
  // Tabs are now driven by platform-registry-service's PluginManifest
  // (fetched via PluginRegistryProvider) instead of a hardcoded array —
  // each active plugin contributes zero or more {key, labelKey, pluginId}
  // entries. "Tasks for Action" + "AI History" stay hidden for Viewer, same
  // restriction as before the Module Federation split; the manifest's own
  // `roles` field isn't consulted yet, since no other consumer wires it.
  const pluginTabs = usePluginTabs();

  // Per-tenant RBAC access map (read-only view for the dashboard). Governs which
  // dashboard sections a role may see. Empty until loaded / on error, so the
  // default (base) visibility below is used when there is no explicit grant.
  const [accessGrants, setAccessGrants] = useState({});
  useEffect(() => {
    let cancelled = false;
    bootstrapGet('accessGrants', '/api/access/grants')
      .then(r => { if (!cancelled) setAccessGrants(toGrantMap(r.data?.grants)); })
      .catch(() => { if (!cancelled) setAccessGrants({}); });
    return () => { cancelled = true; };
  }, []);

  // Explicit allow/deny override for a dashboard section, evaluated against the
  // ACTIVE role (falling back to all held roles for role-less users). Returns
  // true/false to override, or null to keep the section's base visibility.
  const heldRoles = activeRole ? [activeRole] : roles;
  const sectionOverride = (key) => {
    const e = mergeEffect(accessGrants, heldRoles, key, 'user-dashboard');
    return e === 'allow' ? true : e === 'deny' ? false : null;
  };

  // Canonical display order — the registry returns plugins in arbitrary
  // findAll() order, so pin it here. Keys not listed sort after, keeping
  // their registry-relative order.
  // Tasks for Action leads: with the AI Summary tab withdrawn, the queue of
  // what needs a decision is the dashboard's landing view, and it carries the
  // day's calendar alongside it. A key missing from this list sorts last, which
  // is why adding it to the plugin manifest alone left it stranded at the end
  // of the row.
  const TAB_ORDER = ['tasks-for-action', 'notifications', 'decision-log'];
  const tabRank = key => { const i = TAB_ORDER.indexOf(key); return i === -1 ? TAB_ORDER.length : i; };

  // Plugin tabs whose FUNCTION now lives somewhere else in the UI. The plugins
  // stay registered and loaded — only their top-level tab is withdrawn, because
  // the same component is now rendered from its new home:
  //   upcoming-meetings → the calendar rail beside Tasks for Action
  //   doc-search        → the AI Co-pilot dock, behind the document-search toggle
  //   ai-history        → the AI Co-pilot dock, behind the history control
  // Withdrawing the tab here rather than in the PluginManifest keeps this a
  // shell layout decision: no Java rebuild, no per-tenant registry migration,
  // and one line to undo.
  const RELOCATED_TABS = new Set(['upcoming-meetings', 'doc-search', 'ai-history']);

  const visibleTabs = useMemo(() => pluginTabs
    .filter(tab => !RELOCATED_TABS.has(tab.key))
    .filter(tab => {
      // Access map override wins; otherwise fall back to the base rule (Viewer
      // can't see Tasks for Action).
      const ov = sectionOverride(tab.key);
      if (ov !== null) return ov;
      return !(tab.key === 'tasks-for-action' && role === 'Viewer');
    })
    .map(tab => ({ key: tab.key, label: t(`app.${tab.labelKey}`), pluginId: tab.pluginId }))
    .sort((a, b) => tabRank(a.key) - tabRank(b.key)),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [pluginTabs, role, t, accessGrants, activeRole]);

  const trackerTabIdx = visibleTabs.findIndex(tab => tab.key === 'notifications');

  // Collapse state for the tab panel — the artboard's "Panel default" control.
  //
  // Defaulted OPEN, unlike the artboard, which draws this section collapsed.
  // The artboard is making a point about the watchlist being the focus; in the
  // product the panel holds every tab's actual content, and shipping a
  // dashboard whose body starts empty would read as a failed load. The
  // preference is what matters, and a user who wants it shut can now pin it.
  const panelPref = useSectionPref('tabs', true);

  // The greeting band renders this section; the panel only needs to be able to
  // CLOSE it (see openPanel below). Both instances share state through
  // useSectionPref's registry, so collapsing here moves the band on screen.
  const briefPref = useSectionPref('brief', true);

  const panelRef = useRef(null);

  /**
   * Scroll the freshly-opened panel far enough into view to be readable.
   *
   * Deferred a frame: the panel is mid enter-animation when this runs, so its
   * final height is not measurable yet and scrollIntoView would aim at the
   * wrong place. `nearest` rather than `start` — the panel is usually already
   * partly visible, and yanking it to the top of the viewport moves more than
   * the user asked for.
   */
  const revealPanel = useCallback(() => {
    requestAnimationFrame(() => {
      panelRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
    });
  }, []);

  /**
   * Opening the panel minimises the morning brief — the artboard's
   * openTabsPanel().
   *
   * The two sections compete for the same vertical space, and the artboard's
   * point is that you are done with the brief once you have gone looking for a
   * tab. A brief the user has PINNED expanded is left alone: an explicit
   * preference outranks this convenience.
   */
  const openPanel = useCallback(() => {
    if (briefPref.open && briefPref.always !== 'expand') briefPref.toggle();
    revealPanel();
  }, [briefPref, revealPanel]);

  const onPanelToggle = useCallback(() => {
    const opening = !panelPref.open;
    panelPref.toggle();
    if (opening) openPanel();
  }, [panelPref, openPanel]);

  const onPanelAlways = useCallback((value) => {
    const opening = value === 'expand' && !panelPref.open;
    panelPref.setAlways(value);
    if (opening) openPanel();
  }, [panelPref, openPanel]);
  const widgetsPlugin = usePlugin('widgets');

  /**
   * Switch to a tab by key. No-ops when the caller cannot see that tab.
   *
   * Relocated keys resolve to the tab that now hosts them: the remotes still
   * ask for 'upcoming-meetings' (TasksForAction and NotificationsPanel both
   * offer a "view the calendar" affordance), and without this they would have
   * become buttons that silently did nothing once that tab was withdrawn.
   */
  const goToTab = useCallback((key) => {
    const TAB_MOVED_TO = { 'upcoming-meetings': 'tasks-for-action' };
    const target = TAB_MOVED_TO[key] || key;
    const idx = visibleTabs.findIndex(tab => tab.key === target);
    if (idx < 0) return;
    setTab(idx);
    // Every caller of this is asking to SEE something — the header bell, the
    // greeting band's "View full day", a remote's "open the calendar" link. If
    // the panel is collapsed, selecting a tab behind it would look like the
    // click did nothing, so send it to the same open path a click on the strip
    // takes.
    if (!panelPref.open) { panelPref.toggle(); openPanel(); }
  }, [visibleTabs, panelPref, openPanel]);

  // Cross-remote callbacks — WidgetBoard (plugin_widgets), TasksForAction and
  // NotificationsPanel (plugin_email_scanner) all trigger the AI Co-pilot side
  // panel (plugin_ai_copilot). Before the Module Federation split this was
  // plain prop-drilling within one bundle; now it's threaded through the
  // `context` prop every PluginLoader passes to its RemoteApp.
  const pluginContext = useMemo(() => ({
    copilotQuery,
    setCopilotQuery,
    setTrackerCount,
    // Lets a remote move the shell to another tab by key — the calendar
    // highlight strip uses it to open Upcoming Meetings. Tabs are owned by the
    // shell and their INDEX shifts with a user's permissions, so remotes address
    // them by key and the lookup happens here. A key the caller cannot see
    // resolves to nothing rather than jumping to the wrong tab.
    goToTab,
    // Live, host-connected dashboard layout for the widgets remote (see the
    // useDashboardLayout() note above). Recomputes whenever the provider's
    // value changes so toggles/reorders re-render the board.
    dashboardLayout,
    // Tenant time zone (Appearance → Branding). A remote reads its OWN
    // eip-shell-context copy where BrandingProvider's setTenantTimezone() never
    // landed (federation singleton disconnect, same reason dashboardLayout is
    // threaded here), so re-publish it via context for the remote to apply.
    // '' → the remote falls back to the browser zone. The reader's country
    // choice overrides the profile here for the same reason it does in the
    // shell: the co-pilot's own greeting is keyed off this zone.
    tenantTimezone: effectiveTimezone,
    // Tenant theme params (font family + size + colours) so each remote can
    // build+apply the SAME MUI theme via RemoteThemeProvider — the shell's
    // ThemeProvider does not cross the federation boundary.
    themeParams: {
      mode: branding?.theme?.mode,
      colors: branding?.theme?.colors,
      fontFamily: branding?.profile?.fontFamily,
      fontSize: branding?.profile?.fontSize,
    },
  }), [
    copilotQuery, dashboardLayout, goToTab,
    effectiveTimezone,
    branding?.theme?.mode, branding?.theme?.colors,
    branding?.profile?.fontFamily, branding?.profile?.fontSize,
  ]);

  const userFullName = keycloak?.tokenParsed?.name
    || keycloak?.tokenParsed?.preferred_username
    || '';
  const userEmail = keycloak?.tokenParsed?.email || '';

  // Users with no assigned role land on the guest access page — unless they
  // hold an admin capability (e.g. a scoped ingestion_admin with no dashboard
  // role), who must still be able to reach the Admin section.
  if (isGuest && !canAdmin) {
    return (
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <TenantHealthBanner />
        <GuestPage userFullName={userFullName} userEmail={userEmail} />
      </ThemeProvider>
    );
  }

  // Admin page (full-screen, replaces dashboard).
  // The `isAdmin` check here is the first gate; AdminGuard inside AdminPage
  // re-checks on every render; the backend enforces ROLE_admin on every
  // /api/admin/** call. Three layers, all reading the same Keycloak claim.
  // Platform admins are locked into this view — they don't have a dashboard
  // or copilot; their job is cross-tenant admin work only.
  if (isPlatformAdmin || (showAdmin && canAdmin)) {
    return (
      <ThemeProvider theme={theme}>
        <CssBaseline />
        <TenantHealthBanner />
        <AdminPage onBack={() => setShowAdmin(false)} />
        <Snackbar
          open={forbiddenToast}
          autoHideDuration={6000}
          onClose={() => setForbiddenToast(false)}
          anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
        >
          <Alert severity='error' onClose={() => setForbiddenToast(false)}>
            {t('app.admin.denied')}
          </Alert>
        </Snackbar>
      </ThemeProvider>
    );
  }

  // Tenant background image (Branding → Assets → background). Rendered behind
  // the dashboard; the palette background stays as the fallback while the
  // image loads or when no background asset is set.
  const backgroundUrl = brandingAssetUrl(branding?.profile?.backgroundAssetId);
  const brandColors = branding?.theme?.colors || {};

  return (
    <ThemeProvider theme={theme}>
      <CssBaseline />
      {/* Prevent layout shift when the browser scrollbar appears/disappears on tab switch */}
      <GlobalStyles styles={{
        html: { scrollbarGutter: 'stable' },
        // Theme colors exposed as CSS variables so the Branding tab's Custom
        // CSS box can reference them (e.g. `color: var(--orphos-accent)`).
        ':root': {
          '--orphos-primary':    brandColors.primary    || DEFAULT_PALETTE.primary,
          '--orphos-secondary':  brandColors.secondary  || DEFAULT_PALETTE.secondary,
          '--orphos-accent':     brandColors.accent     || '#3DDC97',
          '--orphos-background': brandColors.background || DEFAULT_PALETTE.background,
          '--orphos-text':       brandColors.text       || DEFAULT_PALETTE.text,
        },
      }} />
      <TenantHealthBanner />
      <Box sx={{
        minHeight: '100vh',
        bgcolor: 'background.default',
        ...(backgroundUrl && {
          backgroundImage: `url(${backgroundUrl})`,
          backgroundSize: 'cover',
          backgroundPosition: 'center',
          backgroundAttachment: 'fixed',
        }),
      }}>

        {/* ── Sticky header — stays pinned while content scrolls beneath ── */}
        <Box ref={headerRef} sx={{
          position: 'sticky',
          top: 0,
          zIndex: 1100,
          // Band fill and hairline from the dashboard design. Both are opaque
          // and a clear step off the canvas, so the header still separates from
          // content scrolling under it without a drop shadow. Padding lives in
          // HeaderBar itself — the band is edge-to-edge.
          bgcolor: HEADER_COLORS.band,
          borderBottom: `1px solid ${HEADER_COLORS.line}`,
        }}>
          <HeaderBar
            onRefresh={() => setRefreshKey(k => k + 1)}
            onBellClick={() => setTab(trackerTabIdx)}
            trackerCount={trackerCount}
            onAdminClick={canAdmin ? () => setShowAdmin(true) : null}
          />
        </Box>

        {/* ── Scrollable body ── */}
        <Box sx={{ p: 2 }}>

        {/* ── Two-column body: left (tabs) | right (fixed copilot) ── */}
        <Box sx={{ display: 'flex', gap: 2, alignItems: 'flex-start' }}>

          {/* ── LEFT: WidgetBoard + Tabs + content ──
              A flex COLUMN with at least the viewport's height, which is what
              lets the spacer above the tab strip expand and push the strip to
              the foot of the dashboard when the panel is collapsed. Same height
              expression the co-pilot dock uses, so the two columns agree on
              where the bottom is. The column still GROWS past this when a tab's
              content is long — the page scrolls as it always did, rather than
              trapping content in an inner scroller the way the fixed-height
              artboard card does. */}
          <Box sx={{
            flex: 1, minWidth: 0,
            display: 'flex', flexDirection: 'column',
            minHeight: `calc(100vh - ${headerH + 32}px)`,
          }}>

            <DashboardGreeting
              userFullName={userFullName}
              tenantTimezone={effectiveTimezone}
              onAsk={askCopilot}
              // "View full day" on the next-meeting panel. Routed through
              // goToTab by KEY so it lands wherever the calendar currently
              // lives — today the AI Summary tab's rail, via RELOCATED_TABS.
              onOpenMeetings={() => goToTab('upcoming-meetings')}
              refreshKey={refreshKey}
            />

            {(sectionOverride('widget-board') ?? (role !== 'Viewer')) && widgetsPlugin && (
              <PluginLoader
                key={`widgets-${refreshKey}`}
                pluginId="widgets"
                slot="top-widgets"
                context={pluginContext}
              />
            )}

            {/* ── No-access banner for roles with no tab permissions ── */}
            {visibleTabs.length === 0 && (
              <Box sx={{
                mt: 4, p: 4, textAlign: 'center',
                border: `1px dashed ${ORPHOS_TOKENS.line.strong}`, borderRadius: 3,
                bgcolor: ORPHOS_TOKENS.bg.surface,
              }}>
                <Box sx={{ fontSize: 36, mb: 1 }}>🔒</Box>
                <Box sx={{ fontWeight: 700, fontSize: 15, color: '#FFFFFF', mb: 0.5 }}>
                  {t('app.access.title')}
                </Box>
                <Box sx={{ fontSize: 13, color: ORPHOS_TOKENS.text.muted }}>
                  {t('app.access.body', { role: roleDisplay })}
                </Box>
              </Box>
            )}

            {/* Collapsed, this expands and drives the strip down to the foot of
                the column — the artboard's tabsSpacerFlex. Open, it collapses to
                nothing and the panel takes the room instead. */}
            <Box aria-hidden sx={{
              flex: panelPref.open ? '0 0 0px' : '1 1 auto',
              minHeight: '12px',
              transition: 'flex 220ms ease',
            }} />

            {/* Tab panels — rendered dynamically so indices match visible tabs.
                Every tab is a plugin's; the shell owns none of its own.
                Sits ABOVE the strip; see TabPanel. */}
            <Box ref={panelRef}>
            {panelPref.open && visibleTabs.map((tabDef, idx) => (
              <TabPanel key={tabDef.key} value={tab} index={idx} closing={panelPref.closing}>
                <PluginLoader
                  key={REFRESH_KEYED_TABS.has(tabDef.key) ? `${tabDef.key}-${refreshKey}` : tabDef.key}
                  pluginId={tabDef.pluginId}
                  tabKey={tabDef.key}
                  context={pluginContext}
                />
              </TabPanel>
            ))}
            </Box>

            {/* ── Tab strip — the dock the panel rests on ── */}
            <DashboardTabStrip
              tabs={visibleTabs}
              value={tab}
              onChange={setTab}
              trackerCount={trackerCount}
              pref={panelPref}
              onToggle={onPanelToggle}
              onSetAlways={onPanelAlways}
            />
          </Box>

          {/* ── RIGHT: AI Co-pilot (resizable / collapsible) — hidden for Viewer ── */}
          {(sectionOverride('ask-orphos') ?? (role !== 'Viewer')) && <CopilotDock context={pluginContext} headerH={headerH} />}

        </Box>
        </Box>{/* end scrollable body */}
      </Box>
      <Snackbar
        open={forbiddenToast}
        autoHideDuration={6000}
        onClose={() => setForbiddenToast(false)}
        anchorOrigin={{ vertical: 'bottom', horizontal: 'center' }}
      >
        <Alert severity='error' onClose={() => setForbiddenToast(false)}>
          {t('app.admin.denied')}
        </Alert>
      </Snackbar>
    </ThemeProvider>
  );
}
