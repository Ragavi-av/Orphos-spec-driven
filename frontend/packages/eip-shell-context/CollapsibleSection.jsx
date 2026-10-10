import { useState } from 'react';
import { Accordion, AccordionSummary, AccordionDetails, Box, Typography, Chip } from '@mui/material';
import ExpandMoreIcon from '@mui/icons-material/ExpandMore';

/**
 * CollapsibleSection — the platform's one consistent collapse/expand wrapper for
 * lists and grouped sections. Shows a summary header (icon + title + item count
 * + optional summary text + right-aligned actions) and reveals the body when
 * expanded.
 *
 * Default-open rule (platform-wide): a section auto-collapses when it holds MORE
 * THAN `threshold` items (default 3); ≤ threshold (or an unknown count) stays
 * open. Pass `defaultExpanded` to override. State is per-session (not persisted);
 * children stay mounted so table scroll / in-progress edits survive a collapse.
 *
 * Usage:
 *   <CollapsibleSection title="Users" count={users.length}
 *       actions={<Button onClick={add}>Add user</Button>}>
 *     <Table>…</Table>
 *   </CollapsibleSection>
 */
export function collapseByCount(count, threshold = 3) {
  return count == null ? true : count <= threshold;
}

export default function CollapsibleSection({
  title,
  icon,
  count,
  summary,
  actions,
  defaultExpanded,
  threshold = 3,
  children,
  sx,
  detailsSx,
}) {
  const [expanded, setExpanded] = useState(
    defaultExpanded != null ? defaultExpanded : collapseByCount(count, threshold),
  );

  return (
    <Accordion
      expanded={expanded}
      onChange={(_e, v) => setExpanded(v)}
      elevation={0}
      disableGutters
      sx={{
        border: '1px solid #243A72',
        borderRadius: '8px !important',
        '&:before': { display: 'none' },
        overflow: 'hidden',
        ...sx,
      }}
    >
      <AccordionSummary expandIcon={<ExpandMoreIcon sx={{ fontSize: 20, color: '#7D92C4' }} />}
        sx={{ minHeight: 48, bgcolor: '#0F2450',
          '& .MuiAccordionSummary-content': { alignItems: 'center', gap: 1, my: 1, mr: 1 } }}>
        {icon}
        <Typography fontSize={14} fontWeight={700} color='#FFFFFF'>{title}</Typography>
        {count != null && (
          <Chip size='small' label={count}
            sx={{ height: 18, fontSize: 10, fontWeight: 700, bgcolor: 'rgba(91, 157, 255, 0.14)', color: '#8DBCFF' }} />
        )}
        {summary != null && (
          <Box sx={{ ml: 0.5, display: 'flex', alignItems: 'center',
            fontSize: 11, color: '#7D92C4' }}>{summary}</Box>
        )}
        {actions != null && (
          // Keep header actions clickable without toggling the section.
          <Box sx={{ ml: 'auto', display: 'flex', alignItems: 'center', gap: 1 }}
            onClick={e => e.stopPropagation()}>
            {actions}
          </Box>
        )}
      </AccordionSummary>
      <AccordionDetails sx={{ p: 1.5, ...detailsSx }}>
        {children}
      </AccordionDetails>
    </Accordion>
  );
}
