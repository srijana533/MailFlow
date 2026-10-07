package com.mailflow.queue;

/**
 * Signals a permanent email delivery failure that should NOT be retried.
 * Examples: invalid address, permanent SMTP rejection (5xx).
 */
public class PermanentEmailException extends RuntimeException {
    public PermanentEmailException(String message, Throwable cause) {
        super(message, cause);
    }
    public PermanentEmailException(String message) {
        super(message);
    }
}
