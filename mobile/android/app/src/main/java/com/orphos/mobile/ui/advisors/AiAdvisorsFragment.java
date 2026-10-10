package com.orphos.mobile.ui.advisors;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.orphos.mobile.MainActivity;
import com.orphos.mobile.OrphosApp;
import com.orphos.mobile.R;
import com.orphos.mobile.core.role.Role;
import com.orphos.mobile.data.model.CopilotSession;
import com.orphos.mobile.data.model.DailyDigest;
import com.orphos.mobile.data.model.DecisionItem;
import com.orphos.mobile.databinding.FragmentAiAdvisorsBinding;
import com.orphos.mobile.ui.common.HeaderActions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * AI Advisors (Figma "ORPHOS-1"): the shared search bar (opens Ask Orphos), a proactive AI
 * prompt, the AI recommendation, suggested questions, and the search history.
 * <ul>
 *   <li>Tapping the prompt or a suggestion opens Ask Orphos with that question asked; no
 *       question is answered on this tab itself.</li>
 *   <li>Each answered session is saved to the same per-user store that the web dashboard's
 *       History ▸ AI tab reads.</li>
 *   <li>The history list here reads that store too, so both surfaces show one history:
 *       questions asked on the web appear here, and questions asked here appear there.</li>
 * </ul>
 */
public class AiAdvisorsFragment extends Fragment {

    private FragmentAiAdvisorsBinding binding;
    private HeaderActions header;
    private AdvisorsViewModel viewModel;

