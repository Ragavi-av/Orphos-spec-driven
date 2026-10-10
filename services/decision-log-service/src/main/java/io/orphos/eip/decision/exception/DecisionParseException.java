package io.orphos.eip.decision.exception;

import io.orphos.eip.decision.vo.DecisionDocument;
import java.util.List;

/**
 * Thrown when a document cannot be parsed into a {@link DecisionDocument}.
 * Carries one issue per problem with its location (Req 12.2: descriptive
 * error messages with specific issue locations).
 */
public class DecisionParseException extends RuntimeException {

    /** One parse problem. {@code line} is 1-based; 0 = whole document. */
    public record Issue(int line, String message) {}

    private final transient List<Issue> issues;

    public DecisionParseException(List<Issue> issues) {
        super(issues.isEmpty() ? "Unparseable document"
            : issues.stream()
                .map(i -> (i.line() > 0 ? "line " + i.line() + ": " : "") + i.message())
                .reduce((a, b) -> a + "; " + b).orElse(""));
        this.issues = List.copyOf(issues);
    }

    public List<Issue> issues() {
        return issues;
    }
}
