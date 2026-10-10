package io.orphos.eip.mailinsight.graph;

/** Raised when a Microsoft Graph call (token or data) fails. */
public class GraphException extends RuntimeException {
    public GraphException(String message) {
        super(message);
    }

    public GraphException(String message, Throwable cause) {
        super(message, cause);
    }
}
