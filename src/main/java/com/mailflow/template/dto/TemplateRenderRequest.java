package com.mailflow.template.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Request to render a template for one specific recipient (personalised preview).
 *
 * <p>The recipient is supplied inline so a preview also works for addresses that
 * are not saved contacts yet (uploaded or typed). Nothing is persisted.
 *
 * @param subject   optional campaign subject. When blank, the template's own subject
 *                  is rendered. Campaign sends use the campaign subject, so the
 *                  campaign form passes it here to preview exactly what is sent.
 * @param recipient the recipient values used to resolve placeholders.
 */
public record TemplateRenderRequest(
    @Size(max = 1000) String subject,
    @NotNull @Valid Sample recipient
) {
    public record Sample(
        @Size(max = 255) String email,
        @Size(max = 255) String name,
        @Size(max = 255) String firstName,
        @Size(max = 255) String lastName,
        @Size(max = 255) String company,
        @Size(max = 255) String course,
        /** Custom fields (role, phone, location, …) exactly as stored on a contact. */
        @Size(max = 100) Map<String, String> fields
    ) {}
}
