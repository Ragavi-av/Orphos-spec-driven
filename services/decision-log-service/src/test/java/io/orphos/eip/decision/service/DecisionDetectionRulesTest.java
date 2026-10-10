package io.orphos.eip.decision.service;

import io.orphos.eip.core.ai.NlQueryInternalClient;
import io.orphos.eip.core.ai.NlQueryProperties;
import io.orphos.eip.decision.util.DecisionLogProperties;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rule-based Decision_Detection_Engine behavior with the LLM leg disabled
 * (no token → NlQueryInternalClient.isEnabled() == false), covering the
 * deterministic floor of Req 3: decision-language flagging with confidence
 * scores, bilingual patterns, and the priority association scale.
 */
class DecisionDetectionRulesTest {

    private DecisionDetectionService service() {
        NlQueryProperties nlProps = new NlQueryProperties();   // blank token → LLM disabled
        DecisionLogProperties props = new DecisionLogProperties();
        return new DecisionDetectionService(new NlQueryInternalClient(nlProps), props);
    }

    private static final String TENANT = "00000000-0000-0000-0000-000000000001";

    @Test
    void strongDecisionLanguageIsFlagged() {
        Optional<DecisionDetectionService.DetectedDecision> hit = service().detect(TENANT,
            "Budget sign-off", "The board approved the Q3 infrastructure budget and we will proceed with the migration.",
            "CFO");
        assertTrue(hit.isPresent());
        assertTrue(hit.get().confidence() >= 0.5);
        assertEquals("RULES", hit.get().detectedBy());
    }

    @Test
    void japaneseDecisionLanguageIsFlagged() {
        Optional<DecisionDetectionService.DetectedDecision> hit = service().detect(TENANT,
            "新システムについて", "新しい経費精算システムの導入が承認されました。来月から展開します。", "");
        assertTrue(hit.isPresent());
    }

    @Test
    void neutralTextIsNotFlagged() {
        Optional<DecisionDetectionService.DetectedDecision> hit = service().detect(TENANT,
            "Weekly newsletter", "Here are this week's engineering blog posts and upcoming events.", "");
        assertTrue(hit.isEmpty());
    }

    @Test
    void weakSignalsAloneStayBelowStrongConfidence() {
        DecisionDetectionService s = service();
        double weak = s.ruleConfidence("the team reached consensus on the venue");
        double strong = s.ruleConfidence("the proposal was approved and signed off");
        assertTrue(weak <= 0.55, "weak-only signal must stay <= 0.55, was " + weak);
        assertTrue(strong > weak);
    }

    @Test
    void emptyTextIsRejected() {
        assertTrue(service().detect(TENANT, "", "   ", "").isEmpty());
    }

    @Test
    void priorityScoreTracksLevelAndConfidence() {
        double critical = DecisionDetectionService.priorityScore("CRITICAL", 1.0);
        double low = DecisionDetectionService.priorityScore("LOW", 1.0);
        assertTrue(critical > low);
        assertTrue(DecisionDetectionService.priorityScore("HIGH", 1.0)
            > DecisionDetectionService.priorityScore("HIGH", 0.5));
        assertTrue(critical <= 100);
    }
}
