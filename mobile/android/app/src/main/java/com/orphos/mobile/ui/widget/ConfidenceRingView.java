package com.orphos.mobile.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * A circular confidence gauge — a grey track with a coloured progress arc and the percentage
 * (plus an optional caption) drawn in the centre. Used on the Recommendation detail screen.
 * Set the value with {@link #setPercent(int)}; the colour defaults to Orphos green.
 */
public class ConfidenceRingView extends View {

    private int percent = 92;
    private int ringColor = Color.parseColor("#2FD08A"); // green
    private String caption = "CONFIDENCE";

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pctText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint capText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    public ConfidenceRingView(Context c) { this(c, null); }
    public ConfidenceRingView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(6 * d);
        track.setColor(Color.parseColor("#26314A"));
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(6 * d);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(ringColor);
        pctText.setColor(Color.WHITE);
        pctText.setTextAlign(Paint.Align.CENTER);
        pctText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        capText.setColor(Color.parseColor("#8A94A6"));
        capText.setTextAlign(Paint.Align.CENTER);
        capText.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
    }

    public void setPercent(int p) { percent = Math.max(0, Math.min(100, p)); invalidate(); }
    public void setRingColor(int color) { ringColor = color; arc.setColor(color); invalidate(); }
    public void setCaption(String c) { caption = c; invalidate(); }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float inset = 4 * d;
        bounds.set(inset, inset, getWidth() - inset, getHeight() - inset);
        canvas.drawArc(bounds, 0, 360, false, track);
        canvas.drawArc(bounds, -90, 360f * percent / 100f, false, arc);

        float cx = getWidth() / 2f;
        pctText.setTextSize(getHeight() * 0.30f);
        capText.setTextSize(getHeight() * 0.11f);
        float cy = getHeight() / 2f;
        canvas.drawText(percent + "%", cx, cy + pctText.getTextSize() * 0.20f, pctText);
        if (caption != null) {
            capText.setLetterSpacing(0.08f);
            canvas.drawText(caption, cx, cy + getHeight() * 0.28f, capText);
        }
    }
}
