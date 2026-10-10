import { Box } from '@mui/material';

/**
 * The Orphos ORB — the brand mark that replaces the plain arrow on every
 * affordance that hands a question to Ask Orphos.
 *
 * <p>Three variants, and WHICH one is showing is the affordance's entire state
 * model — the user learns the mark once and reads voice availability off its
 * colour rather than off a separate indicator:</p>
 *
 * <ul>
 *   <li>{@code white} — the default. The control submits text to Ask Orphos and
 *       voice is NOT available (the voice module is not running, or a platform
 *       admin has not enabled it for this tenant). Double-click does nothing.</li>
 *   <li>{@code dark} — voice IS available. Single click still submits; a
 *       double-click starts dictation.</li>
 *   <li>{@code darkSelected} — recording. The glow variant, pulsed, so an
 *       active microphone is unmistakable from across the room.</li>
 * </ul>
 *
 * <p>The artwork is served from the SHELL's origin at {@code /orb/*.png} rather
 * than imported as a bundled asset. `eip-shell-context` is a federation shared
 * singleton, so only one copy of this module loads at runtime — an imported,
 * content-hashed asset URL would resolve against whichever bundle won the
 * singleton race and 404 for every other remote. Every remote renders inside
 * the shell's page, so a root-relative path is always correct. When a remote is
 * run standalone in dev there is no shell serving /orb, so `onError` falls back
 * to the inline mark below rather than showing a broken image.</p>
 *
 * @param {'white'|'dark'|'darkSelected'} [variant]
 * @param {number} [size]   px, applied to both axes
 * @param {string} [title]  accessible name; omit when a parent already labels it
 */

const SRC = {
  white:        '/orb/orb-white.png',
  dark:         '/orb/orb-dark.png',
  darkSelected: '/orb/orb-dark-selected.png',
};

export default function OrphosOrb({ variant = 'white', size = 20, title }) {
  const recording = variant === 'darkSelected';

  return (
    <Box
      sx={{
        width: size, height: size, flex: '0 0 auto',
        display: 'grid', placeItems: 'center',
        // The glow variant carries its own halo in the artwork; the pulse makes
        // it read as LIVE rather than merely selected. Static under
        // prefers-reduced-motion — a recording indicator must not be the only
        // thing a motion-sensitive user has to perceive, so the colour change
        // from dark to glow carries the state on its own.
        '@keyframes orphosOrbPulse': {
          '0%, 100%': { transform: 'scale(1)',    opacity: 1 },
          '50%':      { transform: 'scale(1.09)', opacity: 0.82 },
        },
        '& .orphos-orb-img': recording
          ? { animation: 'orphosOrbPulse 1.5s ease-in-out infinite' }
          : undefined,
        '@media (prefers-reduced-motion: reduce)': {
          '& .orphos-orb-img': { animation: 'none' },
        },
      }}
    >
      <Box
        component="img"
        className="orphos-orb-img"
        src={SRC[variant] || SRC.white}
        alt={title || ''}
        aria-hidden={title ? undefined : 'true'}
        draggable={false}
        onError={e => {
          // Standalone-remote dev, or a shell built without the /orb assets.
          // Swap in a data-URI dot so the button keeps a mark of the right
          // size instead of collapsing to a broken-image glyph.
          if (e.currentTarget.dataset.fallback) return;
          e.currentTarget.dataset.fallback = '1';
          e.currentTarget.src = FALLBACK;
        }}
        sx={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block',
               userSelect: 'none', pointerEvents: 'none' }}
      />
    </Box>
  );
}

// A single violet dot — the ORB reduced to its terminal sphere. Only ever seen
// when /orb/*.png is unreachable (standalone remote dev).
const FALLBACK =
  'data:image/svg+xml;utf8,' + encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20">' +
    '<defs><radialGradient id="g" cx="35%" cy="30%">' +
    '<stop offset="0%" stop-color="#B06BFF"/><stop offset="100%" stop-color="#4B2BE8"/>' +
    '</radialGradient></defs><circle cx="10" cy="10" r="8" fill="url(%23g)"/></svg>'
  );
