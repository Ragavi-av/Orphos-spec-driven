package io.orphos.eip.nlquery.constants;

import java.util.List;

/**
 * The platform's AI usage types ("functions") an admin can pin a provider and
 * model to, e.g. "email classification should always use in-house Gemma".
 *
 * <p>Assignments are optional per type — an unassigned type falls back to the
 * tenant's default provider exactly as before this feature existed. The list
 * is a build-time registry (not tenant data): each entry corresponds to a
 * concrete completion call-site that passes its {@code usageType} to
 * {@code /api/nl-query/internal/complete} or is resolved inline (copilot).</p>
 */
public final class AiUsageTypes {

    public record UsageType(String id, String label, String description) {}

    public static final String COPILOT              = "copilot";
    public static final String DOCUMENT_SEARCH      = "document-search";
    public static final String DOCUMENT_PROCESSING  = "document-processing";
    public static final String EMAIL_CLASSIFICATION = "email-classification";
    public static final String EMAIL_AGENT          = "email-agent";
    public static final String BRIEFING             = "briefing";
    public static final String AGENT_PLANNING       = "agent-planning";

    public static final List<UsageType> ALL = List.of(
        new UsageType(COPILOT, "AI Co-pilot",
            "Interactive chat answers in the AI Co-pilot side panel (RAG-grounded briefing answers)."),
        new UsageType(DOCUMENT_SEARCH, "Document Search",
            "Answer synthesis over ingested document passages (RAG answer generation)."),
        new UsageType(DOCUMENT_PROCESSING, "Document AI Enrichment",
            "Classifies ingested documents and fills blank metadata fields (doc-ingest-service)."),
        new UsageType(EMAIL_CLASSIFICATION, "Email Classification",
            "Refines the rule-based verdict, summary and rationale for monitored mailbox items."),
        new UsageType(EMAIL_AGENT, "Email Agent Drafting",
            "Drafts replies and meeting requests for AI Actions in the Notifications tab."),
        new UsageType(BRIEFING, "Executive Briefings",
            "Scheduled briefing generation (briefing-service)."),
        new UsageType(AGENT_PLANNING, "Agent Planning",
            "Tool-calling planner loop for goal-driven agents (agent-orchestrator / eip-agent-runtime). "
          + "Assign a tool-calling-capable model — small local models are unreliable at multi-tool use.")
    );

    public static boolean isKnown(String id) {
        if (id == null || id.isBlank()) return false;
        return ALL.stream().anyMatch(t -> t.id().equals(id));
    }

    private AiUsageTypes() {}
}
