import { useState } from 'react';
import { Box, Typography, Tooltip } from '@mui/material';
import { useTranslation } from 'react-i18next';

// Shared renderer for the nl-query /ask trust envelope, used by every screen
// that shows an AI answer (AI Co-pilot, RAG Search). Displays a grounding band,
// a confidence percentage, the self-check verdict, and expandable caveats +
// provenance sources. Bilingual via the copilot.trust.* keys in i18n.js.
//
// Renders nothing when `trust` is absent (e.g. a scope decline carries none).

const GROUNDING = {
  GROUNDED:    { key: 'grounded',    color: '#3DDC97', bg: 'rgba(61, 220, 151, 0.18)',    border: 'rgba(61, 220, 151, 0.42)' },
  PARTIAL:     { key: 'partial',     color: '#FFB84D', bg: 'rgba(255, 184, 77, 0.18)',  border: 'rgba(255, 184, 77, 0.45)' },
  SYNTHESIZED: { key: 'synthesized', color: '#C3D3F2', bg: '#243A72', border: '#243A72' },
};

// Set either to `false` to hide that signal — e.g. for a scripted demo where a
// low score against sparse sample data reads as a broken product rather than an
// appropriately cautious one. Both default to ON: an evaluator should see the
// system qualify its own answers, and hiding the qualification is the more
// misleading of the two options.
const SHOW_CONFIDENCE  = true;
const SHOW_VERIFICATION = true;

export default function TrustBadge({ trust }) {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  if (!trust) return null;

  const g = GROUNDING[trust.grounding] || GROUNDING.SYNTHESIZED;
  const pct = Math.round((trust.confidence ?? 0) * 100);
  const caveats = Array.isArray(trust.caveats) ? trust.caveats : [];
  const sources = Array.isArray(trust.sources) ? trust.sources : [];
  const hasDetail = caveats.length > 0 || sources.length > 0;

  return (
    <Box sx={{ mt: 0.75 }}>
      <Box sx={{ display: 'flex', alignItems: 'center', gap: 0.75, flexWrap: 'wrap' }}>
        <Box sx={{ px: 0.9, py: 0.3, borderRadius: 1, bgcolor: g.bg, border: `1px solid ${g.border}` }}>
          <Typography component="span" fontSize={10} fontWeight={700} color={g.color}>
            {t(`copilot.trust.${g.key}`)}
          </Typography>
        </Box>
        {SHOW_CONFIDENCE && (
          <Tooltip title={t('copilot.trust.confidenceTip')}>
            <Typography component="span" fontSize={10} fontWeight={600} color="#C3D3F2" sx={{ cursor: 'help' }}>
              {t('copilot.trust.confidence', { pct })}
            </Typography>
          </Tooltip>
        )}
        {SHOW_VERIFICATION && trust.verification === 'FLAGGED' && (
          <Typography component="span" fontSize={10} fontWeight={600} color="#FFB84D">
            {t('copilot.trust.flagged')}
          </Typography>
        )}
        {SHOW_VERIFICATION && trust.verification === 'PASSED' && (
          <Typography component="span" fontSize={10} fontWeight={600} color="#3DDC97">
            {t('copilot.trust.verified')}
          </Typography>
        )}
        {hasDetail && (
          <Typography component="span" fontSize={10} color="#8DBCFF" sx={{ cursor: 'pointer' }}
            onClick={() => setOpen(o => !o)}>
            {open
              ? t('copilot.trust.hide')
              : (sources.length
                  ? t('copilot.trust.detailsWithSources', { n: sources.length })
                  : t('copilot.trust.details'))}
          </Typography>
        )}
      </Box>
      {open && hasDetail && (
        <Box sx={{ mt: 0.5, pl: 1, borderLeft: '2px solid #243A72' }}>
          {caveats.map((c, i) => (
            <Typography key={i} fontSize={10} color="#FFB84D" lineHeight={1.6}>• {c}</Typography>
          ))}
          {sources.length > 0 && (
            <Typography fontSize={10} color="#C3D3F2" mt={caveats.length ? 0.5 : 0} lineHeight={1.6}>
              {t('copilot.trust.sources')}{' '}
              {sources.map(s => `${s.type || 'context'}${s.id ? ` ${s.id}` : ''} (${Math.round((s.relevance ?? 0) * 100)}%)`).join(', ')}
            </Typography>
          )}
        </Box>
      )}
    </Box>
  );
}
