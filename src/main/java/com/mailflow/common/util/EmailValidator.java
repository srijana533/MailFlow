package com.mailflow.common.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Email address normalisation and format validation: the single rule used for
 * every recipient address (upload preview, bulk create/reuse, lookup, campaign-only
 * addresses, contact create/update, imports and the unsubscribe list).
 *
 * <p>Normalising an address trims it, then lower-cases it with {@link Locale#ROOT}.
 * Trimming removes, from both ends only, everything {@link String#trim()} and
 * {@link String#strip()} remove plus the characters they miss: the non-breaking
 * spaces U+00A0, U+2007 and U+202F, and the invisible U+200B (zero-width space),
 * U+200C, U+200D, U+2060 (word joiner) and U+FEFF (byte-order mark). Characters
 * inside an address are never removed, so such an address stays invalid.
 *
 * <p>The frontend applies the same rule ({@code normalizeEmail} in
 * {@code frontend/src/lib/recipientSelection.ts}, same characters) and the same
 * pattern ({@code EMAIL_RE}); {@code EmailValidatorTest} checks that the two patterns
 * are identical.
 */
public final class EmailValidator {

    /** Address format, written exactly as the frontend's {@code EMAIL_RE}. */
    public static final String PATTERN_SOURCE = "^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}$";

    private static final Pattern EMAIL_PATTERN = Pattern.compile(PATTERN_SOURCE);

    /**
     * {@code Display Name <address>}, the name optionally in double quotes. The two
     * alternatives can't overlap (a quoted name, or a run without quotes or brackets),
     * so matching stays linear on long untrusted cells.
     */
    private static final Pattern NAME_AND_ADDRESS = Pattern.compile("^(?:\"[^\"]*\"\\s*|[^\"<>]*)<([^<>]*)>$");

    private static final String MAILTO = "mailto:";

    private EmailValidator() {}

    /**
     * Whether {@code c} is removed from the ends of an address: C0 controls and
     * space (U+0000-U+0020), U+00A0, U+1680, U+2000-U+200D, U+2028, U+2029, U+202F,
     * U+205F, U+2060, U+3000 and U+FEFF.
     */
    public static boolean isTrimmable(char c) {
        return c <= '\u0020'
                || c == '\u00A0' || c == '\u1680'
                || (c >= '\u2000' && c <= '\u200D')
                || c == '\u2028' || c == '\u2029' || c == '\u202F' || c == '\u205F'
                || c == '\u2060' || c == '\u3000' || c == '\uFEFF';
    }

    /** Removes {@link #isTrimmable} characters from both ends; {@code null} stays {@code null}. */
    public static String trim(String raw) {
        if (raw == null) return null;
        int start = 0;
        int end = raw.length();
        while (start < end && isTrimmable(raw.charAt(start))) start++;
        while (end > start && isTrimmable(raw.charAt(end - 1))) end--;
        return raw.substring(start, end);
    }

    /**
     * The normalised form of an address, used to validate, compare, look up and
     * store it: {@link #trim} then lower-case ({@link Locale#ROOT}). {@code null}
     * stays {@code null}.
     */
    public static String normalize(String raw) {
        String trimmed = trim(raw);
        return trimmed == null ? null : trimmed.toLowerCase(Locale.ROOT);
    }

    /** Whether the address is valid once {@link #normalize normalised}. */
    public static boolean isValid(String email) {
        String normalized = normalize(email);
        return normalized != null && !normalized.isEmpty() && EMAIL_PATTERN.matcher(normalized).matches();
    }

    public static void requireValid(String email) {
        if (!isValid(email)) {
            throw new IllegalArgumentException("Invalid email address: " + email);
        }
    }

    /**
     * The address in a spreadsheet cell as people paste it: {@code Ada Lovelace <ada@example.com>}
     * and {@code mailto:ada@example.com} (an optional {@code ?subject=...} part is dropped) both
     * give {@code ada@example.com}. Any other value is returned trimmed. The result is not
     * lower-cased or validated; pass it to {@link #normalize} and {@link #isValid}.
     */
    public static String extractAddress(String cell) {
        String value = trim(cell);
        if (value == null) return null;
        Matcher m = NAME_AND_ADDRESS.matcher(value);
        if (m.matches()) value = trim(m.group(1));
        if (value.regionMatches(true, 0, MAILTO, 0, MAILTO.length())) {
            value = value.substring(MAILTO.length());
            int query = value.indexOf('?');
            if (query >= 0) value = value.substring(0, query);
            value = trim(value);
        }
        return value;
    }
}
