package com.orphos.mobile.ui.advisors;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.DecisionItem;
import com.orphos.mobile.ui.home.DecisionAdapter;

import java.util.ArrayList;
import java.util.List;

/** AI Advisors — the full decision queue with All / Urgent / Important / Future filters. */
public class AdvisorsFragment extends Fragment implements DecisionAdapter.Listener {

    private DecisionAdapter adapter;
    private final List<DecisionItem> all = new ArrayList<>();
    private int filterId = R.id.chip_all;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_advisors, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        adapter = new DecisionAdapter(this);
        RecyclerView list = view.findViewById(R.id.advisor_list);
        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        list.setAdapter(adapter);

        ChipGroup group = view.findViewById(R.id.filter_group);
        group.setOnCheckedStateChangeListener((g, ids) -> {
            filterId = ids.isEmpty() ? R.id.chip_all : ids.get(0);
            applyFilter();
        });

        load();
    }

    private void load() {
        OrphosApp.get().repo().decisions((data, fromMock) -> {
            all.clear();
            all.addAll(data);
            applyFilter();
        });
    }

    private void applyFilter() {
        List<DecisionItem> out = new ArrayList<>();
        for (DecisionItem d : all) {
            String p = d.priorityLevel == null ? "" : d.priorityLevel.toUpperCase();
            boolean keep;
            if (filterId == R.id.chip_urgent) {
                keep = p.equals("CRITICAL") || p.equals("HIGH");
            } else if (filterId == R.id.chip_important) {
                keep = p.equals("MEDIUM");
            } else if (filterId == R.id.chip_future) {
                keep = p.equals("LOW") || "DETECTED".equalsIgnoreCase(d.status);
            } else {
                keep = true;
            }
            if (keep) out.add(d);
        }
        adapter.submit(out);
    }

    // --- DecisionAdapter.Listener ---

    @Override
    public void onOpen(DecisionItem d) {
        openDetail(d);
    }

    @Override
    public void onAnalysis(DecisionItem d) {
        openDetail(d);
    }

    @Override
    public void onApprove(DecisionItem d) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.confirm_approve_title)
                .setMessage(getString(R.string.confirm_approve_msg, d.title))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.decision_approve, (dlg, w) ->
                        OrphosApp.get().repo().updateDecisionStatus(d.id, "APPROVED", (updated, fromMock) -> {
                            if (getView() != null) {
                                Snackbar.make(getView(), updated == null
                                                ? getString(R.string.action_not_saved)
                                                : getString(R.string.approved_toast, d.title),
                                        Snackbar.LENGTH_SHORT).show();
                            }
                            load();
                        }))
                .show();
    }

    private void openDetail(DecisionItem d) {
        Intent i = new Intent(requireContext(), DecisionDetailActivity.class);
        i.putExtra(DecisionDetailActivity.EXTRA_ID, d.id);
        startActivity(i);
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }
}
