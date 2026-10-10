import { Box, Tabs, Tab } from '@mui/material';
import { useTranslation } from 'react-i18next';
import { ORPHOS_TOKENS, ORPHOS_COLORS } from 'eip-shell-context/orphosTheme';
import SectionToggle from 'eip-shell-context/SectionToggle';

/**
 * The dashboard's tab strip — the dock the tab panel rests on.
 *
 * Sits BELOW its panel, not above it. The v3 JP artboard draws the panel with
 * `border-radius:12px 12px 0 0` and `border-bottom:none`, which is chrome that
 * only makes sense with a strip closing the shape off underneath. The payoff is
 * the collapsed state: with the panel gone the strip parks at the foot of the
 * dashboard as a standalone bar, instead of leaving a row of headers hanging
 * over nothing.
 *
 * The strip owns all the chrome, so a tab is just a pill that fills blue when
 * selected — no folder-tab geometry, no underline indicator (the theme zeroes
 * it). Two states:
 *
 *   open       deep fill, top corners squared off, flush against the panel
 *   collapsed  card fill, rounded all round, 20px of air above it
 *
 * MUI Tabs rather than plain buttons, despite the artboard drawing buttons:
 * roving-tabindex keyboard nav, the scrollable overflow behaviour and the
 * tab/tablist roles all come free, and none of it is visible in a static
 * mockup. The pill look is applied over the top.
 *
 * @param {Array}    tabs         [{ key, label }] already filtered by permission
 * @param {number}   value        selected index
 * @param {function} onChange     (index) => void
 * @param {number}   trackerCount unread count badged on the notifications tab
 * @param {object}   pref         useSectionPref('tabs') state — open/always
 * @param {function} onToggle     collapse/expand the panel now
 * @param {function} onSetAlways  pin a default
 */
export default function DashboardTabStrip({
  tabs, value, onChange, trackerCount = 0, pref, onToggle, onSetAlways,
}) {
  const { t } = useTranslation();
  if (!tabs || tabs.length === 0) return null;

  return (
    <Box sx={{
      flex: '0 0 auto',
      display: 'flex', alignItems: 'center', gap: '4px',
      mt: pref.open ? 0 : '20px',
      p: '4px 7px',
      bgcolor: pref.open ? ORPHOS_TOKENS.bg.deep : ORPHOS_TOKENS.bg.surface,
      border: `1px solid ${ORPHOS_TOKENS.line.subtle}`,
      borderRadius: pref.open ? '0 0 12px 12px' : '12px',
      transition: 'background-color 220ms ease, border-radius 220ms ease, margin 220ms ease',
    }}>
      <Tabs
        value={value}
        onChange={(_, v) => onChange(v)}
        variant='scrollable'
        scrollButtons='auto'
        sx={{
          flex: 1, minHeight: 32,
          '& .MuiTabs-flexContainer': { gap: '4px' },
          '& .MuiTab-root': {
            minHeight: 32, py: '6px', px: '11px',
            fontSize: 13.5, fontWeight: 600, textTransform: 'none',
            borderRadius: '8px',
            color: ORPHOS_TOKENS.text.muted,
            '&:hover': { color: ORPHOS_TOKENS.text.bright },
            '&.Mui-selected': { color: '#FFFFFF', bgcolor: ORPHOS_COLORS.primary },
          },
        }}
      >
        {tabs.map((tabDef, idx) => (
          tabDef.key === 'notifications' && trackerCount > 0 ? (
            <Tab key={tabDef.key}
              label={
                <Box sx={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                  <span>{tabDef.label}</span>
                  {/* On the selected pill the badge sits on blue, so it takes a
                      white wash; off it, a deep blue chip against the strip.
                      Its own critical red would have been a third status colour
                      on a control that is not reporting status. */}
                  <Box sx={{
                    bgcolor: value === idx ? 'rgba(255, 255, 255, 0.28)' : '#1C3268',
                    color: '#FFFFFF',
                    borderRadius: 999, px: '7px', py: '1px',
                    fontSize: 12.5, fontWeight: 700, lineHeight: 1.4,
                  }}>
                    {trackerCount > 99 ? '99+' : trackerCount}
                  </Box>
                </Box>
              }
            />
          ) : (
            <Tab key={tabDef.key} label={tabDef.label} />
          )
        ))}
      </Tabs>

      <SectionToggle
        open={pref.open}
        always={pref.always}
        onToggle={onToggle}
        onSetAlways={onSetAlways}
        menuTitle={t('dashboard.panel.menuTitle', 'Panel default')}
        words={{
          expand: t('dashboard.panel.expanded', 'expanded'),
          collapse: t('dashboard.panel.collapsed', 'collapsed'),
        }}
        // The panel grows UPWARD out of this strip, so the arrow that means
        // "open me" points up — the reverse of a section that expands down.
        invertArrows
        // Docked at the foot of the column: a menu dropping below it would open
        // off the bottom of the viewport.
        menuPlacement='above'
      />
    </Box>
  );
}
