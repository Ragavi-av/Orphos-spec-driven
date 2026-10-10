package com.orphos.mobile.ui.advisors;

import androidx.lifecycle.ViewModel;

/**
 * Holds the Advisors tab's state at activity scope. MainActivity builds a new fragment on
 * every bottom-nav switch, so without this each tab visit would refetch what it already has.
 * Questions are not answered here; the tab hands them to {@link AskOrphosActivity}.
 */
public class AdvisorsViewModel extends ViewModel {

    /**
     * AI recommendation text per decision id. The annotation is an LLM call that can take
     * about 30 s, so it is fetched once per activity rather than on every tab visit.
     */
    private final java.util.Map<String, String> recommendations = new java.util.HashMap<>();

    @androidx.annotation.Nullable
    public String recommendation(String decisionId) {
        return recommendations.get(decisionId);
    }

    public void putRecommendation(String decisionId, String text) {
        recommendations.put(decisionId, text);
    }
}
