package com.mailflow.ai;

/**
 * Abstraction over AI content generation providers.
 *
 * <p>Current implementations:
 * <ul>
 *   <li>{@link OpenAiContentProvider} — calls OpenAI Chat API</li>
 *   <li>{@link NoOpAiContentProvider} — fallback when AI is disabled</li>
 * </ul>
 */
public interface AiContentProvider {

    /**
     * Generates an email subject and body from a natural-language prompt.
     *
     * @param prompt user's description of the email they want
     * @return generated content with subject and body
     */
    AiGeneratedContent generate(String prompt);

    /**
     * Improves/polishes an existing email draft (subject + body), preserving the
     * user's meaning, facts, links and calls to action. The result is returned
     * for human review — it is never sent automatically.
     *
     * @param request the draft plus optional purpose/audience/tone/key points
     * @return the improved subject and body
     */
    ImprovedEmail improve(ImproveEmailRequest request);

    /** Returns true if AI generation is available. */
    boolean isAvailable();
}
