package com.orphos.mobile.ui.scribbles;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;

import com.google.android.material.snackbar.Snackbar;
import com.orphos.mobile.R;

/**
 * Handwriting "Scribble" canvas (Figma "ORPHOS-1"): a tool row (pen, highlighter,
 * eraser, shapes, lasso, colour) over a drawing surface. Done → recognition + AI summary
 * → review/edit → save (see {@link ProcessingActivity}, {@link CaptureSummaryActivity}).
 */
public class ScribbleActivity extends AppCompatActivity {

    private DrawingView drawing;

    private static final int[] TOOL_IDS = {
            R.id.tool_pen, R.id.tool_highlighter, R.id.tool_eraser,
            R.id.tool_shapes, R.id.tool_lasso, R.id.tool_color };
    private static final DrawingView.Tool[] TOOLS = {
            DrawingView.Tool.PEN, DrawingView.Tool.HIGHLIGHTER, DrawingView.Tool.ERASER,
            DrawingView.Tool.SHAPES, DrawingView.Tool.LASSO, DrawingView.Tool.COLOR };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        setContentView(R.layout.activity_scribble);
        // Shared header: status dot and bell behave exactly as on Home (see HeaderActions).
        com.orphos.mobile.ui.common.HeaderActions.bind(this, null);

        drawing = findViewById(R.id.drawing);
        findViewById(R.id.scribble_back).setOnClickListener(v -> finish());
        findViewById(R.id.scribble_done).setOnClickListener(v -> done());
        findViewById(R.id.scribble_share).setOnClickListener(v -> share());
        findViewById(R.id.scribble_more).setOnClickListener(v ->
                Snackbar.make(v, R.string.scribble_title, Snackbar.LENGTH_SHORT).show());

