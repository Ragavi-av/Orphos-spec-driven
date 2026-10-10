import { Box, ButtonBase } from '@mui/material';
import LanguageIcon from '@mui/icons-material/Language';
import { useTranslation } from 'react-i18next';
import globalI18n, { SUPPORTED_LANGUAGES, markLanguageChosen } from 'eip-shell-context/i18n';

/**
 * Language picker shown in the HeaderBar — one pill per language, the active
 * one filled.
 *
 * THE ONLY locale control on the user side, and deliberately so. It moves the
 * PROSE and nothing else: the time zone, the reporting currency and the
 * number/date notation all come from the tenant's primary country, which only
 * an admin sets (Admin → Branding → Primary country). A reader switching to
 * Japanese on an India workspace gets Japanese labels over ₹ crore figures and
 * Asia/Kolkata clocks — because re-denominating a figure or moving its wall
 * clock changes what it means, which is not a reading preference. See
 * eip-shell-context/country.
 *
 * The pick survives reloads and branding refetches, but NOT a change of the
 * tenant's country: relocating the workspace resets everyone to the new
 * country's language, and the reader picks again from there.
 *
 * A segmented control rather than a dropdown: with only two languages shipped
 * (see SUPPORTED_LANGUAGES) a select hid half the choice behind a click, and
 * the pills make the alternative readable at a glance. Still driven off
 * SUPPORTED_LANGUAGES, so adding a third language widens the control rather
 * than breaking it — past three or four, revisit the dropdown.
 *
 * The choice is persisted to localStorage ('orphos.lang') by i18next
 * LanguageDetector, so it survives reloads. Changing it re-renders every
 * component using useTranslation without a page reload.
 */
export default function LanguageSwitcher() {
  const { i18n } = useTranslation();
  const current = (i18n.language || 'en').split('-')[0];

  // Marked BEFORE the switch so the re-render this triggers already sees the
  // reader as having chosen. The marker is what stops the tenant country's
  // default language overwriting the pick on the next branding fetch — the
  // detector's own 'orphos.lang' cache cannot say who chose, since every
  // changeLanguage() writes it, this one and the automatic default alike.
  //
  // Only for the GLOBAL instance. The admin console renders this same switcher
  // against a scoped instance ('orphos.admin.lang') whose language is separate
  // from the dashboard's by design, so marking there would silently pin the
  // dashboard to whatever an admin last read the console in.
  const choose = (code) => {
    if (i18n === globalI18n) markLanguageChosen();
    i18n.changeLanguage(code);
  };

  return (
    <Box sx={{ display: 'flex', alignItems: 'center', gap: 1 }}>
      {/* The globe sits outside the pill group, as a quiet label for it.
          #7D92C4, not the dimmer #4D6299 it used to be: the artboard draws it
          at slate, and orphosTheme's own contrast note reserves #4D6299 for
          marks that restate something already legible beside them. */}
      <LanguageIcon sx={{ fontSize: 16, color: '#7D92C4' }} />
      <Box
        role='group'
        sx={{
          display: 'flex', alignItems: 'center', gap: '2px',
          bgcolor: '#122349',
          border: '1px solid #2E4A8C',
          borderRadius: 999, p: '2px',
        }}
      >
        {SUPPORTED_LANGUAGES.map((lang) => {
          const active = lang.code === current;
          return (
            <ButtonBase
              key={lang.code}
              onClick={() => choose(lang.code)}
              aria-pressed={active}
              sx={{
                px: '13px', py: '5px', borderRadius: 999,
                // 14px per the artboard — at 12px the kanji in 日本語 lost
                // enough stroke detail on a standard display to be a guess.
                fontSize: 14, fontWeight: 600, lineHeight: 1.2,
                // The artboard dims the inactive pill to #7D92C4. Held at
                // #A8BBE6 instead: this is interactive text, not a caption, and
                // slate on #122349 lands around 4.4:1 — under AA for the size.
                color: active ? '#FFFFFF' : '#A8BBE6',
                bgcolor: active ? '#2F6FF0' : 'transparent',
                transition: 'background-color 120ms ease, color 120ms ease',
                '&:hover': {
                  bgcolor: active ? '#2F6FF0' : 'rgba(168, 187, 230, 0.12)',
                  color: '#FFFFFF',
                },
              }}
            >
              {lang.nativeLabel}
            </ButtonBase>
          );
        })}
      </Box>
    </Box>
  );
}
