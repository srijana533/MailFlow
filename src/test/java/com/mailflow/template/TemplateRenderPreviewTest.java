package com.mailflow.template;

import com.mailflow.template.dto.TemplateRenderRequest;
import com.mailflow.template.dto.TemplateRenderResponse;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.repository.EmailTemplateRepository;
import com.mailflow.template.service.TemplateEngineService;
import com.mailflow.template.service.TemplateService;
import freemarker.cache.StringTemplateLoader;
import freemarker.template.TemplateExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The personalised preview must resolve placeholders exactly like a real send:
 * same model (aliases, custom fields, empty defaults), campaign subject first.
 */
@ExtendWith(MockitoExtension.class)
class TemplateRenderPreviewTest {

    @Mock private EmailTemplateRepository templateRepository;

    private TemplateService templateService;
    private EmailTemplate template;

    @BeforeEach
    void setUp() {
        StringTemplateLoader loader = new StringTemplateLoader();
        freemarker.template.Configuration config =
                new freemarker.template.Configuration(freemarker.template.Configuration.VERSION_2_3_32);
        config.setTemplateLoader(loader);
        config.setDefaultEncoding("UTF-8");
        config.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        config.setNewBuiltinClassResolver(freemarker.core.TemplateClassResolver.SAFER_RESOLVER);
        templateService = new TemplateService(templateRepository, new TemplateEngineService(config, loader));

        template = EmailTemplate.builder()
                .id(5L)
                .name("Welcome")
                .subject("Template subject for {{Name}}")
                .htmlBody("<p>Hi {{FirstName}} from {{Company}} ({{Role}}, {{Team}})</p>")
                .plainBody("Hi {{firstName}}, phone: {{Phone}}")
                .build();
        when(templateRepository.findByIdAndDeletedAtIsNull(5L)).thenReturn(Optional.of(template));
    }

    private TemplateRenderRequest.Sample ada() {
        return new TemplateRenderRequest.Sample(
                "ada@example.com", "Ada Lovelace", "Ada", "Lovelace", "Acme", null,
                Map.of("role", "Engineer", "Team", "Platform"));
    }

    @Test
    void renders_campaign_subject_and_body_for_one_recipient() {
        TemplateRenderResponse res = templateService.renderForRecipient(5L,
                new TemplateRenderRequest("Hello {{FirstName}}", ada()));

        // The campaign subject (what is actually sent) wins over the template subject.
        assertThat(res.subject()).isEqualTo("Hello Ada");
        // Capitalised aliases, known custom fields and arbitrary custom fields resolve.
        assertThat(res.htmlBody()).isEqualTo("<p>Hi Ada from Acme (Engineer, Platform)</p>");
        // A placeholder without data renders blank, never as a raw {{Phone}}, and is reported.
        assertThat(res.plainBody()).isEqualTo("Hi Ada, phone: ");
        assertThat(res.emptyVariables()).containsExactly("Phone");
        verify(templateRepository, never()).save(any());
    }

    @Test
    void blank_subject_falls_back_to_the_template_subject() {
        TemplateRenderResponse res = templateService.renderForRecipient(5L,
                new TemplateRenderRequest("  ", ada()));

        assertThat(res.subject()).isEqualTo("Template subject for Ada Lovelace");
    }
}
