package io.orphos.eip.agent.runtime;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Map;

/**
 * Resolves an {@link io.orphos.eip.agent.spec.AgentManifest#goalPromptKey() goalPromptKey} into the
 * system prompt the planner runs with (TO-BE design §5.2). Prompts are no longer hardcoded in the
 * agent's compiled manifest — they are ops-editable configuration.
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>a configured override under {@code eip.agent.runtime.prompts.<key>} (env/config editable);</li>
 *   <li>otherwise, if the value looks like an inline literal (contains whitespace), it is used
 *       verbatim — a pragmatic fallback so an agent can still ship a default prompt;</li>
 *   <li>otherwise the key is returned unchanged with a warning, so a missing prompt is loud, not silent.</li>
 * </ol>
 *
 * <p>Wiring this to nl-query's admin-editable prompt store (design §5.2) is the next step; this
 * resolver is the seam that makes that a one-bean swap without touching agents.</p>
 */
public class GoalPromptResolver {

    private static final Logger log = LogManager.getLogger(GoalPromptResolver.class);

    private final Map<String, String> prompts;

    public GoalPromptResolver(Map<String, String> prompts) {
        this.prompts = prompts == null ? Map.of() : prompts;
    }

    /** Resolves {@code goalPromptKey} to the system prompt text. */
    public String resolve(String goalPromptKey) {
        if (goalPromptKey == null || goalPromptKey.isBlank()) {
            return "";
        }
        String configured = prompts.get(goalPromptKey);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        // Inline literal (whitespace ⇒ prose, not a key): use as-is for back-compat.
        if (goalPromptKey.strip().indexOf(' ') >= 0) {
            return goalPromptKey;
        }
        log.warn("No prompt configured for goalPromptKey '{}' (eip.agent.runtime.prompts.{}); "
                + "using the key itself as the prompt.", goalPromptKey, goalPromptKey);
        return goalPromptKey;
    }
}
