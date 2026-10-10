package io.orphos.eip.mailinsight.channels;

import io.orphos.eip.mailinsight.graph.GraphItem;
import io.orphos.eip.mailinsight.inference.Classification;
import io.orphos.eip.mailinsight.inference.RuleBasedClassifier;
import org.springframework.stereotype.Component;

/**
 * Hybrid inference for communication-channel messages: the deterministic
 * {@link RuleBasedClassifier} always runs (shared with email — it operates on the
 * normalised {@link GraphItem}), and when an AI provider is configured
 * {@link CommsLlmClassifier} refines it using the channel-specific prompt.
 * Mirrors {@link io.orphos.eip.mailinsight.inference.ClassifierChain}.
 */
@Component
public class CommsClassifierChain {

    private final RuleBasedClassifier rules;
    private final CommsLlmClassifier llm;

    public CommsClassifierChain(RuleBasedClassifier rules, CommsLlmClassifier llm) {
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
