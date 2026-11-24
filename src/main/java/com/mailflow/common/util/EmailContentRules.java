package com.mailflow.common.util;

/**
 * Validation rules for a campaign's own email content: the subject and the
 * plain-text body written in the New Campaign form. The API (Bean Validation on
 * {@code CampaignRequest} and on the preview request), the launch check and the
 * frontend ({@code frontend/src/lib/campaignContent.ts}) apply the same rules;
 * {@code CampaignContentTest} compares the limits and the control characters with
 * the frontend source.
 *
 * <ul>
 *   <li>Subject: at most {@value #SUBJECT_MAX_LENGTH} characters (the RFC 5322 line
 *       limit) on one line: no CR or LF, which would start a new mail header, and no
 *       other control character except tab.</li>
 *   <li>Body: at most {@value #BODY_MAX_LENGTH} characters, no control character
 *       except tab, CR and LF.</li>
 * </ul>
 *
 * <p>Control characters are those of {@link Character#isISOControl(char)}: U+0000 to
 * U+001F and U+007F to U+009F.
 */
public final class EmailContentRules {

    public static final int SUBJECT_MAX_LENGTH = 998;
    public static final int BODY_MAX_LENGTH = 100_000;

    /** A whole subject: no control character except tab (U+0009). */
    public static final String SUBJECT_PATTERN = "[^\\u0000-\\u0008\\u000A-\\u001F\\u007F-\\u009F]*";

    /** A whole body: no control character except tab (U+0009), LF (U+000A) and CR (U+000D). */
    public static final String BODY_PATTERN = "[^\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]*";

    public static final String SUBJECT_TOO_LONG = "must be at most 998 characters";
    public static final String SUBJECT_NOT_ONE_LINE = "must be one line without control characters";
    public static final String BODY_TOO_LONG = "must be at most 100,000 characters";
    public static final String BODY_CONTROL_CHARACTERS =
            "must not contain control characters (tabs and line breaks are allowed)";

    private EmailContentRules() {}

    public static boolean isAllowedInSubject(char c) {
        return !Character.isISOControl(c) || c == '\t';
    }

    public static boolean isAllowedInBody(char c) {
        return !Character.isISOControl(c) || c == '\t' || c == '\n' || c == '\r';
    }

    /**
     * What is wrong with a subject that is not blank, in the words of the API's field
     * errors, or {@code null} when it is fine. Blank subjects are refused separately.
     */
    public static String subjectProblem(String subject) {
        if (subject == null) return null;
        if (subject.length() > SUBJECT_MAX_LENGTH) return SUBJECT_TOO_LONG;
        for (int i = 0; i < subject.length(); i++) {
            if (!isAllowedInSubject(subject.charAt(i))) return SUBJECT_NOT_ONE_LINE;
        }
        return null;
    }

    /** What is wrong with a body, or {@code null} when it is fine (a blank body is fine). */
    public static String bodyProblem(String body) {
        if (body == null) return null;
        if (body.length() > BODY_MAX_LENGTH) return BODY_TOO_LONG;
        for (int i = 0; i < body.length(); i++) {
            if (!isAllowedInBody(body.charAt(i))) return BODY_CONTROL_CHARACTERS;
        }
        return null;
    }
}
