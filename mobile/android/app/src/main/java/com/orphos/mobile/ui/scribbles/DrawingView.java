package com.orphos.mobile.ui.scribbles;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Region;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Finger-drawing surface for the "Scribble" capture. Supports pen, highlighter,
 * eraser, shapes and lasso (select + move) tools plus colour selection; each stroke
 * keeps its own paint so undo and erase compose correctly.
 *
 * <p>Alongside the pixels it records every stroke's touch points (x, y, time), which is
 * what handwriting recognition reads — see {@link #inkStrokes()}.</p>
 */
public class DrawingView extends View {

    /** Scribble tools, mirroring the Figma toolbar. */
    public enum Tool { PEN, HIGHLIGHTER, ERASER, SHAPES, LASSO, COLOR }

    private static final float ERASER_WIDTH = 34f;

    private static class Stroke {
        final Path path;
        final Paint paint;
        final Tool tool;
        /** Touch points as x, y, t triples (t = ms since the first touch on this canvas). */
        final FloatList points = new FloatList();
        Stroke(Path path, Paint paint, Tool tool) { this.path = path; this.paint = paint; this.tool = tool; }
    }

    private final List<Stroke> strokes = new ArrayList<>();
    private Stroke current;
    private long timeBase = -1;
    private Bitmap bitmap;
    private Canvas cache;

    private Tool tool = Tool.PEN;
    private int color = Color.WHITE;

    /** Figure the SHAPES tool draws: dragged out from the touch-down point to the finger. */
    public enum Shape { LINE, ARROW, RECTANGLE, OVAL }

    private Shape shape = Shape.RECTANGLE;
    private float startX, startY;

    // Lasso: the loop being drawn, the strokes it caught, and whether a drag is moving them.
    private Path lasso;
    private final List<Stroke> selection = new ArrayList<>();
    private final RectF selectionBounds = new RectF();
    private boolean moving;
    private float lastX, lastY, moveStartX, moveStartY;
    private final Paint lassoPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** One undoable edit. Undo pops the newest and reverts it. */
    private interface Edit { void revert(); }

    private final List<Edit> history = new ArrayList<>();
    private Runnable historyListener;

    public DrawingView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        lassoPaint.setStyle(Paint.Style.STROKE);
        lassoPaint.setStrokeWidth(1.5f * d);
        lassoPaint.setColor(0xCC94A2C2);
        lassoPaint.setPathEffect(new DashPathEffect(new float[] {8 * d, 6 * d}, 0));
        selectionPaint.set(lassoPaint);
        selectionPaint.setColor(0xFF4F8CFF);
    }

    public void setTool(Tool t) {
        if (t != Tool.LASSO) clearSelection();
        this.tool = t;
        invalidate();
    }

    public Tool getTool() { return tool; }

    public void setShape(Shape s) {
        this.shape = s;
        setTool(Tool.SHAPES);
    }

    public Shape getShape() { return shape; }

    /** Colour for the next pen or shape stroke; a lasso selection is recoloured too. */
    public void setColor(int c) {
        this.color = c;
        List<Stroke> changed = new ArrayList<>();
        List<Integer> before = new ArrayList<>();
        for (Stroke s : selection) {
            if ((s.tool == Tool.PEN || s.tool == Tool.SHAPES) && s.paint.getColor() != c) {
                changed.add(s);
                before.add(s.paint.getColor());
                s.paint.setColor(c);
            }
        }
        if (!changed.isEmpty()) {
            record(() -> {
                for (int i = 0; i < changed.size(); i++) changed.get(i).paint.setColor(before.get(i));
            });
        }
        invalidate();
    }

    public int getColor() { return color; }

    public boolean hasSelection() { return !selection.isEmpty(); }

    /** Told how many strokes a finished lasso loop caught (0 when it caught none). */
    public interface OnSelectionListener { void onSelection(int count); }

    private OnSelectionListener selectionListener;

    public void setOnSelectionListener(OnSelectionListener l) { this.selectionListener = l; }

    /** Removes the strokes the lasso caught. */
    public void deleteSelection() {
        if (selection.isEmpty()) return;
        // Keep each stroke's position in the list: drawing order decides what an eraser covers.
        List<Stroke> removed = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        for (int i = 0; i < strokes.size(); i++) {
            if (selection.contains(strokes.get(i))) { removed.add(strokes.get(i)); at.add(i); }
        }
        strokes.removeAll(removed);
        record(() -> {
            for (int i = 0; i < removed.size(); i++) strokes.add(at.get(i), removed.get(i));
        });
        clearSelection();
        invalidate();
    }

    /** Called whenever the undo history grows or shrinks, e.g. to enable an Undo button. */
    public void setOnHistoryListener(Runnable l) { this.historyListener = l; }

    public boolean canUndo() { return !history.isEmpty(); }

    private void record(Edit edit) {
        history.add(edit);
        if (historyListener != null) historyListener.run();
    }

    private void addStroke(Stroke s) {
        strokes.add(s);
        record(() -> strokes.remove(s));
    }

    private void clearSelection() {
        selection.clear();
        moving = false;
        lasso = null;
    }

    private Paint paintFor(Tool t) {
        Paint p = new Paint();
        p.setAntiAlias(true);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setStrokeCap(Paint.Cap.ROUND);
        switch (t) {
            case HIGHLIGHTER:
                p.setColor(0x66FFD54F);
                p.setStrokeWidth(24f);
                break;
            case ERASER:
                p.setColor(Color.TRANSPARENT);
                p.setStrokeWidth(ERASER_WIDTH);
                p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                break;
            default:
                p.setColor(color);
                p.setStrokeWidth(6f);
        }
        return p;
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (w > 0 && h > 0) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            cache = new Canvas(bitmap);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap == null) return;
        bitmap.eraseColor(Color.TRANSPARENT);
        for (Stroke s : strokes) cache.drawPath(s.path, s.paint);
        if (current != null) cache.drawPath(current.path, current.paint);
        canvas.drawBitmap(bitmap, 0, 0, null);
        if (lasso != null) canvas.drawPath(lasso, lassoPaint);
        if (!selection.isEmpty()) canvas.drawRect(selectionBounds, selectionPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        boolean handled;
        switch (tool) {
            case SHAPES: handled = onShapeTouch(e); break;
            case LASSO:  handled = onLassoTouch(e); break;
            default:     handled = onFreehandTouch(e);
        }
        if (handled) invalidate();
        return handled;
    }

    private boolean onFreehandTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                current = new Stroke(new Path(), paintFor(tool), tool);
                current.path.moveTo(x, y);
                current.points.add3(x, y, rel(e.getEventTime()));
                break;
            case MotionEvent.ACTION_MOVE:
                if (current != null) {
                    // Batched samples between frames keep fast handwriting legible to recognition.
                    for (int i = 0; i < e.getHistorySize(); i++) {
                        current.points.add3(e.getHistoricalX(i), e.getHistoricalY(i), rel(e.getHistoricalEventTime(i)));
                    }
                    current.path.lineTo(x, y);
                    current.points.add3(x, y, rel(e.getEventTime()));
                }
                break;
            case MotionEvent.ACTION_UP:
                if (current != null) { addStroke(current); current = null; }
                break;
            default:
                return false;
        }
        return true;
    }

    /** Drag out a line, arrow, rectangle or oval; the figure follows the finger until release. */
    private boolean onShapeTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                startX = x;
                startY = y;
                current = new Stroke(new Path(), paintFor(Tool.SHAPES), Tool.SHAPES);
                break;
            case MotionEvent.ACTION_MOVE:
                if (current != null) buildShape(current.path, startX, startY, x, y);
                break;
            case MotionEvent.ACTION_UP:
                if (current != null) {
                    buildShape(current.path, startX, startY, x, y);
                    // A tap, not a drag: nothing to keep.
                    if (Math.hypot(x - startX, y - startY) >= 8f) {
                        samplePath(current, rel(e.getEventTime()));
                        addStroke(current);
                    }
                    current = null;
                }
                break;
            case MotionEvent.ACTION_CANCEL:
                current = null;
                break;
            default:
                return false;
        }
        return true;
    }

    private void buildShape(Path p, float x0, float y0, float x1, float y1) {
        p.reset();
        switch (shape) {
            case LINE:
                p.moveTo(x0, y0);
                p.lineTo(x1, y1);
                break;
            case ARROW: {
                p.moveTo(x0, y0);
                p.lineTo(x1, y1);
                double angle = Math.atan2(y1 - y0, x1 - x0);
                float head = Math.min(36f, (float) Math.hypot(x1 - x0, y1 - y0) / 3f);
                for (double side : new double[] {Math.PI / 7, -Math.PI / 7}) {
                    p.moveTo(x1, y1);
                    p.lineTo(x1 - head * (float) Math.cos(angle + side),
                            y1 - head * (float) Math.sin(angle + side));
                }
                break;
            }
            case RECTANGLE:
                p.addRect(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1), Path.Direction.CW);
                break;
            case OVAL:
                p.addOval(new RectF(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1)), Path.Direction.CW);
                break;
        }
    }

    /** Records points along a shape's outline so the lasso can find and move it. */
    private static void samplePath(Stroke s, float t) {
        PathMeasure pm = new PathMeasure(s.path, false);
        float[] pos = new float[2];
        do {
            float len = pm.getLength();
            for (float d = 0; d <= len; d += 12f) {
                pm.getPosTan(d, pos, null);
                s.points.add3(pos[0], pos[1], t);
            }
            pm.getPosTan(len, pos, null);
            s.points.add3(pos[0], pos[1], t);
        } while (pm.nextContour());
    }

    /**
     * Lasso: draw a loop to select the strokes inside it, then drag inside the dashed box
     * to move them. Touching outside the box starts a new loop.
     */
    private boolean onLassoTouch(MotionEvent e) {
        float x = e.getX(), y = e.getY();
        switch (e.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (!selection.isEmpty() && selectionBounds.contains(x, y)) {
                    moving = true;
                    lastX = moveStartX = x;
                    lastY = moveStartY = y;
                } else {
                    selection.clear();
                    lasso = new Path();
                    lasso.moveTo(x, y);
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (moving) {
                    float dx = x - lastX, dy = y - lastY;
                    for (Stroke s : selection) {
                        s.path.offset(dx, dy);
                        s.points.offset(dx, dy);
                    }
                    selectionBounds.offset(dx, dy);
                    lastX = x;
                    lastY = y;
                } else if (lasso != null) {
                    lasso.lineTo(x, y);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (moving) {
                    moving = false;
                    // One undo step per drag, however many frames it took.
                    float dx = lastX - moveStartX, dy = lastY - moveStartY;
                    if (dx != 0 || dy != 0) {
                        List<Stroke> moved = new ArrayList<>(selection);
                        record(() -> {
                            for (Stroke s : moved) {
                                s.path.offset(-dx, -dy);
                                s.points.offset(-dx, -dy);
                            }
                        });
                    }
                } else if (lasso != null) {
                    lasso.close();
                    selectInside(lasso);
                    lasso = null;
                }
                break;
            default:
                return false;
        }
        return true;
    }

    /** Selects every stroke with most of its points inside {@code loop}. */
    private void selectInside(Path loop) {
        selection.clear();
        Region region = new Region();
        region.setPath(loop, new Region(0, 0, Math.max(1, getWidth()), Math.max(1, getHeight())));
        if (region.isEmpty()) {
            if (selectionListener != null) selectionListener.onSelection(0);
            return;
        }
        RectF bounds = null;
        for (Stroke s : strokes) {
            int total = 0, inside = 0;
            for (int i = 0; i + 2 < s.points.size(); i += 3) {
                total++;
                if (region.contains((int) s.points.get(i), (int) s.points.get(i + 1))) inside++;
            }
            if (total == 0 || inside * 2 < total) continue;
            selection.add(s);
            RectF b = new RectF();
            s.path.computeBounds(b, true);
            float pad = s.paint.getStrokeWidth() / 2f + 8f;
            b.inset(-pad, -pad);
            if (bounds == null) bounds = b; else bounds.union(b);
        }
        if (bounds != null) selectionBounds.set(bounds);
        if (selectionListener != null) selectionListener.onSelection(selection.size());
    }

    /** Event time relative to the first touch, so it stays exact as a float. */
    private float rel(long eventTime) {
        if (timeBase < 0) timeBase = eventTime;
        return eventTime - timeBase;
    }

    public void clear() {
        strokes.clear();
        history.clear();
        clearSelection();
        current = null;
        if (historyListener != null) historyListener.run();
        invalidate();
    }

    /** Reverts the newest edit: a stroke, shape, lasso move, recolour or delete. */
    public void undo() {
        if (history.isEmpty()) return;
        history.remove(history.size() - 1).revert();
        clearSelection();
        if (historyListener != null) historyListener.run();
        invalidate();
    }

    public boolean isEmpty() {
        return strokes.isEmpty() && current == null;
    }

    /**
     * The handwriting as recognition input: one {x0, y0, t0, x1, y1, t1, …} array per
     * contiguous run of writing. Only the pen counts (highlighter marks and shapes are not
     * writing), and points an eraser later passed over are dropped — splitting a stroke
     * where it was erased — so recognition reads what the canvas shows.
     */
    public ArrayList<float[]> inkStrokes() {
        ArrayList<float[]> out = new ArrayList<>();
        for (int si = 0; si < strokes.size(); si++) {
            Stroke s = strokes.get(si);
            if (s.tool != Tool.PEN) continue;
            FloatList run = new FloatList();
            for (int i = 0; i + 2 < s.points.size(); i += 3) {
                float x = s.points.get(i), y = s.points.get(i + 1);
                if (erasedAfter(si, x, y)) {
                    if (run.size() > 0) { out.add(run.toArray()); run = new FloatList(); }
                } else {
                    run.add3(x, y, s.points.get(i + 2));
                }
            }
            if (run.size() > 0) out.add(run.toArray());
        }
        return out;
    }

    /** True when an eraser stroke drawn after stroke {@code index} covers (x, y). */
    private boolean erasedAfter(int index, float x, float y) {
        float r = ERASER_WIDTH / 2f;
        for (int k = index + 1; k < strokes.size(); k++) {
            Stroke er = strokes.get(k);
            if (er.tool != Tool.ERASER) continue;
            FloatList p = er.points;
            for (int i = 0; i + 2 < p.size(); i += 3) {
                float dx = p.get(i) - x, dy = p.get(i + 1) - y;
                if (dx * dx + dy * dy <= r * r) return true;
            }
        }
        return false;
    }

    /**
     * Flattens the current strokes to a new bitmap for upload/ingestion. Returns null
     * before the view has been laid out.
     */
    public Bitmap exportBitmap() {
        if (getWidth() <= 0 || getHeight() <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(getWidth(), getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        for (Stroke s : strokes) c.drawPath(s.path, s.paint);
        return bmp;
    }

    /**
     * The canvas as a scanned page: dark ink on white. The on-screen canvas is light ink
     * on a transparent (dark-themed) surface, which OCR reads as a blank page. This is also
     * the image stored with the note and shown when it is reopened.
     */
    public Bitmap exportPaperBitmap() {
        Bitmap ink = exportBitmap();
        if (ink == null) return null;
        Bitmap page = Bitmap.createBitmap(ink.getWidth(), ink.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(page);
        c.drawColor(Color.WHITE);
        // Keep each pixel's coverage (alpha) but paint it near-black, whatever the pen colour.
        ColorMatrix toInk = new ColorMatrix(new float[] {
                0, 0, 0, 0, 20,
                0, 0, 0, 0, 20,
                0, 0, 0, 0, 30,
                0, 0, 0, 1, 0 });
        Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
        p.setColorFilter(new ColorMatrixColorFilter(toInk));
        c.drawBitmap(ink, 0, 0, p);
        ink.recycle();
        return page;
    }

    /** Minimal growable float array, so touch samples are not boxed one by one. */
    private static final class FloatList {
        private float[] data = new float[96];
        private int size;

        void add3(float a, float b, float c) {
            if (size + 3 > data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = a;
            data[size++] = b;
            data[size++] = c;
        }

        float get(int i) { return data[i]; }

        /** Shifts every (x, y) pair; times are left alone. */
        void offset(float dx, float dy) {
            for (int i = 0; i + 2 < size; i += 3) { data[i] += dx; data[i + 1] += dy; }
        }

        int size() { return size; }

        float[] toArray() { return Arrays.copyOf(data, size); }
    }
}
