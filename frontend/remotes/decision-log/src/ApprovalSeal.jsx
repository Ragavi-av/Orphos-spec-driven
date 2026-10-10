import { Box } from '@mui/material';
import { useTranslation } from 'react-i18next';

/**
 * The approval seal — a hanko (印鑑) stamp beside a decision that has been
 * approved.
 *
 * Vermillion red, always, in BOTH languages. That is not a status colour and
 * must not be read as one: a Japanese seal is red because seal ink is red, not
 * because the decision is in trouble. Wiring this to the status palette would
 * turn every approved decision green and quietly destroy the reference — the
 * artboard draws it at #E0455C next to a green "Approved" chip precisely
 * because the two are saying different things.
 *
 * It is decorative reinforcement of the status chip, never the only signal:
 * the chip beside it already names the status in words, so a reader who does
 * not recognise the convention loses nothing. Hence `aria-hidden` on the glyph
 * and a title for anyone who hovers it.
 */
const SEAL_RED = '#E0455C';

export default function ApprovalSeal({ status }) {
  const { t } = useTranslation();
  if (String(status).toUpperCase() !== 'APPROVED') return null;

  // 承認 in Japanese, "OK" elsewhere — two characters either way, which is what
  // lets both fit a 30px circle at 12px without shrinking the type.
  const mark = t('decisionLog.seal.mark', 'OK');

  return (
    <Box
      aria-hidden
      title={t('decisionLog.status.APPROVED', 'Approved')}
      sx={{
        flex: '0 0 34px', display: 'grid', placeItems: 'center',
        alignSelf: 'center',
      }}
    >
      <Box sx={{
        width: 30, height: 30, borderRadius: '50%',
        border: `2px solid ${SEAL_RED}`, color: SEAL_RED,
        display: 'grid', placeItems: 'center',
        fontSize: 12, fontWeight: 700, letterSpacing: '-0.5px',
        // The glyph sits optically high inside a ring without this, because the
        // circle centres the line box rather than the characters.
        lineHeight: 1,
      }}>
        {mark}
      </Box>
    </Box>
  );
}
