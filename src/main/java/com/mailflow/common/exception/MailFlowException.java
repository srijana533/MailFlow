package com.mailflow.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Base checked exception for all application-layer errors.
 * Subclasses carry the HTTP status that the global handler maps to.
 */
public class MailFlowException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public MailFlowException(HttpStatus status, String errorCode, String message) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public MailFlowException(HttpStatus status, String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
