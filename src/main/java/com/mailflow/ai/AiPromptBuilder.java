package com.mailflow.ai;

/**
 * Builds the prompts used for AI-assisted email improvement. Centralised so the
 * same guardrails apply to every provider (Gemini, OpenAI) and can be unit-tested.
 */
final class AiPromptBuilder {

    private AiPromptBuilder() {}

    /**
     * System instruction shared by all providers. Emphasises that the model must
     * preserve meaning/facts/links/CTAs and must NOT invent recipient details,
     * offers, discounts or business facts. Output is strict JSON.
     */
    static final String IMPROVE_SYSTEM_PROMPT = """
            You are an expert email copy editor for a legitimate email marketing platform.
            You improve the clarity, flow, grammar and professionalism of an email draft.

            Strict rules:
            - Preserve the author's intended meaning and all factual details.
            - Preserve every link, URL, offer term, price, date and call to action exactly.
            - Do NOT invent recipient names, personal details, promises, discounts, statistics,
              or any business facts that are not in the original draft.
            - Keep any {{merge_variables}} such as {{Name}}, {{FirstName}}, {{Company}}, {{Role}},
              {{Location}}, {{Interest}} intact and in place. Never remove or rename them.
            - Do not add tracking pixels, scripts, or unsubscribe text unless already present.
            - Return plain text or simple HTML consistent with the input; do not wrap in markdown.

            Respond with ONLY a JSON object, no commentary, in exactly this shape:
            {"subject": "...", "body": "..."}
            """;

    /** Builds the per-request user prompt from the draft + optional context. */
    static String buildImprovePrompt(ImproveEmailRequest r) {
        StringBuilder sb = new StringBuilder();
        sb.append("Improve the following email draft.\n\n");
        if (notBlank(r.tone())) {
            sb.append("Desired tone: ").append(r.tone().trim()).append("\n");
        }
        if (notBlank(r.purpose())) {
            sb.append("Campaign purpose: ").append(r.purpose().trim()).append("\n");
        }
        if (notBlank(r.audience())) {
            sb.append("Target audience: ").append(r.audience().trim()).append("\n");
        }
        if (notBlank(r.keyPoints())) {
            sb.append("Key points that MUST be retained: ").append(r.keyPoints().trim()).append("\n");
        }
        sb.append("\n--- CURRENT SUBJECT ---\n").append(r.subject());
        sb.append("\n\n--- CURRENT BODY ---\n").append(r.body());
        sb.append("\n\nReturn the improved subject and body as JSON: {\"subject\":\"...\",\"body\":\"...\"}");
        return sb.toString();
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
