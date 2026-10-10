package com.orphos.mobile.ui.scribbles;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.Capture;
import com.orphos.mobile.ui.common.HeaderActions;

import java.util.List;

/**
 * Scribbles (Notes) — capture by typed note, voice, image or handwriting. Every input
 * follows the same flow: Done → AI processing (ProcessingActivity, nothing stored) →
 * review and edit (CaptureSummaryActivity) → Save, which is the only step that persists:
 * the BFF stores it in ArcadeDB and forwards it to doc-ingest for Web Document Search.
 *
 * <p>Recent Captures lists the newest saved captures; Saved items (AllCapturesActivity)
 * lists all of them. Both read the same store — /api/mobile/captures when connected, the
 * on-device LocalCaptureStore in demo mode — so neither shows anything the user didn't save.</p>
 */
public class ScribblesFragment extends Fragment implements CaptureAdapter.Listener {

    /** How many of the newest captures the hub shows; the rest are under Saved items. */
    private static final int RECENT_LIMIT = 5;

    /**
     * Captures not yet in Web Document Search are re-sent once per app process: the save
     * never waits on doc-ingest, so a note can be listed before it is searchable.
     */
    private static boolean reingestRequested;

    private CaptureAdapter adapter;
    private HeaderActions header;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_scribbles, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new CaptureAdapter(this);
        RecyclerView list = view.findViewById(R.id.capture_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        View back = view.findViewById(R.id.scr_back);
        if (back != null) back.setOnClickListener(v -> {
            if (getActivity() instanceof com.orphos.mobile.MainActivity m) m.selectTab(R.id.nav_home);
        });

        view.findViewById(R.id.cap_type).setOnClickListener(v -> typeNoteDialog());
        view.findViewById(R.id.cap_voice).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), VoiceNoteActivity.class)));
        view.findViewById(R.id.cap_image).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CaptureActivity.class)));
        view.findViewById(R.id.cap_handwriting).setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ScribbleActivity.class)));

        View savedItems = view.findViewById(R.id.scr_captures_see_all);
        if (savedItems != null) savedItems.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AllCapturesActivity.class)));

        // AI Insights tiles: each opens the list behind its count.
        openInsightsOn(view, R.id.scr_tile_notes, NoteInsightsActivity.CATEGORY_NOTES);
        openInsightsOn(view, R.id.scr_tile_actions, NoteInsightsActivity.CATEGORY_ACTIONS);
        openInsightsOn(view, R.id.scr_tile_decisions, NoteInsightsActivity.CATEGORY_DECISIONS);
        openInsightsOn(view, R.id.scr_tile_insights, NoteInsightsActivity.CATEGORY_INSIGHTS);

        // Shared header: status dot, bell and search behave exactly as on Home.
        header = HeaderActions.bind(this, view, view.findViewById(R.id.scr_search));
    }

    private void openInsightsOn(View root, int tileId, int category) {
        root.findViewById(tileId).setOnClickListener(v ->
                startActivity(NoteInsightsActivity.intent(requireContext(), category)));
    }

    /** Also runs on return from a capture flow, so a just-saved note is listed at once. */
    @Override
    public void onResume() {
        super.onResume();
        loadCaptures();
        header.refreshStatus();
    }

    private void loadCaptures() {
        OrphosApp.get().repo().captures((data, fromMock) -> {
            if (!isAdded()) return;
            adapter.submit(data.size() > RECENT_LIMIT ? data.subList(0, RECENT_LIMIT) : data);
            showEmpty(data.isEmpty(), fromMock);
            showInsights(NoteInsights.of(data));
            if (!fromMock) reingestIfNeeded(data);
        });
    }

    private void reingestIfNeeded(List<Capture> data) {
        if (reingestRequested) return;
        boolean pending = false;
        for (Capture c : data) if (!c.ingested) { pending = true; break; }
        if (!pending) return;
        reingestRequested = true;
        OrphosApp.get().repo().reingestCaptures((n, failed) -> {
            if (failed) reingestRequested = false;   // try again on the next visit
            else if (n != null && n > 0 && isAdded()) loadCaptures();
        });
    }

    /** No captures: say so (or that the server is unreachable) instead of an empty gap. */
    private void showEmpty(boolean empty, boolean fromMock) {
        TextView t = getView() == null ? null : getView().findViewById(R.id.scr_captures_empty);
        if (t == null) return;
        t.setVisibility(empty ? View.VISIBLE : View.GONE);
        boolean unreachable = fromMock && !OrphosApp.get().config().isDemoMode();
        t.setText(unreachable ? R.string.data_unavailable : R.string.captures_empty);
    }

    private void showInsights(NoteInsights s) {
        View v = getView();
        if (v == null) return;
        setCount(v, R.id.scr_stat_notes, s.notes);
        setCount(v, R.id.scr_stat_actions, s.actions);
        setCount(v, R.id.scr_stat_decisions, s.decisions);
        setCount(v, R.id.scr_stat_insights, s.insights);
    }

    private static void setCount(View root, int id, int n) {
        TextView t = root.findViewById(id);
        if (t != null) t.setText(String.valueOf(n));
    }

    /**
     * Type Note: Done hands the text, exactly as typed, to AI processing (no OCR) and then
     * the review screen, where the user edits, names and saves it. Nothing is stored before
     * that Save. The dialog stays open when it's empty, so the draft isn't lost.
     */
    private void typeNoteDialog() {
        // Build the field from the dialog's dark overlay context, and pin the colours: the
        // fragment's DayNight theme gives dark text in light mode, invisible on the dark dialog.
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext());
        EditText input = new EditText(builder.getContext());
        input.setTextColor(ContextCompat.getColor(requireContext(), R.color.text));
        input.setHintTextColor(ContextCompat.getColor(requireContext(), R.color.text_muted));
        input.setHint(R.string.capture_type_hint);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMinLines(3);
        input.setMaxLines(12);
        input.setVerticalScrollBarEnabled(true);
        int pad = (int) (getResources().getDisplayMetrics().density * 20);
        androidx.appcompat.app.AlertDialog dialog = builder
                .setTitle(R.string.capture_type_note)
                .setView(input, pad, pad / 2, pad, 0)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.scribble_done, null)
                .show();
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText().toString();
            if (TextUtils.isEmpty(text.trim())) {
                input.setError(getString(R.string.review_typed_empty));
                return;
            }
            dialog.dismiss();
            Intent i = new Intent(requireContext(), ProcessingActivity.class);
            i.putExtra(ProcessingActivity.EXTRA_TEXT, text);
            startActivity(i);
        });
    }

    @Override
    public void onOpen(Capture c) {
        Intent i = new Intent(requireContext(), CaptureSummaryActivity.class);
        i.putExtra(CaptureSummaryActivity.EXTRA_ID, c.id);
        startActivity(i);
    }
}