    /** Last history loaded from the server; edited optimistically on delete. */
    private List<CopilotSession> sessions = new ArrayList<>();
    /** Ids of the history sessions the user has expanded; kept across refreshes. */
    private final Set<String> expanded = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        binding = FragmentAiAdvisorsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        binding.aiBack.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity m) m.selectTab(R.id.nav_home);
        });
        // Shared header: status dot, bell and search behave exactly as on Home — the search
        // bar opens Ask Orphos, whose conversations land in the same shared history.
        header = HeaderActions.bind(this, binding.getRoot(), binding.aiSearch);

        viewModel = new ViewModelProvider(requireActivity()).get(AdvisorsViewModel.class);

        // The full chat screen (voice dictation, more prompts) is still one tap away.
        binding.aiSeeAll.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AskOrphosActivity.class)));

        binding.aiHistoryClear.setOnClickListener(v -> confirmClearAll());

        // Until the data arrives the suggestions are the role's own questions. They are
        // true for this persona whatever the data says, so nothing invented is on show.
        AdvisorsInsights roleOnly = new AdvisorsInsights(requireContext(), role(), null, null);
        bindSuggestions(roleOnly.roleQuestions());
    }

    // ── Persona- and data-driven cards ────────────────────────────────────────

    /** Bumped per load, so a slow earlier response cannot overwrite a newer one. */
    private int insightsLoad;

    private Role role() {
        return Role.of(OrphosApp.get().config().userRole());
    }

    /** Loads decisions + today's briefing, then rebuilds the prompt, recommendation and suggestions. */
    private void loadInsights() {
        final int load = ++insightsLoad;
        // Both callbacks land on the main thread, so plain holders are enough.
        final AtomicReference<List<DecisionItem>> decisions = new AtomicReference<>();
        final AtomicReference<DailyDigest> digest = new AtomicReference<>();
        final int[] pending = {2};
        Runnable done = () -> {
            if (--pending[0] > 0 || binding == null || load != insightsLoad) return;
            bindInsights(new AdvisorsInsights(requireContext(), role(), decisions.get(), digest.get()));
        };
        OrphosApp.get().repo().decisions((list, fromMock) -> { decisions.set(list); done.run(); });
        OrphosApp.get().repo().dailyDigest((d, fromMock) -> { digest.set(d); done.run(); });
    }

    private void bindInsights(AdvisorsInsights insights) {
        AdvisorsInsights.Prompt prompt = insights.prompt();
        binding.aiPromptText.setText(prompt.text);
        binding.aiPromptQ.setText(prompt.question);
        binding.aiPromptQ.setVisibility(prompt.question != null ? View.VISIBLE : View.GONE);
        binding.aiPrompt.setOnClickListener(prompt.question == null ? null : v -> openAskOrphos(prompt.question));

        bindRecommendation(insights.topDecision());
        bindSuggestions(insights.suggestions());
    }

    /**
     * The recommendation card is about the most urgent pending decision. It shows the
     * decision's own summary at once, then swaps in the first recommendation from its AI
     * annotation, which decision-log tailors to the audience role. A tap opens the decision.
     */
    private void bindRecommendation(@Nullable DecisionItem d) {
        if (d == null) {
            binding.aiRec.setVisibility(View.GONE);
            return;
        }
        binding.aiRec.setVisibility(View.VISIBLE);
        int confidence = d.confidencePercent();
        binding.aiRecLabel.setText(confidence > 0
                ? getString(R.string.advisors_rec_label_fmt, confidence)
                : getString(R.string.home_ai_recommendation));
        binding.aiRec.setOnClickListener(v -> startActivity(
                new Intent(requireContext(), DecisionDetailActivity.class)
                        .putExtra(DecisionDetailActivity.EXTRA_ID, d.id)));

        String cached = viewModel.recommendation(d.id);
        if (cached != null) {
            binding.aiRecText.setText(cached);
            return;
        }
        binding.aiRecText.setText(AdvisorsInsights.provisionalRecommendation(requireContext(), d));

        final int load = insightsLoad;
        final android.content.Context app = requireContext().getApplicationContext();
        OrphosApp.get().repo().annotation(d.id, (a, fromMock) -> {
            String text = null;
            if (a != null && a.recommendations != null) {
                for (String r : a.recommendations) {
                    if (r != null && !r.trim().isEmpty()) { text = r.trim(); break; }
                }
            }
            if (text == null && a != null) text = AdvisorsInsights.reviewRecommendation(app, d);
            // Cached even when this view has gone: the next visit then shows it at once.
            if (text != null && !fromMock) viewModel.putRecommendation(d.id, text);
            if (binding == null || load != insightsLoad) return;
            if (text != null) binding.aiRecText.setText(text);
            // If the annotation failed, the provisional copy stays. It is still true.
        });
    }

    private void bindSuggestions(List<AdvisorsInsights.Suggestion> list) {
        View[] cards = {binding.aiQ1Card, binding.aiQ2Card, binding.aiQ3Card};
        android.widget.TextView[] texts = {binding.aiQ1Text, binding.aiQ2Text, binding.aiQ3Text};
        android.widget.TextView[] tags = {binding.aiQ1Tag, binding.aiQ2Tag, binding.aiQ3Tag};
        android.widget.ImageView[] icons = {binding.aiQ1Icon, binding.aiQ2Icon, binding.aiQ3Icon};
        for (int i = 0; i < cards.length; i++) {
            if (i >= list.size()) {
                cards[i].setVisibility(View.GONE);
                continue;
            }
            AdvisorsInsights.Suggestion s = list.get(i);
            cards[i].setVisibility(View.VISIBLE);
            texts[i].setText(s.question);
            tags[i].setText(s.label);
            int fgRes, bgRes, glyph;
            switch (s.tag) {
                case REVENUE: fgRes = R.color.chip_revenue_fg; bgRes = R.color.chip_revenue_bg; glyph = R.drawable.ic_trending; break;
                case PEOPLE:  fgRes = R.color.chip_people_fg;  bgRes = R.color.chip_people_bg;  glyph = R.drawable.ic_people;   break;
                default:      fgRes = R.color.chip_ops_fg;     bgRes = R.color.chip_ops_bg;     glyph = R.drawable.ic_settings; break;
            }
            int fg = androidx.core.content.ContextCompat.getColor(requireContext(), fgRes);
            int bg = androidx.core.content.ContextCompat.getColor(requireContext(), bgRes);
            tags[i].setTextColor(fg);
            tags[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(bg));
            // The *_bg colours are nearly the card colour, so the circle uses a translucent
            // wash of the category colour instead, with the glyph in full colour on top.
            icons[i].setImageResource(glyph);
            icons[i].setImageTintList(android.content.res.ColorStateList.valueOf(fg));
            icons[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    androidx.core.graphics.ColorUtils.setAlphaComponent(fg, 0x33)));
            cards[i].setOnClickListener(v -> openAskOrphos(s.question));
        }
    }

    // ── Asking ────────────────────────────────────────────────────────────────

    /**
     * Prompt and suggestion taps open Ask Orphos with the question already asked; the chat
     * is saved to the shared history, which this tab re-reads in onResume.
     */
    private void openAskOrphos(String question) {
        startActivity(AskOrphosActivity.askIntent(requireContext(), question));
    }

    // ── History (shared with the web dashboard) ───────────────────────────────

    private void loadHistory() {
        OrphosApp.get().repo().copilotSessions((list, fromMock) -> {
            if (binding == null) return;
            if (list == null) {
                // Offline, or this role lacks the ai-history grant. Never show stale or
                // sample rows as if they were the user's own history.
                sessions = new ArrayList<>();
                renderHistory(getString(R.string.copilot_history_unavailable));
                return;
            }
            sessions = new ArrayList<>(list);
            renderHistory(null);
        });
    }

    /** @param problem copy to show in place of the list, or null to show {@link #sessions}. */
    private void renderHistory(@Nullable String problem) {
        if (binding == null) return;
        boolean empty = sessions.isEmpty();
        binding.aiHistoryEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        binding.aiHistoryEmpty.setText(problem != null ? problem : getString(R.string.copilot_history_empty));
        binding.aiHistoryClear.setVisibility(empty ? View.GONE : View.VISIBLE);
        binding.aiHistorySummary.setVisibility(empty ? View.GONE : View.VISIBLE);

        int questions = 0;
        for (CopilotSession s : sessions) questions += s.questionCount();
        binding.aiHistorySummary.setText(getString(R.string.copilot_history_summary, sessions.size(), questions));

        CopilotHistoryRenderer.renderSessions(binding.aiHistoryList, sessions, expanded, this::deleteSession);
    }

    private void deleteSession(CopilotSession s) {
        sessions.remove(s);                 // optimistic, like the web
        expanded.remove(s.id);
        renderHistory(null);
        OrphosApp.get().repo().deleteCopilotSession(s.id, (ok, fromMock) -> {
            if (!Boolean.TRUE.equals(ok)) failedEdit();
        });
    }

    private void confirmClearAll() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.copilot_history_clear_all)
                .setMessage(R.string.copilot_history_clear_confirm)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.copilot_history_clear, (d, w) -> {
                    sessions.clear();
                    expanded.clear();
                    renderHistory(null);
                    OrphosApp.get().repo().clearCopilotSessions((ok, fromMock) -> {
                        if (!Boolean.TRUE.equals(ok)) failedEdit();
                    });
                })
                .show();
    }

    /** A delete did not reach the server: say so and show what the server still holds. */
    private void failedEdit() {
        if (!isAdded()) return;
        Toast.makeText(requireContext(), R.string.copilot_history_delete_failed, Toast.LENGTH_SHORT).show();
        loadHistory();
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onResume() {
        super.onResume();
        header.refreshStatus();
        // Re-read on every visit: questions asked on the web dashboard, or in Ask Orphos,
        // since the last visit belong in this list.
        loadHistory();
        loadInsights();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
