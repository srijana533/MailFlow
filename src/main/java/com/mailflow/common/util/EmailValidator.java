package com.mailflow.common.util;

import java.util.regex.Pattern;

/**
 * Lightweight email format validator.
 * More reliable than Jakarta's @Email for edge-case addresses.
 */
public final class EmailValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
        "^[a-zA-Z0-9._%+\\-]+@[a-zA-Z0-9.\\-]+\\.[a-zA-Z]{2,}$"
    );

    private EmailValidator() {}

    public static boolean isValid(String email) {
        if (email == null || email.isBlank()) return false;
        return EMAIL_PATTERN.matcher(email.trim()).matches();
    }

    public static void requireValid(String email) {
        if (!isValid(email)) {
            throw new IllegalArgumentException("Invalid email address: " + email);
        }
    }
}
