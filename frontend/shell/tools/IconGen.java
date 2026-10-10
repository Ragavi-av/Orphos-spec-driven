/*
 * Generates the PWA / home-screen icon set in frontend/shell/public.
 *
 * The mark is the shell's own default brand tile — the conic-gradient rounded
 * square with the white eight-spoked asterisk that HeaderBar.jsx renders when a
 * tenant has uploaded no logo (see its `conic-gradient(from 210deg, …)` Box).
 * Reproducing it here rather than hand-drawing something new keeps the icon on
 * the iPhone home screen identical to the mark the user sees in the app header.
 *
 * Run (from frontend/shell):
 *     java tools/IconGen.java
 *
 * Java has no conic gradient primitive, so the background is filled per pixel
 * by angle. Everything is rendered at SS× the target size and box-downsampled,
 * which is what keeps the corner radius and the spoke tips clean — Java2D's own
 * antialiasing alone leaves visible stair-stepping on the diagonal spokes at
 * 32px.
 */

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import javax.imageio.ImageIO;

public final class IconGen {

    /** Supersampling factor — rendered at SS× then averaged down. */
    private static final int SS = 4;

    /** Conic sweep start, matching the header tile's `from 210deg`. */
    private static final double START_DEG = 210.0;

    /**
     * The header tile's gradient stops, in sweep order. The last entry repeats
     * the first so the conic wraps seamlessly at 360°.
     */
    private static final Color[] STOPS = {
        new Color(0x5B9DFF),
        new Color(0x2F6FF0),
        new Color(0x7F6BFF),
        new Color(0x5B9DFF),
    };

    /** Page ground (ORPHOS_COLORS.canvas) — the opaque backdrop iOS needs. */
    private static final Color CANVAS = new Color(0x0B1533);

    /** Corner radius as a fraction of the icon edge, for the masked variants. */
    private static final double RADIUS_FRACTION = 0.22;

    public static void main(String[] args) throws IOException {
        File out = new File("public");
        if (!out.isDirectory()) {
            System.err.println("Run this from frontend/shell — no ./public here.");
            System.exit(1);
        }

        // ── Rounded, transparent-cornered tiles ──────────────────────────────
        // Browser tab + Android "any" purpose. Transparency outside the corners
        // is correct here: the launcher/tab strip shows its own background
        // through them.
        write(render(32,  true,  false, 0.60), out, "favicon-32.png");
        write(render(192, true,  false, 0.56), out, "icon-192.png");
        write(render(512, true,  false, 0.56), out, "icon-512.png");

        // ── apple-touch-icon ─────────────────────────────────────────────────
        // iOS applies its OWN corner mask (squircle) and composites any alpha
        // onto black, so this one is full-bleed and fully opaque — pre-rounding
        // it would leave dark wedges inside iOS's mask.
        write(render(180, false, true,  0.52), out, "apple-touch-icon.png");

        // ── Android maskable ─────────────────────────────────────────────────
        // Adaptive-icon shapes crop to an inner circle of 80% diameter, so the
        // glyph is pulled well inside that safe zone and the gradient runs edge
        // to edge to survive any crop shape.
        write(render(512, false, true,  0.40), out, "icon-maskable-512.png");

        System.out.println("Wrote 5 icons to " + out.getAbsolutePath());
    }

    /**
     * @param size    output edge length in px
     * @param rounded apply the rounded-square mask (transparent outside it)
     * @param opaque  paint {@link #CANVAS} behind everything first
     * @param glyph   asterisk tip-to-tip diameter as a fraction of the edge
     */
    private static BufferedImage render(int size, boolean rounded, boolean opaque, double glyph) {
        int big = size * SS;
        BufferedImage img = new BufferedImage(big, big, BufferedImage.TYPE_INT_ARGB);

        conicFill(img, big);
        if (rounded) maskRoundRect(img, big);

        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        drawAsterisk(g, big, glyph);
        g.dispose();

        BufferedImage small = downsample(img, size);
        return opaque ? flatten(small) : small;
    }

    /** Fills every pixel by its angle around the centre, per {@link #STOPS}. */
    private static void conicFill(BufferedImage img, int size) {
        double c = size / 2.0;
        int segments = STOPS.length - 1;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                // Screen y grows downward, so negate it to sweep clockwise the
                // way CSS conic-gradient does.
                double deg = Math.toDegrees(Math.atan2(x + 0.5 - c, c - (y + 0.5)));
                double t = ((deg - START_DEG) % 360 + 360) % 360 / 360.0;
                double pos = t * segments;
                int i = Math.min((int) pos, segments - 1);
                img.setRGB(x, y, lerp(STOPS[i], STOPS[i + 1], pos - i));
            }
        }
    }

    private static int lerp(Color a, Color b, double f) {
        int r = (int) Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * f);
        int g = (int) Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * f);
        int bl = (int) Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * f);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Clears everything outside the rounded square (DST_IN against the shape). */
    private static void maskRoundRect(BufferedImage img, int size) {
        BufferedImage mask = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D mg = mask.createGraphics();
        mg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        mg.setColor(Color.WHITE);
        double r = size * RADIUS_FRACTION;
        mg.fill(new RoundRectangle2D.Double(0, 0, size, size, r * 2, r * 2));
        mg.dispose();

        Graphics2D g = img.createGraphics();
        g.setComposite(java.awt.AlphaComposite.DstIn);
        g.drawImage(mask, 0, 0, null);
        g.dispose();
    }

    /** The eight-spoked asterisk (✳), drawn as round-capped radial strokes. */
    private static void drawAsterisk(Graphics2D g, int size, double glyphFraction) {
        double c = size / 2.0;
        double radius = size * glyphFraction / 2.0;
        double stroke = radius * 0.42;
        // Round caps already extend half the stroke past the endpoint, so stop
        // the line short of the nominal radius to keep the tip-to-tip diameter
        // equal to the glyph fraction that was asked for.
        double reach = radius - stroke / 2.0;

        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke((float) stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(i * 45.0);
            g.draw(new Line2D.Double(c, c, c + reach * Math.cos(a), c + reach * Math.sin(a)));
        }
    }

    /** Box-averages the SS× render down to the target size. */
    private static BufferedImage downsample(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        int n = SS * SS;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                long a = 0, r = 0, g = 0, b = 0;
                for (int dy = 0; dy < SS; dy++) {
                    for (int dx = 0; dx < SS; dx++) {
                        int p = src.getRGB(x * SS + dx, y * SS + dy);
                        int pa = (p >>> 24);
                        a += pa;
                        // Premultiply before averaging: straight-alpha averaging
                        // bleeds the colour of fully transparent pixels into the
                        // corner edges as a dark fringe.
                        r += ((p >> 16) & 0xFF) * pa;
                        g += ((p >> 8) & 0xFF) * pa;
                        b += (p & 0xFF) * pa;
                    }
                }
                int outA = (int) (a / n);
                int rgb = a == 0 ? 0
                        : (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
                out.setRGB(x, y, (outA << 24) | rgb);
            }
        }
        return out;
    }

    /** Composites over the canvas colour so the result carries no alpha. */
    private static BufferedImage flatten(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(CANVAS);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static void write(BufferedImage img, File dir, String name) throws IOException {
        File f = new File(dir, name);
        ImageIO.write(img, "png", f);
        System.out.println("  " + name + "  " + img.getWidth() + "x" + img.getHeight()
                + "  " + f.length() + " bytes");
    }

    private IconGen() { }
}
