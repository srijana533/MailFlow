package com.mailflow.template.service;

import java.util.Map;

/**
 * What a campaign email contains, for one recipient: the single code path shared by
 * the batch pipeline ({@code CampaignItemProcessor}, real sends) and the preview
 * ({@code POST /api/templates/render}), so a preview shows exactly what is sent.
 *
 * <ul>
 *   <li>A campaign with its own body (a non-blank custom body) sends it as plain
 *       text: the text/plain part is the rendered body, and the text/html part is
 *       made from the RENDERED text by {@link #toHtml}, so the placeholder values
 *       (contact data) are HTML-escaped too.</li>
 *   <li>Otherwise the template's HTML and plain-text bodies are rendered, as before.</li>
 * </ul>
 *
 * <p>Placeholders are filled by {@link TemplateEngineService} with the model of
 * {@link PersonalizationModel}.
 */
public final class CampaignEmailContent {

    /** A subject and both bodies, rendered for one recipient. Missing parts are "". */
    public record Rendered(String subject, String htmlBody, String plainBody) {}

    private CampaignEmailContent() {}

    /** Whether the campaign uses its own body instead of the template's. */
    public static boolean hasCustomBody(String customBody) {
        return customBody != null && !customBody.isBlank();
    }

    /**
     * Renders the email for one recipient's model.
     *
     * @param customBody   the campaign's own plain-text body; used when not blank
     * @param templateHtml the template's HTML body, used when there is no custom body
     * @param templatePlain the template's plain-text body, used when there is no custom body
     */
    public static Rendered render(TemplateEngineService engine, Map<String, Object> model, String subject,
                                  String customBody, String templateHtml, String templatePlain) {
        String renderedSubject = engine.render(subject, model);
        if (hasCustomBody(customBody)) {
            String plain = engine.render(customBody, model);
            return new Rendered(renderedSubject, toHtml(plain), plain);
        }
        return new Rendered(renderedSubject,
                templateHtml != null ? engine.render(templateHtml, model) : "",
                templatePlain != null ? engine.render(templatePlain, model) : "");
    }

    /**
     * The HTML part of a plain-text email: the text HTML-escaped ({@code & < > " '}),
     * with every line break (CRLF, CR or LF) turned into {@code <br>}, in a minimal
     * UTF-8 document. Pass the RENDERED text, so values filled into placeholders are
     * escaped as well. A blank text gives "" (no HTML part).
     */
    public static String toHtml(String renderedText) {
        if (renderedText == null || renderedText.isBlank()) return "";
        StringBuilder body = new StringBuilder(renderedText.length() + 64);
        for (int i = 0; i < renderedText.length(); i++) {
            char c = renderedText.charAt(i);
            switch (c) {
                case '&' -> body.append("&amp;");
                case '<' -> body.append("&lt;");
                case '>' -> body.append("&gt;");
                case '"' -> body.append("&quot;");
                case '\'' -> body.append("&#39;");
                case '\r' -> {
                    if (i + 1 < renderedText.length() && renderedText.charAt(i + 1) == '\n') i++;
                    body.append("<br>\n");
                }
                case '\n' -> body.append("<br>\n");
                default -> body.append(c);
            }
        }
        return "<!DOCTYPE html>\n<html><head><meta charset=\"UTF-8\"></head>\n"
                + "<body style=\"font-family: Arial, Helvetica, sans-serif; font-size: 14px; line-height: 1.5;\">\n"
                + body
                + "\n</body></html>\n";
    }
}
