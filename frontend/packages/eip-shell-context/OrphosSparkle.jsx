import { Box } from '@mui/material';

/**
 * The Orphos "ask" mark — a large four-point star with a smaller one twinkling
 * out of phase behind it.
 *
 * Every affordance that hands something to the co-pilot carries this mark in
 * the design, so the user learns one shape rather than a different icon per
 * surface. It replaces MUI's AutoAwesome, whose three-star arrangement reads
 * as "magic" rather than as a specific product.
 *
 * The two stars animate in counterpoint (one dims as the other brightens),
 * which is what makes it read as alive at 15px. Both drop to static under
 * prefers-reduced-motion.
 *
 * @param {number} [size]   px, applied to both axes
 * @param {string} [color]  the large star
 * @param {string} [spark]  the small star; defaults to a lift of `color`
 */
export default function OrphosSparkle({ size = 15, color = '#8DBCFF', spark = '#DBE9FF' }) {
  return (
    <Box
      component="svg"
      viewBox="0 0 20 20"
      aria-hidden="true"
      sx={{
        width: size, height: size, display: 'block', flex: '0 0 auto',
        // The stars scale about their own centres, which sit outside the tight
        // path bounds — without this the twinkle clips.
        overflow: 'visible',
        '@keyframes orphosTwinkleA': {
          '0%, 100%': { opacity: 1, transform: 'scale(1)' },
          '45%':      { opacity: 0.35, transform: 'scale(0.82)' },
        },
        '@keyframes orphosTwinkleB': {
          '0%, 100%': { opacity: 0.4, transform: 'scale(0.8)' },
          '45%':      { opacity: 1, transform: 'scale(1.08)' },
        },
        '& .orphos-star': { animationDuration: '2.2s', animationTimingFunction: 'ease-in-out', animationIterationCount: 'infinite' },
        '@media (prefers-reduced-motion: reduce)': {
          '& .orphos-star': { animation: 'none', opacity: 1, transform: 'none' },
        },
      }}
    >
      <Box
        component="path"
        className="orphos-star"
        d="M7.6 1.6l1.15 3.35 3.35 1.15-3.35 1.15L7.6 10.6 6.45 7.25 3.1 6.1l3.35-1.15z"
        sx={{ fill: color, transformOrigin: '6.8px 6.1px', animationName: 'orphosTwinkleA' }}
      />
      <Box
        component="path"
        className="orphos-star"
        d="M14.3 10.6l.78 2.24 2.24.78-2.24.78-.78 2.24-.78-2.24-2.24-.78 2.24-.78z"
        sx={{ fill: spark, transformOrigin: '14.3px 13.6px', animationName: 'orphosTwinkleB' }}
      />
    </Box>
  );
}
