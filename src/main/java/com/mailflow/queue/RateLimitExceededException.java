package com.mailflow.queue;

/**
 * Thrown when the email rate limit is exceeded.
 * RabbitMQ treats this as a temporary failure and requeues with backoff.
 */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String message) {
        super(message);
    }
}
