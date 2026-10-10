package io.orphos.eip.mcp.priority.exception;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Checked failure raised by the service layer when a priority tool cannot be answered. Checked
 * rather than runtime on purpose: the design requires that a tool call never throws into the
 * planner loop, so making it checked forces {@code PriorityTools} to decide, at compile time, how
 * each call degrades.
 */
public class EIPMCPException extends Exception {

    private static final long serialVersionUID = 1L;

    private final Error error;

    public EIPMCPException(Error error) {
        super(error.getMessage());
        this.error = error;
    }

    public EIPMCPException(Error error, Throwable cause) {
        super(error.getMessage(), cause);
        this.error = error;
        this.error.setErrorStackTrace(stackTraceOf(cause));
    }

    public Error getError() {
        return error;
    }

    private static String stackTraceOf(Throwable cause) {
        if (cause == null) {
            return null;
        }
        StringWriter writer = new StringWriter();
        cause.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
