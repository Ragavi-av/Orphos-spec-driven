package io.orphos.eip.common.web;

import java.time.Instant;

/**
 * Stable error envelope used by every EIP service.
 * <p>{@code code} is a machine-readable enum-like string ({@code validation_error},
 * {@code conflict}, {@code internal_error} …) so the SPA can branch on it without
 * parsing free-form messages. {@code message} is the human-readable one-liner.</p>
 */
public record ApiError(String code, String message, Instant timestamp) {
    public static ApiError of(String code, String message) {
        return new ApiError(code, message, Instant.now());
    }
}
