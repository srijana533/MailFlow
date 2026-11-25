package com.mailflow.template;

import com.mailflow.batch.CampaignItemProcessor;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.config.FreemarkerConfig;
import com.mailflow.email.UnsubscribeService;
import com.mailflow.queue.EmailMessage;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.template.controller.TemplateController;
import com.mailflow.template.dto.TemplateContentRenderRequest;
import com.mailflow.template.dto.TemplateRenderResponse;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.repository.EmailTemplateRepository;
import com.mailflow.template.service.TemplateEngineService;
import com.mailflow.template.service.TemplateService;
import freemarker.cache.StringTemplateLoader;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.method.AuthorizationManagerBeforeMethodInterceptor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * POST /api/templates/render previews unsaved template content (the Templates page
 * editor). It must render exactly like a real send, save nothing, bound the request,
 * and be limited to admins and campaign managers.
 */
@ExtendWith(MockitoExtension.class)
class TemplateContentRenderTest {

    private static final String SUBJECT = "Hi {{FirstName}}, news for {{Company}}";
    private static final String HTML =
            "<p>{{Name}} ({{Email}}) works on {{Team}} as {{Role}}.</p><p>Phone: {{Phone}}</p>";
    private static final String PLAIN = "Dear {{firstName}} {{LastName}}, team {{Team}}";

    @Mock private EmailTemplateRepository templateRepository;
    @Mock private UnsubscribeService unsubscribeService;
    @Mock private CampaignRecipientRepository campaignRecipientRepository;
    @Mock private CampaignRepository campaignRepository;
    @Mock private TemplateService mockedTemplateService;

    private TemplateEngineService engine;
    private TemplateService templateService;

    @BeforeEach
    void setUp() {
        StringTemplateLoader loader = new StringTemplateLoader();
        // The production FreeMarker configuration, built without a Spring context.
        engine = new TemplateEngineService(new FreemarkerConfig().mailflowFreemarkerConfiguration(loader), loader);
        templateService = new TemplateService(templateRepository, engine);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static TemplateContentRenderRequest.Sample ada() {
        return new TemplateContentRenderRequest.Sample(
                "ada@demo.local", "Ada Lovelace", "Ada", "Lovelace", "Analytical Engines", null,
                Map.of("role", "Engineer", "Team", "Platform"));
    }

    @Test
    void renders_unsaved_content_exactly_like_a_real_send_and_saves_nothing() {
        TemplateRenderResponse preview = templateService.renderContent(
                new TemplateContentRenderRequest(SUBJECT, HTML, PLAIN, ada(), null));

        assertThat(preview.subject()).isEqualTo("Hi Ada, news for Analytical Engines");
        assertThat(preview.htmlBody())
                .isEqualTo("<p>Ada Lovelace (ada@demo.local) works on Platform as Engineer.</p><p>Phone: </p>");
        assertThat(preview.plainBody()).isEqualTo("Dear Ada Lovelace, team Platform");
        // {{Phone}} has no value: it renders blank (never as a raw placeholder) and is reported.
        assertThat(preview.emptyVariables()).containsExactly("Phone");

        // The batch processor, given the same content and the same person as a saved
        // contact, produces exactly the same subject and bodies.
        Recipient contact = Recipient.builder()
                .id(7L).email("ada@demo.local").name("Ada Lovelace").firstName("Ada").lastName("Lovelace")
                .company("Analytical Engines")
                .customFields(new HashMap<>(Map.of("role", "Engineer", "Team", "Platform")))
                .build();
        Campaign campaign = Campaign.builder().organizationId(com.mailflow.tenant.TestTenant.ORG)
                .id(3L).subject(SUBJECT).senderEmail("noreply@mailflow.local")
                .template(EmailTemplate.builder().id(9L).name("Draft").subject(SUBJECT)
                        .htmlBody(HTML).plainBody(PLAIN).build())
                .build();
        CampaignRecipient row = CampaignRecipient.builder()
                .id(1L).campaign(campaign).recipient(contact)
                .idempotencyKey(CampaignRecipient.buildIdempotencyKey(3L, 7L))
                .deliveryStatus(CampaignRecipient.DeliveryStatus.PENDING)
                .retryCount(0)
                .build();
        when(unsubscribeService.isUnsubscribed(com.mailflow.tenant.TestTenant.ORG, "ada@demo.local")).thenReturn(false);
        // The processor only renders while the campaign is RUNNING.
        when(campaignRepository.findStatusById(3L)).thenReturn(Optional.of(CampaignStatus.RUNNING));

        EmailMessage sent = new CampaignItemProcessor(
                engine, unsubscribeService, campaignRecipientRepository, campaignRepository)
                .process(row);

        assertThat(sent).isNotNull();
        assertThat(sent.subject()).isEqualTo(preview.subject());
        assertThat(sent.htmlBody()).isEqualTo(preview.htmlBody());
        assertThat(sent.plainBody()).isEqualTo(preview.plainBody());

        // No template lookup and no save: the preview never touches the database.
        verifyNoInteractions(templateRepository);
    }

    @Test
    void missing_parts_render_as_empty_strings() {
        TemplateRenderResponse preview = templateService.renderContent(new TemplateContentRenderRequest(
                null, null, "Hello {{Name}}",
                new TemplateContentRenderRequest.Sample(null, null, null, null, null, null, null), null));

        assertThat(preview.subject()).isEmpty();
        assertThat(preview.htmlBody()).isEmpty();
        assertThat(preview.plainBody()).isEqualTo("Hello ");
        assertThat(preview.emptyVariables()).containsExactly("Name");
        verifyNoInteractions(templateRepository);
    }

    @Test
    void request_size_is_limited() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        Map<String, String> fiftyFields = new LinkedHashMap<>();
        // 50 distinct 40-character keys ("k" plus a zero-padded number), each with a 1,000-character value.
        for (int i = 0; i < 50; i++) fiftyFields.put(String.format("k%039d", i), "v".repeat(1000));
        TemplateContentRenderRequest atLimits = new TemplateContentRenderRequest(
                "s".repeat(1000), "h".repeat(500_000), "p".repeat(500_000),
                new TemplateContentRenderRequest.Sample(
                        "e".repeat(255), "n".repeat(255), "f".repeat(255), "l".repeat(255),
                        "c".repeat(255), "o".repeat(255), fiftyFields),
                "b".repeat(100_000));
        assertThat(fiftyFields).hasSize(50).allSatisfy((k, v) -> assertThat(k).hasSize(40));
        assertThat(validator.validate(atLimits)).isEmpty();

        Map<String, String> tooMany = new LinkedHashMap<>(Map.of("Team", "Platform"));
        for (int i = 0; i < 50; i++) tooMany.put("k" + i, "v");
        TemplateContentRenderRequest overLimits = new TemplateContentRenderRequest(
                "s".repeat(1001), "h".repeat(500_001), "p".repeat(500_001),
                new TemplateContentRenderRequest.Sample(
                        "e".repeat(256), null, null, null, "c".repeat(256), null, tooMany),
                "b".repeat(100_001));
        assertThat(paths(validator.validate(overLimits))).containsExactlyInAnyOrder(
                "subject", "htmlBody", "plainBody", "recipient.email", "recipient.company", "recipient.fields",
                "customBody");

        // Keys and values of custom fields are bounded too.
        TemplateContentRenderRequest longKeyAndValue = new TemplateContentRenderRequest(
                "Hi", "<p>x</p>", null,
                new TemplateContentRenderRequest.Sample(null, null, null, null, null, null,
                        Map.of("k".repeat(41), "v", "Team", "v".repeat(1001))), null);
        assertThat(validator.validate(longKeyAndValue)).hasSize(2)
                .allSatisfy(v -> assertThat(v.getPropertyPath().toString()).startsWith("recipient.fields"));

        // A sample recipient is required.
        assertThat(paths(validator.validate(new TemplateContentRenderRequest("Hi", "<p>x</p>", null, null, null))))
                .containsExactly("recipient");
    }

