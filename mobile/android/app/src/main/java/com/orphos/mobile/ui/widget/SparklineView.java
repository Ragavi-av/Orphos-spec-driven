package com.orphos.mobile.ui.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * A small trend line with a fading area fill and a dot on the latest point. Points are
 * normalised to the view, so any scale works. Used by the CEO Metrics hero card.
 */
public class SparklineView extends View {

    private final List<Float> points = new ArrayList<>();
    private int lineColor = Color.parseColor("#34D399");

    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    public SparklineView(Context c) { this(c, null); }
    public SparklineView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2 * d);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        dot.setStyle(Paint.Style.FILL);
    }

    public void setPoints(List<Float> values) {
        points.clear();
        if (values != null) points.addAll(values);
        invalidate();
    }

    public void setLineColor(int color) {
        lineColor = color;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (points.size() < 2) return;
        float d = getResources().getDisplayMetrics().density;
        float pad = 3 * d; // room for the end dot and stroke
        float w = getWidth() - 2 * pad, h = getHeight() - 2 * pad;
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float p : points) { min = Math.min(min, p); max = Math.max(max, p); }
        float span = max - min == 0 ? 1 : max - min;

        linePath.reset();
        float lastX = 0, lastY = 0;
        for (int i = 0; i < points.size(); i++) {
            lastX = pad + w * i / (points.size() - 1);
            lastY = pad + h - h * (points.get(i) - min) / span;
            if (i == 0) linePath.moveTo(lastX, lastY); else linePath.lineTo(lastX, lastY);
        }
        fillPath.set(linePath);
        fillPath.lineTo(lastX, getHeight());
        fillPath.lineTo(pad, getHeight());
        fillPath.close();

        int top = (lineColor & 0x00FFFFFF) | 0x59000000; // ~35% alpha
        fill.setShader(new LinearGradient(0, 0, 0, getHeight(), top, lineColor & 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        canvas.drawPath(fillPath, fill);
        line.setColor(lineColor);
        canvas.drawPath(linePath, line);
        dot.setColor(lineColor);
        canvas.drawCircle(lastX, lastY, 3 * d, dot);
    }
}