        for (int i = 0; i < TOOL_IDS.length; i++) {
            final int idx = i;
            findViewById(TOOL_IDS[i]).setOnClickListener(v -> selectTool(idx));
        }
        drawing.setColor(ContextCompat.getColor(this, PALETTE[0]));
        drawing.setOnSelectionListener(this::onLassoSelection);
        View undo = findViewById(R.id.scribble_undo);
        undo.setOnClickListener(v -> drawing.undo());
        Runnable syncUndo = () -> {
            undo.setEnabled(drawing.canUndo());
            undo.setAlpha(drawing.canUndo() ? 1f : 0.4f);
        };
        drawing.setOnHistoryListener(syncUndo);
        syncUndo.run();
        updateColorSwatch();
        selectTool(0);
    }

    private void selectTool(int selected) {
        DrawingView.Tool tool = TOOLS[selected];
        View anchor = findViewById(TOOL_IDS[selected]);
        if (tool == DrawingView.Tool.COLOR) {
            // Colour is a picker, not a mode: the active tool stays highlighted.
            showColorPicker(anchor);
            return;
        }
        if (tool == DrawingView.Tool.SHAPES) {
            showShapePicker(anchor);
        } else {
            drawing.setTool(tool);
        }
        if (tool == DrawingView.Tool.LASSO) {
            Snackbar.make(drawing, R.string.scribble_lasso_hint, Snackbar.LENGTH_SHORT).show();
        }
        highlightTool(selected);
    }

    private void highlightTool(int selected) {
        int active = ContextCompat.getColor(this, R.color.primary);
        int inactive = ContextCompat.getColor(this, R.color.text_secondary);
        for (int i = 0; i < TOOL_IDS.length; i++) {
            LinearLayout item = findViewById(TOOL_IDS[i]);
            boolean on = i == selected;
            View icon = item.getChildAt(0);
            TextView label = (TextView) item.getChildAt(1);
            View underline = item.getChildAt(2);
            // The colour swatch keeps its own fill; other tools tint their glyph.
            if (icon instanceof ImageView) {
                ((ImageView) icon).setColorFilter(on ? active : inactive, PorterDuff.Mode.SRC_IN);
            }
            label.setTextColor(on ? active : inactive);
            underline.setBackgroundColor(on ? active : 0);
        }
    }

    private static final int[] SHAPE_LABELS = {
            R.string.shape_line, R.string.shape_arrow, R.string.shape_rectangle, R.string.shape_oval };
    private static final DrawingView.Shape[] SHAPES = {
            DrawingView.Shape.LINE, DrawingView.Shape.ARROW,
            DrawingView.Shape.RECTANGLE, DrawingView.Shape.OVAL };

    /** Shapes draws right away with the last figure; the menu switches to another. */
    private void showShapePicker(View anchor) {
        drawing.setShape(drawing.getShape());
        // Built by hand, like the colour picker: the platform menu renders dark text on
        // this app's dark surfaces.
        float d = getResources().getDisplayMetrics().density;
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackgroundResource(R.drawable.card_bg_2);
        list.setPadding(0, (int) (6 * d), 0, (int) (6 * d));
        list.setMinimumWidth((int) (180 * d));

        PopupWindow popup = new PopupWindow(list, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8 * d);

        int active = ContextCompat.getColor(this, R.color.primary);
        int text = ContextCompat.getColor(this, R.color.text);
        for (int i = 0; i < SHAPES.length; i++) {
            DrawingView.Shape s = SHAPES[i];
            boolean on = s == drawing.getShape();
            TextView row = new TextView(this);
            row.setText(SHAPE_LABELS[i]);
            row.setTextSize(15);
            row.setTextColor(on ? active : text);
            if (on) row.setTypeface(row.getTypeface(), android.graphics.Typeface.BOLD);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setMinHeight((int) (46 * d));
            row.setPadding((int) (18 * d), 0, (int) (18 * d), 0);
            TypedValue ripple = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
            row.setBackgroundResource(ripple.resourceId);
            if (on) {
                android.graphics.drawable.Drawable check =
                        ContextCompat.getDrawable(this, R.drawable.ic_check_circle).mutate();
                check.setTint(active);
                row.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, check, null);
                row.setCompoundDrawablePadding((int) (12 * d));
            }
            row.setOnClickListener(v -> {
                drawing.setShape(s);
                popup.dismiss();
            });
            list.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        popup.showAsDropDown(anchor);
    }

    private static final int[] PALETTE = {
            R.color.text, R.color.red, R.color.orange, R.color.scribble_yellow,
            R.color.green, R.color.link, R.color.purple, R.color.scribble_pink };

    /** A row of swatches under the COLOR tool; the chosen one gets a ring. */
    private void showColorPicker(View anchor) {
        float d = getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackgroundResource(R.drawable.card_bg_2);
        int pad = (int) (10 * d);
        row.setPadding(pad, pad, pad, pad);

        PopupWindow popup = new PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setElevation(8 * d);

        int current = drawing.getColor();
        int size = (int) (30 * d);
        for (int res : PALETTE) {
            int c = ContextCompat.getColor(this, res);
            GradientDrawable dot = new GradientDrawable();
            dot.setShape(GradientDrawable.OVAL);
            dot.setColor(c);
            if (c == current) dot.setStroke((int) (3 * d), ContextCompat.getColor(this, R.color.primary));
            View swatch = new View(this);
            swatch.setBackground(dot);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
            lp.setMarginStart((int) (5 * d));
            lp.setMarginEnd((int) (5 * d));
            swatch.setLayoutParams(lp);
            swatch.setOnClickListener(v -> {
                drawing.setColor(c);
                // Eraser and highlighter do not use the colour: go back to writing.
                DrawingView.Tool t = drawing.getTool();
                if (t == DrawingView.Tool.ERASER || t == DrawingView.Tool.HIGHLIGHTER) {
                    drawing.setTool(DrawingView.Tool.PEN);
                    highlightTool(0);
                }
                updateColorSwatch();
                popup.dismiss();
            });
            row.addView(swatch);
        }
        popup.showAsDropDown(anchor);
    }

    private void updateColorSwatch() {
        LinearLayout item = findViewById(R.id.tool_color);
        item.getChildAt(0).setBackgroundTintList(ColorStateList.valueOf(drawing.getColor()));
    }

    private void onLassoSelection(int count) {
        if (count == 0) {
            Snackbar.make(drawing, R.string.scribble_lasso_none, Snackbar.LENGTH_SHORT).show();
            return;
        }
        Snackbar.make(drawing, getString(R.string.scribble_lasso_selected, count), Snackbar.LENGTH_LONG)
                .setAction(R.string.scribble_lasso_delete, v -> drawing.deleteSelection())
                .show();
    }

    private void share() {
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, getString(R.string.scribble_title));
        startActivity(Intent.createChooser(send, getString(R.string.capture_share)));
    }

    /**
     * Done → recognise + summarise → review → save. Nothing is stored yet: the canvas is
     * flattened to a page image and handed, with its strokes, to processing; the note is
     * only persisted once the user saves it from the review screen. This canvas stays
     * underneath until then, so backing out of the review returns to the writing.
     */
    private void done() {
        if (drawing == null || drawing.isEmpty()) {
            Snackbar.make(findViewById(android.R.id.content), R.string.scribble_empty, Snackbar.LENGTH_SHORT).show();
            return;
        }
        android.graphics.Bitmap page = drawing.exportPaperBitmap();
        java.io.File file = page == null ? null
                : com.orphos.mobile.core.capture.CaptureFiles.fromBitmap(this, page, "handwriting");
        if (file == null) {
            Snackbar.make(findViewById(android.R.id.content), R.string.capture_not_saved, Snackbar.LENGTH_LONG).show();
            return;
        }
        Intent i = new Intent(this, ProcessingActivity.class);
        i.putExtra(ProcessingActivity.EXTRA_FILE, file.getAbsolutePath());
        java.io.File ink = com.orphos.mobile.core.capture.CaptureFiles.writeInk(this, drawing.inkStrokes());
        if (ink != null) i.putExtra(ProcessingActivity.EXTRA_INK, ink.getAbsolutePath());
        i.putExtra(ProcessingActivity.EXTRA_INK_WIDTH, (float) drawing.getWidth());
        i.putExtra(ProcessingActivity.EXTRA_INK_HEIGHT, (float) drawing.getHeight());
        review.launch(i);
    }

    /** The review screen reports RESULT_OK once the note is saved; the canvas is then done. */
    private final androidx.activity.result.ActivityResultLauncher<Intent> review =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
                    result -> { if (result.getResultCode() == RESULT_OK) finish(); });
}
