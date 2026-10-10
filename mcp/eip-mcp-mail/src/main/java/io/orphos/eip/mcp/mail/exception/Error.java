package io.orphos.eip.mcp.mail.exception;

/**
 * The error model carried by {@link EIPMCPException}: a human-readable message, a stable machine
 * code (see {@code AppConstant.ERROR_CODE_*}) callers can branch on, and — when the failure came
 * from a caught throwable — the originating stack trace for the logs.
 */
public class Error {

    private String message;
    private String code;
    private String errorStackTrace;

    public Error(String message) {
        this.message = message;
    }

    public Error(String message, String code) {
        this.message = message;
        this.code = code;
    }

    public Error(String message, String code, String errorStackTrace) {
        this.message = message;
        this.code = code;
        this.errorStackTrace = errorStackTrace;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getErrorStackTrace() {
        return errorStackTrace;
    }

    public void setErrorStackTrace(String errorStackTrace) {
        this.errorStackTrace = errorStackTrace;
    }
}
