package com.orphos.mobile.ui.scribbles;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * Draws over the Capture image viewfinder: the four corner guides from the design and, while
 * AI framing is on, an outline around the content the camera currently detects.
 */
public class FramingOverlayView extends View {

    private final float density = getResources().getDisplayMetrics().density;
    private final Paint cornerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint contentStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint contentFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path corners = new Path();
    private final RectF content = new RectF();
    private boolean hasContent;

    public FramingOverlayView(Context context) { this(context, null); }

    public FramingOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(3 * density);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        cornerPaint.setColor(Color.WHITE);
        contentStroke.setStyle(Paint.Style.STROKE);
        contentStroke.setStrokeWidth(2 * density);
        contentFill.setStyle(Paint.Style.FILL);
    }

    public void setCornerColor(int color) {
        if (cornerPaint.getColor() == color) return;
        cornerPaint.setColor(color);
        invalidate();
    }

    /** The detected content in this view's coordinates, or null to hide the outline. */
    public void setContent(@Nullable RectF rect, int color) {
        hasContent = rect != null;
        if (rect != null) {
            float pad = 10 * density;
            content.set(Math.max(0, rect.left - pad), Math.max(0, rect.top - pad),
                    Math.min(getWidth(), rect.right + pad), Math.min(getHeight(), rect.bottom + pad));
            contentStroke.setColor(color);
            contentFill.setColor((color & 0x00FFFFFF) | 0x1F000000);
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float inset = 32 * density, arm = 22 * density, r = 8 * density;
        float l = inset, t = inset, rt = w - inset, b = h - inset;
        corners.reset();
        // top-left
        corners.moveTo(l, t + arm);
        corners.lineTo(l, t + r);
        corners.arcTo(l, t, l + 2 * r, t + 2 * r, 180, 90, false);
        corners.lineTo(l + arm, t);
        // top-right
        corners.moveTo(rt - arm, t);
        corners.lineTo(rt - r, t);
        corners.arcTo(rt - 2 * r, t, rt, t + 2 * r, 270, 90, false);
        corners.lineTo(rt, t + arm);
        // bottom-right
        corners.moveTo(rt, b - arm);
        corners.lineTo(rt, b - r);
        corners.arcTo(rt - 2 * r, b - 2 * r, rt, b, 0, 90, false);
        corners.lineTo(rt - arm, b);
        // bottom-left
        corners.moveTo(l + arm, b);
        corners.lineTo(l + r, b);
        corners.arcTo(l, b - 2 * r, l + 2 * r, b, 90, 90, false);
        corners.lineTo(l, b - arm);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (hasContent) {
            float r = 8 * density;
            canvas.drawRoundRect(content, r, r, contentFill);
            canvas.drawRoundRect(content, r, r, contentStroke);
        }
        canvas.drawPath(corners, cornerPaint);
    }
}