    @Test
    void only_admins_and_campaign_managers_may_render() throws Exception {
        TemplateContentRenderRequest request = new TemplateContentRenderRequest("Hi", "<p>x</p>", null, ada(), null);
        when(mockedTemplateService.renderContent(request))
                .thenReturn(new TemplateRenderResponse("Hi", "<p>x</p>", "", List.of()));

        // The controller behind Spring Security's real @PreAuthorize interceptor.
        ProxyFactory factory = new ProxyFactory(new TemplateController(mockedTemplateService));
        factory.setProxyTargetClass(true);
        factory.addAdvisor(AuthorizationManagerBeforeMethodInterceptor.preAuthorize());
        TemplateController controller = (TemplateController) factory.getProxy();

        authenticateAs("ROLE_VIEWER");
        assertThatThrownBy(() -> controller.renderContent(request)).isInstanceOf(AccessDeniedException.class);

        authenticateAs("ROLE_CAMPAIGN_MANAGER");
        assertThat(controller.renderContent(request).getStatusCode().value()).isEqualTo(200);

        authenticateAs("ROLE_ADMIN");
        assertThat(controller.renderContent(request).getBody().subject()).isEqualTo("Hi");

        verify(mockedTemplateService, times(2)).renderContent(request);

        // Mapped at POST /api/templates/render. SecurityConfig also limits every non-GET
        // request under /api/templates/** to ADMIN and CAMPAIGN_MANAGER.
        assertThat(TemplateController.class.getAnnotation(RequestMapping.class).value())
                .containsExactly("/api/templates");
        assertThat(TemplateController.class
                .getMethod("renderContent", TemplateContentRenderRequest.class)
                .getAnnotation(PostMapping.class).value())
                .containsExactly("/render");
    }

    private static void authenticateAs(String authority) {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "user@mailflow.local", null, List.of(new SimpleGrantedAuthority(authority))));
    }

    private static List<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).toList();
    }
}
