package io.orphos.eip.mcp.mail.exception;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Checked failure raised when a <em>non-sending</em> mail tool cannot be answered. Checked rather
 * than runtime on purpose: it forces {@code MailTools} to decide, at compile time, how each read or
 * draft degrades, since those must never throw into the planner loop.
 *
 * <p>Deliberately <strong>not</strong> used on the send path. A failed send must surface loudly and
 * unchecked so the approval flow marks the ProposedAction FAILED rather than recording a send that
 * never happened — see {@code MailInsightClient#postStrict}.
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
