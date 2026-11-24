package com.mailflow.template.dto;

import com.mailflow.common.util.EmailContentRules;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Request to render content that has not been saved: the Templates page previews a
 * template while it is created or edited, and the campaign form previews a campaign's
 * own body. Nothing is persisted.
 *
 * <p>The limits bound the work of one preview. The subject limit matches
 * {@link TemplateRenderRequest}. Each body may have up to 500,000 characters, far
 * more than a typical HTML email (Gmail clips messages above about 102 KB). The
 * sample's custom fields follow the limits of a campaign-only address
 * ({@code TargetAddress}): at most 50 fields, keys up to 40 and values up to 1,000
 * characters.
 *
 * @param subject    subject to render; a blank subject renders as an empty string
 * @param htmlBody   HTML body to render (optional)
 * @param plainBody  plain-text body to render (optional)
 * @param recipient  sample values used to resolve placeholders
 * @param customBody a campaign's own plain-text body (optional, same limits as on a
 *                   campaign). When it is not blank it is rendered exactly as a send
 *                   renders it: {@code plainBody} is the rendered text and
 *                   {@code htmlBody} the HTML part made from it, and the request's
 *                   {@code htmlBody} and {@code plainBody} are not used.
 */
public record TemplateContentRenderRequest(
    @Size(max = 1000, message = "must be at most 1,000 characters") String subject,
    @Size(max = 500_000, message = "must be at most 500,000 characters") String htmlBody,
    @Size(max = 500_000, message = "must be at most 500,000 characters") String plainBody,
    @NotNull @Valid Sample recipient,
    @Size(max = EmailContentRules.BODY_MAX_LENGTH, message = EmailContentRules.BODY_TOO_LONG)
    @Pattern(regexp = EmailContentRules.BODY_PATTERN, message = EmailContentRules.BODY_CONTROL_CHARACTERS)
    String customBody
) {
    /**
     * Sample recipient. The same fields as {@link TemplateRenderRequest.Sample}, with
     * bounded custom fields because this request carries no saved contact data.
     */
    public record Sample(
        @Size(max = 255) String email,
        @Size(max = 255) String name,
        @Size(max = 255) String firstName,
        @Size(max = 255) String lastName,
        @Size(max = 255) String company,
        @Size(max = 255) String course,
        /** Extra placeholder values, for example {@code {"Team": "Platform"}} for {@code {{Team}}}. */
        @Size(max = 50) Map<@Size(max = 40) String, @Size(max = 1000) String> fields
    ) {
        public TemplateRenderRequest.Sample asRenderSample() {
            return new TemplateRenderRequest.Sample(email, name, firstName, lastName, company, course, fields);
        }
    }
}
