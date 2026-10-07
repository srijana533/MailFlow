package com.mailflow.common.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * Standardised API error response body.
 *
 * <pre>
 * {
 *   "timestamp": "2026-10-02T10:30:00Z",
 *   "status": 400,
 *   "error": "VALIDATION_ERROR",
 *   "message": "Invalid email address",
 *   "path": "/api/recipients",
 *   "fieldErrors": [ ... ]
 * }
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
    Instant timestamp,
    int status,
    String error,
    String message,
    String path,
    List<FieldError> fieldErrors
) {
    public ApiError(int status, String error, String message, String path) {
        this(Instant.now(), status, error, message, path, null);
    }

    public ApiError(int status, String error, String message, String path,
                    List<FieldError> fieldErrors) {
        this(Instant.now(), status, error, message, path, fieldErrors);
    }

    public record FieldError(String field, String message) {}
}
