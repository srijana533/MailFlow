package com.mailflow.recipient.dto;

import com.mailflow.common.util.EmailValidator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * Create or update a saved contact. The email is normalised on binding
 * ({@link EmailValidator#normalize}: trimmed, including invisible characters, and
 * lower-cased), so validation and storage see the same value.
 */
public record RecipientRequest(
    @NotBlank @Email @Size(max = 255) String email,
    @Size(max = 255) String name,
    @Size(max = 100) String firstName,
    @Size(max = 100) String lastName,
    @Size(max = 255) String company,
    @Size(max = 255) String course,
    Map<String, String> customFields
) {
    public RecipientRequest {
        email = EmailValidator.normalize(email);
    }
}
