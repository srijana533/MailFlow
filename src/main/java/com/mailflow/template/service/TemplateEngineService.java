package com.mailflow.template.service;

import freemarker.cache.StringTemplateLoader;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles variable substitution in email templates.
 *
 * <p>Template syntax: {@code {{variableName}}}
 * which is converted to Freemarker {@code ${variableName}} internally.
 *
 * <p>The conversion allows templates to be authored in a simpler
 * Mustache-like syntax while leveraging Freemarker for rendering.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateEngineService {

    private static final Pattern MUSTACHE_VAR = Pattern.compile("\\{\\{(\\w+)\\}\\}");

    @Qualifier("mailflowFreemarkerConfiguration")
    private final freemarker.template.Configuration freemarkerConfig;
    private final StringTemplateLoader stringTemplateLoader;

    /**
     * Renders a template string by substituting all {@code {{var}}} placeholders.
     *
     * @param templateContent the raw template with {{var}} syntax
     * @param variables       map of variable name → value
     * @return rendered string
     */
    public String render(String templateContent, Map<String, Object> variables) {
        if (templateContent == null || templateContent.isBlank()) return "";

        // Convert {{var}} → ${var} for Freemarker
        String freemarkerTemplate = toFreemarkerSyntax(templateContent);

        // Use a unique key per render call to avoid cache collisions
        String key = "tmpl_" + System.nanoTime();
        stringTemplateLoader.putTemplate(key, freemarkerTemplate);

        try {
            Template template = freemarkerConfig.getTemplate(key);
            StringWriter writer = new StringWriter();
            template.process(variables, writer);
            return writer.toString();
        } catch (IOException | TemplateException e) {
            log.warn("Template rendering failed: {}", e.getMessage());
            // Return partially rendered content rather than throwing
            return fallbackRender(templateContent, variables);
        } finally {
            // Remove the template from the loader to avoid memory growth
            stringTemplateLoader.putTemplate(key, "");
        }
    }

    /**
     * Extracts all variable names from a template string.
     */
    public java.util.List<String> extractVariables(String templateContent) {
        if (templateContent == null) return java.util.List.of();
        Matcher matcher = MUSTACHE_VAR.matcher(templateContent);
        return matcher.results()
                .map(r -> r.group(1))
                .distinct()
                .toList();
    }

    /**
     * Validates that all variables required by a template are present in the provided map.
     */
    public java.util.List<String> findMissingVariables(String templateContent,
                                                        Map<String, Object> variables) {
        return extractVariables(templateContent).stream()
                .filter(v -> !variables.containsKey(v))
                .toList();
    }

    // ── Private ──────────────────────────────────────────────────────────────

    /**
     * Converts {@code {{var}}} to the Freemarker expression {@code ${(var)!}}
     * which renders an empty string when the variable is missing or null,
     * instead of throwing a TemplateException.
     */
    private String toFreemarkerSyntax(String template) {
        return MUSTACHE_VAR.matcher(template).replaceAll(m -> "\\${(" + m.group(1) + ")!}");
    }

    /** Simple string replacement fallback when Freemarker fails. */
    private String fallbackRender(String template, Map<String, Object> variables) {
        String result = template;
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}",
                                    entry.getValue() != null ? entry.getValue().toString() : "");
        }
        return result;
    }
}
