package io.orphos.eip.mcp.spec;

import java.util.regex.Pattern;

/**
 * The MCP tool-naming convention (TO-BE design §5.1): every tool is named
 * {@code capability.action} — e.g. {@code priority.get_queue}, {@code rag.search_documents},
 * {@code decision.log_decision}. Agents reference tools purely by this <em>name</em>; the gateway
 * resolves the name to whichever live server advertises it. That indirection is what satisfies
 * invariant I2 (no agent depends on a specific MCP-server module).
 *
 * <p>This is a value type + validator, no logic beyond parsing/formatting — it stays a contract,
 * not a coupling point.</p>
 *
 * @param capability the capability segment, e.g. {@code "priority"}
 * @param action     the action segment, e.g. {@code "get_queue"}
 */
public record McpToolName(String capability, String action) {

    /** Each segment: lowercase start, then lowercase/digits/underscores. */
    private static final Pattern SEGMENT = Pattern.compile("[a-z][a-z0-9_]*");

    public McpToolName {
        if (!SEGMENT.matcher(capability == null ? "" : capability).matches()) {
            throw new IllegalArgumentException("Invalid capability segment: " + capability);
        }
        if (!SEGMENT.matcher(action == null ? "" : action).matches()) {
            throw new IllegalArgumentException("Invalid action segment: " + action);
        }
    }

    /**
     * Parses a {@code capability.action} tool name. Rejects anything that is not exactly two
     * dot-separated, convention-valid segments.
     */
    public static McpToolName parse(String toolName) {
        if (toolName == null) {
            throw new IllegalArgumentException("Tool name must not be null");
        }
        int dot = toolName.indexOf('.');
        if (dot <= 0 || dot != toolName.lastIndexOf('.') || dot == toolName.length() - 1) {
            throw new IllegalArgumentException(
                    "Tool name must be exactly 'capability.action': " + toolName);
        }
        return new McpToolName(toolName.substring(0, dot), toolName.substring(dot + 1));
    }

    /** {@code true} if {@code toolName} is a syntactically valid {@code capability.action} name. */
    public static boolean isValid(String toolName) {
        try {
            parse(toolName);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The full {@code capability.action} name. */
    public String qualified() {
        return capability + '.' + action;
    }

    @Override
    public String toString() {
        return qualified();
    }
}
