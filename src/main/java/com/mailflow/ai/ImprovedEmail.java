package com.mailflow.ai;

/**
 * AI-improved email draft returned to the user for review.
 *
 * <p>The user reviews, optionally edits, then accepts or discards this draft.
 * Nothing is sent automatically.
 */
public record ImprovedEmail(
    String subject,
    String body
) {}
