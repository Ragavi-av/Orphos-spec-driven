import { useState, useCallback, useRef, useEffect } from 'react';
import {
  Box, Typography, TextField, Button, Alert, CircularProgress,
} from '@mui/material';
import SearchIcon  from '@mui/icons-material/Search';
import { useTranslation } from 'react-i18next';
import axios from 'axios';

// Relevance band → tone. Flattening the card left the score as text only, so
// this returns the one colour it needs rather than the former bar/label/text
// trio that all carried the same value anyway.
const SCORE_TONE = (s) => {
  if (s >= 0.80) return '#3DDC97';
  if (s >= 0.65) return '#8DBCFF';
  if (s >= 0.50) return '#FFB84D';
  return '#C3D3F2';
};

/**
 * One retrieved passage, drawn the way the design canvas draws a document hit:
 * a single flat bordered card of three lines — [kind badge] title, a metadata
 * line, then the passage itself. The previous card banded the same content into
 * a header strip, a score rail, a body and a footer strip, which spent four
 * surface changes on what the canvas states in one.
 *
 * Nothing is dropped in the flattening — match score, reference number, page,
 * chunk and document id all move into the metadata line, where the canvas puts
 * that class of information.
 */
function ResultCard({ result, compact, surfaces }) {
  const tone = SCORE_TONE(result.score);
  const pct = Math.round(result.score * 100);
  const docLabel = result.title || result.fileName || result.documentId?.slice(0, 8) + '…';

  // The canvas's metadata line is one run of dim text with "·" between facts.
  const meta = [
    result.referenceNumber,
    result.pageNumber > 0 ? `Page ${result.pageNumber}` : null,
    `Chunk ${result.chunkIndex + 1}`,
    !compact && result.documentId ? `doc: ${result.documentId.slice(0, 8)}…` : null,
  ].filter(Boolean);

  return (
    <Box sx={{
      bgcolor: surfaces.card, border: `1px solid ${surfaces.line}`,
      borderRadius: '10px', px: '11px', py: '9px',
    }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: '7px' }}>
        {result.documentType && (
          <Box sx={{
            fontSize: 9, fontWeight: 700, letterSpacing: '0.5px', flexShrink: 0,
            color: '#9DC0F5', bgcolor: '#123A6B', border: '1px solid #245C9E',
            borderRadius: '4px', px: '5px', py: '1px',
          }}>
            {result.documentType}
          </Box>
        )}
        <Typography fontSize={12.5} color='#DBE6FF' sx={{ minWidth: 0, flex: 1,
          overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
          {docLabel}
        </Typography>
        {/* Relevance keeps its tone — it is the one number here worth reading
            at a glance, and the canvas has no equivalent to borrow. */}
        <Typography fontSize={10.5} fontWeight={700} color={tone} sx={{ flexShrink: 0 }}>
          {pct}%
        </Typography>
      </Box>

      {meta.length > 0 && (
        <Typography fontSize={10.5} color='#4D6299' mt='4px' noWrap>
          {meta.join(' · ')}
        </Typography>
      )}

      <Typography fontSize={11} color='#A8BBE6' mt='5px' lineHeight={1.45}
        sx={{
          whiteSpace: 'pre-wrap', wordBreak: 'break-word', textWrap: 'pretty',
          // Long passages would push the next citation off a dock-height
          // panel; the full text stays available in the full-width view.
          ...(compact && {
            display: '-webkit-box', WebkitLineClamp: 6, WebkitBoxOrient: 'vertical',
            overflow: 'hidden',
          }),
        }}>
        {result.text}
      </Typography>
    </Box>
  );
}

/**
 * Document search over ingested passages.
 *
 * @param {boolean} [compact]      dock layout: full-bleed, denser cards, clamped excerpts
 * @param {string}  [initialQuery] run this search on mount / when it changes —
 *                                 how the history panel replays a past search
 */
export default function DocSearch({ compact = false, initialQuery = '' }) {
  const { t } = useTranslation();
  const [query,   setQuery]   = useState(initialQuery);
  const [results, setResults] = useState(null);   // null = initial, [] = no results
  const [loading, setLoading] = useState(false);
  const [error,   setError]   = useState(null);
  const inputRef = useRef(null);

  const search = useCallback(async (q = query) => {
    const trimmed = q.trim();
    if (!trimmed) return;
    setLoading(true); setError(null);
    try {
      const r = await axios.post('/api/doc-ingest/search', { query: trimmed, limit: 10 });
      setResults(Array.isArray(r.data?.results) ? r.data.results : []);
    } catch (e) {
      setError(e?.response?.data?.message || 'Search failed. Is the doc-ingest service running?');
      setResults(null);
    }
    setLoading(false);
  }, [query]);

  // Replaying a search from the history panel: the query arrives as a prop, so
  // it has to drive both the field and the request. Keyed on the value, not on
  // `search`, which changes identity on every keystroke.
  useEffect(() => {
    if (!initialQuery) return;
    setQuery(initialQuery);
    search(initialQuery);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [initialQuery]);

  const onKey = (e) => { if (e.key === 'Enter') search(); };

  // The canvas draws document search INSIDE the Ask Orphos panel, so in the dock
  // this takes that panel's surface family (#101A3E on #2C3A72) and reads as
  // part of the co-pilot. As its own full-width tab it belongs to the dashboard
  // instead, so it takes the dashboard's nested-panel pair.
  const surfaces = compact
    ? { card: '#101A3E', line: '#2C3A72' }
    : { card: '#0F2450', line: '#2E4A8C' };

  return (
    <Box sx={compact
      ? { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }
      : { maxWidth: 820, mx: 'auto', py: 2 }}>

      {/* Search bar — the canvas draws this as ONE framed field with the
          magnifier sitting inside it, not a field beside a detached button.
          The submit control stays (the canvas's own composer keeps its send
          button inside the same frame), so Enter and click both still work. */}
      <Box sx={{
        display: 'flex', alignItems: 'center', gap: '8px', flexShrink: 0,
        mb: compact ? 1.5 : 2.5,
        bgcolor: surfaces.card, border: `1px solid ${surfaces.line}`,
        borderRadius: '10px', pl: '10px', pr: '6px', py: '7px',
      }}>
        <SearchIcon sx={{ fontSize: 15, color: '#7D92C4', flexShrink: 0 }} />
        <TextField
          inputRef={inputRef}
          fullWidth variant='standard'
          placeholder={compact
            ? t('docSearch.placeholderShort', 'Search documents…')
            : t('docSearch.placeholder',
                'Search your documents… (e.g. "data retention policy" or "Q3 revenue targets")')}
          value={query}
          onChange={e => setQuery(e.target.value)}
          onKeyDown={onKey}
          disabled={loading}
          InputProps={{ disableUnderline: true }}
          sx={{ '& .MuiInput-input': { fontSize: 12, color: '#EAF0FF', p: 0 },
                '& .MuiInput-input::placeholder': { color: '#7D92C4', opacity: 1 } }}
        />
        {/* Same button the canvas puts inside the Ask Orphos composer, down to
            its disabled tone — MUI's default disabled contained button greys
            out to a colour that is not in this palette at all, which is what
            made an empty field look broken rather than merely idle. */}
        <Button variant='contained' size='small'
          onClick={() => search()} disabled={loading || !query.trim()}
          sx={{
            textTransform: 'none', fontSize: 11.5, fontWeight: 600,
            px: compact ? '9px' : '13px', py: '4px', minWidth: 0, flexShrink: 0,
            borderRadius: '8px', boxShadow: 'none',
            bgcolor: '#2F6FF0', color: '#FFFFFF',
            '&:hover': { bgcolor: '#5B9DFF', boxShadow: 'none' },
            '&.Mui-disabled': { bgcolor: '#243A72', color: '#7D92C4' },
          }}>
          {compact ? <SearchIcon sx={{ fontSize: 16 }} /> : t('docSearch.search', 'Search')}
        </Button>
      </Box>

      {/* Scroll region — in the dock the results scroll, not the whole panel */}
      <Box sx={compact ? { flex: 1, minHeight: 0, overflowY: 'auto', pr: 0.5 } : undefined}>

      {error && (
        <Alert severity='error' sx={{ mb: 2, fontSize: 13 }} onClose={() => setError(null)}>
          {error}
        </Alert>
      )}

      {loading && (
        <Box sx={{ p: compact ? 3 : 6, textAlign: 'center' }}>
          <CircularProgress size={compact ? 20 : 28} />
          <Typography fontSize={12} color='#7D92C4' mt={1.5}>
            {t('docSearch.searching', 'Searching documents…')}
          </Typography>
        </Box>
      )}

      {/* Initial empty state. The canvas draws NO placeholder card here — the
          field simply sits above an empty list until you type. A bordered box
          with a 28px icon reads as a result, which is the one thing it is not,
          and in the dock it was the largest object on screen while saying the
          least. Reduced to the canvas's quiet hint tone. */}
      {!loading && results === null && !error && (
        <Typography fontSize={compact ? 11 : 12} color='#4D6299'
          sx={{ px: '2px', maxWidth: 420, lineHeight: 1.45 }}>
          {compact
            ? t('docSearch.emptyHint', 'Type a question or keywords to search your documents.')
            /* One key, not two: the two sentences render as a single paragraph,
               and splitting them would let a translation reorder only half. */
            : t('docSearch.emptyBody',
                'Enter a question or keywords to find relevant passages across all ingested documents. '
                + 'Each result shows the source document, page, and a relevance score.')}
        </Typography>
      )}

      {/* No results */}
      {!loading && results !== null && results.length === 0 && (
        <Typography fontSize={compact ? 11 : 12} color='#7D92C4' sx={{ px: '2px', lineHeight: 1.45 }}>
          {t('docSearch.noResults',
             'No matching passages found. Try different keywords or upload more documents.')}
        </Typography>
      )}

      {/* Results */}
      {!loading && results !== null && results.length > 0 && (
        <>
          <Box sx={{ display: 'flex', alignItems: 'center', mb: 1.5, gap: 1 }}>
            {/* Counted through i18next rather than an inline "s" — that suffix
                is English grammar, and Japanese has no plural form to append. */}
            <Typography fontSize={12} color='#C3D3F2'>
              {t('docSearch.passagesFound', '{{n}} passage(s) found', { n: results.length })}
            </Typography>
            <Box sx={{ flex: 1 }} />
            {!compact && (
              <Typography fontSize={11} color='#7D92C4'>
                {t('docSearch.sortedByRelevance', 'sorted by relevance')}
              </Typography>
            )}
          </Box>
          <Box sx={{ display: 'flex', flexDirection: 'column', gap: '7px' }}>
            {results.map((r, i) => (
              <ResultCard key={i} result={r} compact={compact} surfaces={surfaces} />
            ))}
          </Box>
        </>
      )}
      </Box>
    </Box>
  );
}
