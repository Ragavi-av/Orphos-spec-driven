package io.orphos.eip.mailinsight.inference;

import io.orphos.eip.mailinsight.graph.GraphItem;
import org.springframework.stereotype.Component;

/**
 * Orchestrates the hybrid inference: the deterministic {@link RuleBasedClassifier}
 * always runs and produces a guaranteed verdict; when the tenant has an AI
 * provider configured, {@link LlmClassifier} refines it. Any LLM failure falls
 * back to the rule baseline.
 */
@Component
public class ClassifierChain {

    private final RuleBasedClassifier rules;
    private final LlmClassifier llm;

    public ClassifierChain(RuleBasedClassifier rules, LlmClassifier llm) {
        this.rules = rules;
        this.llm = llm;
    }

    public Classification classify(GraphItem item) {
        Classification base = rules.classify(item);
        if (llm.isAvailable()) {
            return llm.enrich(item, base);
        }
        return base;
    }
}
