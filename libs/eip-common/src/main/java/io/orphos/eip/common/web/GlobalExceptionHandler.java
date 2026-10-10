package io.orphos.eip.common.web;

import io.orphos.eip.common.tenant.TenantDataUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into stable {@link ApiError} responses. Wired into
 * every service via {@link CommonWebAutoConfiguration}; individual services
 * may add their own {@link org.springframework.web.bind.annotation.ControllerAdvice}
 * for service-specific exceptions, which take precedence over this one.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LogManager.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .orElse("validation failed");
        return ResponseEntity.badRequest().body(ApiError.of("validation_error", msg));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraint(ConstraintViolationException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("validation_error", ex.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiError.of("upload_too_large", "file exceeds permitted size"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadArg(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(ApiError.of("bad_request", safe(ex.getMessage())));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleConflict(IllegalStateException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("conflict", safe(ex.getMessage())));
    }

    @ExceptionHandler(TenantDataUnavailableException.class)
    public ResponseEntity<ApiError> handleTenantUnavailable(TenantDataUnavailableException ex) {
        log.warn("Tenant data store unavailable: tenant={} store={} cause={}",
                ex.getTenantId(), ex.getStore(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("X-Tenant-DB-Status", "unavailable")
                .header("X-Tenant-DB-Store", ex.getStore() == null ? "" : ex.getStore())
                .body(ApiError.of("tenant_data_unavailable",
                        "Tenant data store is temporarily unavailable; please retry shortly."));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found", "no handler for " + ex.getResourcePath()));
    }

    // Controllers signal deliberate statuses (404 lookup misses, 400 bad
    // input) via ResponseStatusException — pass them through, or the
    // catch-all below masks them as 500 internal_error.
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        String code = status != null
                ? status.name().toLowerCase()
                : "http_" + ex.getStatusCode().value();
        return ResponseEntity.status(ex.getStatusCode())
                .body(ApiError.of(code, safe(ex.getReason())));
    }

    // A @PreAuthorize denial (or an explicit authorization check) thrown from inside a
    // controller reaches the MVC exception resolvers before Spring Security's
    // ExceptionTranslationFilter, so without this the catch-all below would turn every
    // authorization refusal into a 500.
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("forbidden", safe(ex.getMessage())));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleAny(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("internal_error", "unexpected error"));
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "request rejected" : s;
    }
}
