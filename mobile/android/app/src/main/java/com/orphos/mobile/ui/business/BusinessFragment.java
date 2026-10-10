package com.orphos.mobile.ui.business;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.data.model.TeamIntelligence;

import java.util.Locale;

/**
 * Business — Team Intelligence: productivity, availability, and wellbeing/burnout.
 * Note: per-function productivity and availability are BFF-aggregated in production
 * (no single live endpoint yet); burnout maps to okg-reasoning OverloadedPerson insights.
 */
public class BusinessFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_business, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        OrphosApp.get().repo().teamIntelligence((t, fromMock) -> {
            if (!isAdded()) return;
            ((TextView) view.findViewById(R.id.business_period)).setText(t.period);
            renderProductivity(view.findViewById(R.id.productivity_container), t);
            renderAvailability(view.findViewById(R.id.availability_group), t);
            renderBurnout(view.findViewById(R.id.burnout_container), t);
        });
    }

    private void renderProductivity(LinearLayout container, TeamIntelligence t) {
        LayoutInflater inf = LayoutInflater.from(requireContext());
        container.removeAllViews();
        for (TeamIntelligence.Productivity p : t.productivity) {
            View row = inf.inflate(R.layout.item_productivity, container, false);
            ((TextView) row.findViewById(R.id.prod_function)).setText(p.function);
            ((TextView) row.findViewById(R.id.prod_percent))
                    .setText(String.format(Locale.getDefault(), "%d%%", p.percent));
            LinearProgressIndicator bar = row.findViewById(R.id.prod_bar);
            bar.setMax(100);
            bar.setProgressCompat(p.percent, false);
            container.addView(row);
        }
    }

    private void renderAvailability(ChipGroup group, TeamIntelligence t) {
        group.removeAllViews();
        for (TeamIntelligence.Availability a : t.availability) {
            Chip chip = new Chip(requireContext());
            chip.setText(String.format(Locale.getDefault(), "%s · %d", a.status, a.count));
            chip.setClickable(false);
            group.addView(chip);
        }
    }

    private void renderBurnout(LinearLayout container, TeamIntelligence t) {
        LayoutInflater inf = LayoutInflater.from(requireContext());
        container.removeAllViews();
        for (TeamIntelligence.Burnout b : t.burnout) {
            View card = inf.inflate(R.layout.item_burnout, container, false);
            ((TextView) card.findViewById(R.id.burnout_assessment)).setText(b.assessment);
            ((TextView) card.findViewById(R.id.burnout_subject)).setText(b.subject);
            ((TextView) card.findViewById(R.id.burnout_signal)).setText(b.signal);
            ((TextView) card.findViewById(R.id.burnout_action)).setText(b.suggestedAction);
            card.findViewById(R.id.burnout_share).setOnClickListener(v -> shareAction(b));
            container.addView(card);
        }
    }

    /** Opens the system share sheet — the user picks the recipient and sends. */
    private void shareAction(TeamIntelligence.Burnout b) {
        String text = b.subject + " — " + b.assessment + ".\nSuggested action: " + b.suggestedAction;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.business_wellbeing));
        send.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, getString(R.string.business_share_mail)));
    }
}
