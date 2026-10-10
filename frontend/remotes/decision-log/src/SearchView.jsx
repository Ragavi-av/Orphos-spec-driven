import { useCallback, useEffect, useState } from 'react';
import {
  Box, Paper, Stack, TextField, MenuItem, Chip, Typography, CircularProgress, Alert,
  List, Divider, InputAdornment, IconButton, Button,
} from '@mui/material';
import SearchIcon from '@mui/icons-material/Search';
import { useTranslation } from 'react-i18next';
import { searchDecisions } from './api';
import { DecisionRow } from './LogView';

const MODES = ['HYBRID', 'KEYWORD', 'SEMANTIC'];

/**
 * Decision_Search_Engine UI (Req 6): keyword / semantic / hybrid search with
 * faceted filters, highlighted snippets and result relevance.
 */
export default function SearchView({ onSelect }) {
  const { t } = useTranslation();
  const [query, setQuery] = useState('');
  const [mode, setMode] = useState('HYBRID');
  const [filters, setFilters] = useState({});
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  const run = useCallback((overrides = {}) => {
    setLoading(true);
    const body = {
      query,
      mode,
      limit: 50,
      filters: {
        statuses: filters.status ? [filters.status] : undefined,
        domains: filters.domain ? [filters.domain] : undefined,
        priorities: filters.priority ? [filters.priority] : undefined,
        detectionSources: filters.source ? [filters.source] : undefined,
        dateFrom: filters.dateFrom || undefined,
        dateTo: filters.dateTo || undefined,
        person: filters.person || undefined,
      },
      ...overrides,
    };
    searchDecisions(body)
      .then(data => { setResult(data); setError(null); })
      .catch(() => setError(t('decisionLog.loadError')))
      .finally(() => setLoading(false));
  }, [query, mode, filters, t]);

  useEffect(() => { run(); /* initial facet-only browse */ }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const facet = (name) => result?.facets?.[name] || {};
  const setFilter = (key, value) => setFilters(f => ({ ...f, [key]: value || undefined }));

  return (
    <Box>
      <Paper sx={{ p: 2, mb: 2 }}>
        <Stack direction="row" spacing={1} alignItems="center" flexWrap="wrap" useFlexGap>
          <TextField
            fullWidth size="small" sx={{ flex: '1 1 340px' }}
            placeholder={t('decisionLog.search.placeholder')}
            value={query}
            onChange={e => setQuery(e.target.value)}
            onKeyDown={e => { if (e.key === 'Enter') run(); }}
            InputProps={{
              endAdornment: (
                <InputAdornment position="end">
                  <IconButton onClick={() => run()} edge="end"><SearchIcon /></IconButton>
                </InputAdornment>
              ),
            }}
          />
          <TextField select size="small" sx={{ minWidth: 150 }} label={t('decisionLog.search.mode')}
            value={mode} onChange={e => setMode(e.target.value)}>
            {MODES.map(m => (
              <MenuItem key={m} value={m}>{t(`decisionLog.search.${m.toLowerCase()}`)}</MenuItem>
            ))}
          </TextField>
        </Stack>
        <Stack direction="row" spacing={1} mt={1.5} flexWrap="wrap" useFlexGap>
          <FacetSelect label={t('decisionLog.fields.status')} value={filters.status}
            options={Object.keys(facet('status'))} counts={facet('status')}
            onChange={v => setFilter('status', v)} />
          <FacetSelect label={t('decisionLog.fields.domain')} value={filters.domain}
            options={Object.keys(facet('domain'))} counts={facet('domain')}
            onChange={v => setFilter('domain', v)} />
          <FacetSelect label={t('decisionLog.fields.priority')} value={filters.priority}
            options={Object.keys(facet('priorityLevel'))} counts={facet('priorityLevel')}
            onChange={v => setFilter('priority', v)} />
          <FacetSelect label={t('decisionLog.fields.source')} value={filters.source}
            options={Object.keys(facet('detectionSource'))} counts={facet('detectionSource')}
            onChange={v => setFilter('source', v)} />
          <TextField size="small" type="date" label={t('decisionLog.search.from')} sx={{ width: 160 }}
            InputLabelProps={{ shrink: true }} value={filters.dateFrom || ''}
            onChange={e => setFilter('dateFrom', e.target.value)} />
          <TextField size="small" type="date" label={t('decisionLog.search.to')} sx={{ width: 160 }}
            InputLabelProps={{ shrink: true }} value={filters.dateTo || ''}
            onChange={e => setFilter('dateTo', e.target.value)} />
          <TextField size="small" label={t('decisionLog.search.person')} sx={{ width: 160 }}
            value={filters.person || ''} onChange={e => setFilter('person', e.target.value)} />
          <Button size="small" onClick={() => { setFilters({}); setQuery(''); }}>
            {t('decisionLog.search.clear')}
          </Button>
          <Button size="small" variant="contained" onClick={() => run()}>{t('decisionLog.tabs.search')}</Button>
        </Stack>
      </Paper>

      {loading && <CircularProgress />}
      {error && <Alert severity="warning">{error}</Alert>}
      {!loading && !error && result && (
        <>
          <Typography variant="caption" color="text.secondary">
            {t('decisionLog.search.results', { n: result.total })} · {result.mode}
          </Typography>
          <Paper sx={{ mt: 1 }}>
            <List disablePadding>
              {(result.results || []).length === 0 && (
                <Typography p={2} color="text.secondary">{t('decisionLog.search.noResults')}</Typography>
              )}
              {(result.results || []).map((d, i) => (
                <Box key={d.id || i}>
                  <DecisionRow
                    d={d} t={t} onSelect={onSelect}
                    secondaryOverride={
                      <>
                        {d.snippet && (
                          <Typography variant="body2" component="span" display="block">
                            <Highlight text={d.snippet} term={query} />
                          </Typography>
                        )}
                        <Typography variant="caption" color="text.secondary">
                          {[d.domain, d.decidedBy, d.decidedAt?.slice(0, 10),
                            typeof d.relevance === 'number' ? `relevance ${d.relevance}` : null]
                            .filter(Boolean).join(' · ')}
                        </Typography>
                      </>
                    }
                  />
                  {i < result.results.length - 1 && <Divider component="li" />}
                </Box>
              ))}
            </List>
          </Paper>
        </>
      )}
    </Box>
  );
}

function FacetSelect({ label, value, options, counts, onChange }) {
  return (
    <TextField select size="small" sx={{ minWidth: 150 }} label={label}
      value={value || ''} onChange={e => onChange(e.target.value)}>
      <MenuItem value="">—</MenuItem>
      {options.map(o => (
        <MenuItem key={o} value={o}>
          <Stack direction="row" justifyContent="space-between" width="100%" gap={1}>
            <span>{o}</span>
            <Chip size="small" label={counts[o]} />
          </Stack>
        </MenuItem>
      ))}
    </TextField>
  );
}

/** Highlights the search term inside a snippet (Req 6.5). */
function Highlight({ text, term }) {
  if (!term || !text) return text || null;
  const idx = text.toLowerCase().indexOf(term.toLowerCase());
  if (idx < 0) return text;
  return (
    <>
      {text.slice(0, idx)}
      <mark>{text.slice(idx, idx + term.length)}</mark>
      {text.slice(idx + term.length)}
    </>
  );
}
