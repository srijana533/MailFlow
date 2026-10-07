package com.mailflow.common.util;

import java.util.UUID;

/**
 * Utility for generating URL-safe identifiers and tokens.
 */
public final class SlugUtils {

    private SlugUtils() {}

    /** Generates a random URL-safe token (UUID without dashes). */
    public static String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    /** Converts a display name to a URL-safe slug. */
    public static String toSlug(String name) {
        if (name == null) return "";
        return name.trim()
                   .toLowerCase()
                   .replaceAll("[^a-z0-9\\s-]", "")
                   .replaceAll("\\s+", "-")
                   .replaceAll("-+", "-");
    }
}
