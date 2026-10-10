package io.orphos.eip.agent.spec;

/**
 * One tool advertised by an MCP server (TO-BE design §5.2). Carries the read/write classification
 * and approval flag that the orchestrator and approval store rely on (§5.1 classification).
 *
 * @param name             fully-qualified tool name, {@code capability.action} — e.g. {@code "priority.get_queue"}
 * @param description      human-readable description surfaced to the planner
 * @param write            {@code true} if the tool mutates state (vs. a read-only query)
 * @param approvalRequired {@code true} if executing it must go through the approval store first
 * @param prepareTool      for a write tool bound to a confirmation token, the name of the companion
 *                         (side-effect-free) tool that (re)issues that token — called by the approval
 *                         "edit-before-approve" flow to re-mint a token after a human edits the
 *                         proposed content. {@code null} for tools with no token binding.
 * @param confirmationTokenField argument key the (re)issued token is written into (e.g.
 *                         {@code "confirmationToken"}). {@code null} when {@code prepareTool} is null.
 */
public record ToolDescriptor(
        String name,
        String description,
        boolean write,
        boolean approvalRequired,
        String prepareTool,
        String confirmationTokenField) {

    /** Convenience constructor for tools with no confirmation-token binding. */
    public ToolDescriptor(String name, String description, boolean write, boolean approvalRequired) {
        this(name, description, write, approvalRequired, null, null);
    }
}
