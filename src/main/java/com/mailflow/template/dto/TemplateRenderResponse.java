package com.mailflow.template.dto;

import java.util.List;

/**
 * A template rendered for one recipient.
 *
 * @param emptyVariables placeholders used by the subject or body that resolve to an
 *                       empty value for this recipient (for example {@code Company}
 *                       when the contact has no company). They render as blank text,
 *                       never as a raw {@code {{placeholder}}}.
 */
public record TemplateRenderResponse(
    String subject,
    String htmlBody,
    String plainBody,
    List<String> emptyVariables
) {}
