package com.mailflow.template;

import com.mailflow.template.service.TemplateEngineService;
import freemarker.cache.StringTemplateLoader;
import freemarker.template.TemplateExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the template engine (variable substitution and extraction).
 * Uses Freemarker's fully-qualified Configuration to avoid import ambiguity
 * with Spring's @Configuration annotation.
 */
class TemplateEngineServiceTest {

    private TemplateEngineService service;

    @BeforeEach
    void setUp() {
        StringTemplateLoader loader = new StringTemplateLoader();
        freemarker.template.Configuration config =
                new freemarker.template.Configuration(
                        freemarker.template.Configuration.VERSION_2_3_32);
        config.setTemplateLoader(loader);
        config.setDefaultEncoding("UTF-8");
        config.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        config.setNewBuiltinClassResolver(freemarker.core.TemplateClassResolver.SAFER_RESOLVER);

        service = new TemplateEngineService(config, loader);
    }

    @Test
    void renders_simple_variable() {
        String result = service.render("Hello {{name}}!", Map.of("name", "Rahul"));
        assertThat(result).isEqualTo("Hello Rahul!");
    }

    @Test
    void renders_multiple_variables() {
        String template = "Hi {{name}}, your code for {{course}} is {{code}}.";
        Map<String, Object> vars = Map.of("name", "Priya", "course", "CS", "code", "ABC123");
        String result = service.render(template, vars);
        assertThat(result).isEqualTo("Hi Priya, your code for CS is ABC123.");
    }

    @Test
    void missing_variable_renders_empty_string() {
        // With (var)! syntax, missing variable renders as empty string, not an exception
        String result = service.render("Hello {{name}} from {{company}}!", Map.of("name", "Arjun"));
        assertThat(result).contains("Arjun");
        assertThat(result).doesNotContain("{{company}}");
        assertThat(result).isEqualTo("Hello Arjun from !");
    }

    @Test
    void extracts_variables_from_template() {
        String template = "Hi {{name}}, your course {{course}} code is {{code}}.";
        List<String> vars = service.extractVariables(template);
        assertThat(vars).containsExactlyInAnyOrder("name", "course", "code");
    }

    @Test
    void finds_missing_variables() {
        String template = "Hi {{name}}, your code is {{code}}.";
        Map<String, Object> provided = Map.of("name", "Test");
        List<String> missing = service.findMissingVariables(template, provided);
        assertThat(missing).containsExactly("code");
    }

    @Test
    void handles_null_template_gracefully() {
        String result = service.render(null, Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void handles_empty_template() {
        String result = service.render("", Map.of());
        assertThat(result).isEmpty();
    }

    @Test
    void renders_html_template() {
        String html = "<p>Dear <strong>{{name}}</strong>, welcome to {{course}}.</p>";
        String result = service.render(html, Map.of("name", "Dev", "course", "Java Advanced"));
        assertThat(result).contains("<strong>Dev</strong>");
        assertThat(result).contains("Java Advanced");
    }

    @Test
    void renders_all_standard_campaign_variables() {
        String template = "Hi {{name}}, your {{course}} code at {{company}} is {{code}}.";
        Map<String, Object> vars = Map.of(
                "name", "Raj", "course", "CS", "company", "TechCorp", "code", "XY99");
        String result = service.render(template, vars);
        assertThat(result).isEqualTo("Hi Raj, your CS code at TechCorp is XY99.");
    }
}
