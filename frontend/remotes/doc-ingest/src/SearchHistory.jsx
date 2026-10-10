import { useState, useEffect, useCallback } from 'react';
import { Box, Typography, CircularProgress, IconButton, Tooltip } from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import DeleteOutlineIcon from '@mui/icons-material/DeleteOutline';
import { useTranslation } from 'react-i18next';
import axios from 'axios';

const API = '/api/doc-ingest/me/search/history';

/** Group entries under Today / Yesterday / an explicit date, newest group first. */
function groupByDate(entries, t) {
  const groups = new Map();
  const today = new Date().toDateString();
  const yesterday = new Date(Date.now() - 86400000).toDateString();

  for (const e of entries) {
    const d = new Date(e.timestamp);
    const day = isNaN(d.getTime()) ? '' : d.toDateString();
    const label = day === today ? t('history.today', 'Today')
                : day === yesterday ? t('history.yesterday', 'Yesterday')
                : isNaN(d.getTime()) ? t('history.earlier', 'Earlier')
                : d.toLocaleDateString(undefined, { day: 'numeric', month: 'long', year: 'numeric' });
    if (!groups.has(label)) groups.set(label, []);
    groups.get(label).push(e);
  }
  return [...groups.entries()];
}

/**
 * The signed-in user's own document searches.
 *
 * Reads /api/doc-ingest/me/search/history — the per-user endpoint, NOT the
 * admin one, which is tenant-wide and would show colleagues' queries. Rows are
 * clickable: picking one replays that search in the dock.
 *
 * @param {function} [onPick] replay a past query, (query: string) => void
 */
export default function SearchHistory({ onPick }) {
  const { t } = useTranslation();
  const [entries, setEntries] = useState(null);   // null = loading
  const [failed, setFailed] = useState(false);

  const refresh = useCallback(() => {
    axios.get(API, { params: { limit: 50 } })
      .then(r => { setEntries(Array.isArray(r.data) ? r.data : []); setFailed(false); })
      .catch(() => { setEntries([]); setFailed(true); });
  }, []);

  useEffect(() => { refresh(); }, [refresh]);

  const remove = (searchId) => {
    // Optimistic: the row disappears at once and a failed delete is repaired by
    // the refresh, matching how CopilotHistory treats its own deletes.
    setEntries(prev => (prev || []).filter(e => e.searchId !== searchId));
    axios.delete(`${API}/${encodeURIComponent(searchId)}`).catch(refresh);
  };

  const clearAll = () => {
    setEntries([]);
    axios.delete(API).catch(refresh);
  };

  if (entries === null) {
    return (
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 1, py: 3 }}>
        <CircularProgress size={14} />
        <Typography fontSize={12} color="#7D92C4">
          {t('history.loading', 'Loading history…')}
        </Typography>
      </Box>
    );
  }

  if (failed) {
    return (
      <Typography fontSize={12} color="#7D92C4" sx={{ py: 3 }}>
        {t('history.docLoadError', 'Could not load your search history.')}
      </Typography>
    );
  }

  if (entries.length === 0) {
    return (
      <Box sx={{ py: 6, textAlign: 'center' }}>
        <SearchIcon sx={{ fontSize: 32, color: '#7D92C4', mb: 1 }} />
        <Typography fontSize={13} fontWeight={600} color="#C3D3F2">
          {t('history.docEmptyTitle', 'No document searches yet')}
        </Typography>
        <Typography fontSize={11.5} color="#7D92C4" mt={0.5}>
          {t('history.docEmptyBody', 'Searches you run in document mode appear here.')}
        </Typography>
      </Box>
    );
  }

  return (
    <Box>
      <Box sx={{ display: 'flex', alignItems: 'center', mb: 1 }}>
        <Typography fontSize={11.5} color="#C3D3F2">
          {t('history.docSummary', '{{n}} search(es)', { n: entries.length })}
        </Typography>
        <Box sx={{ flex: 1 }} />
        <Tooltip title={t('history.clearAll', 'Clear all history')}>
          <IconButton size="small" onClick={clearAll}
            sx={{ color: '#7D92C4', '&:hover': { color: '#FF6B81' } }}>
            <DeleteOutlineIcon sx={{ fontSize: 16 }} />
          </IconButton>
        </Tooltip>
      </Box>

      {groupByDate(entries, t).map(([label, rows]) => (
        <Box key={label} sx={{ mb: 2 }}>
          <Typography fontSize={10} fontWeight={700} color="#7D92C4"
            sx={{ textTransform: 'uppercase', letterSpacing: 0.8, mb: 0.75 }}>
            {label}
          </Typography>

          {rows.map(e => (
            <Box key={e.searchId}
              onClick={() => onPick && onPick(e.query)}
              sx={{
                display: 'flex', alignItems: 'flex-start', gap: 1,
                px: 1.25, py: 0.9, mb: 0.5, borderRadius: 1.5,
                border: '1px solid #243A72',
                cursor: onPick ? 'pointer' : 'default',
                '&:hover': onPick ? { bgcolor: 'rgba(91, 157, 255, 0.10)' } : undefined,
              }}>
              <Box sx={{ flex: 1, minWidth: 0 }}>
                <Typography fontSize={12.5} color="#FFFFFF" sx={{ wordBreak: 'break-word' }}>
                  {e.query}
                </Typography>
                <Typography fontSize={10.5} color="#7D92C4" sx={{ mt: 0.2 }}>
                  {t('history.docMeta', '{{n}} result(s)', { n: e.resultCount })}
                  {' · '}
                  {new Date(e.timestamp).toLocaleTimeString(undefined,
                    { hour: '2-digit', minute: '2-digit' })}
                </Typography>
              </Box>
              <IconButton size="small"
                onClick={(ev) => { ev.stopPropagation(); remove(e.searchId); }}
                sx={{ color: '#7D92C4', '&:hover': { color: '#FF6B81' } }}>
                <DeleteOutlineIcon sx={{ fontSize: 14 }} />
              </IconButton>
            </Box>
          ))}
        </Box>
      ))}
    </Box>
  );
}
