package com.mailflow.campaign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailflow.analytics.EmailLogService;
import com.mailflow.batch.CampaignItemProcessor;
import com.mailflow.batch.CampaignJobLauncher;
import com.mailflow.campaign.dto.CampaignRequest;
import com.mailflow.campaign.dto.CampaignResponse;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.campaign.repository.CampaignTargetAddressRepository;
import com.mailflow.campaign.service.CampaignService;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.common.util.EmailContentRules;
import com.mailflow.config.FreemarkerConfig;
import com.mailflow.email.EmailRequest;
import com.mailflow.email.RateLimiterService;
import com.mailflow.email.SmtpEmailProvider;
import com.mailflow.email.UnsubscribeService;
import com.mailflow.queue.EmailConsumer;
import com.mailflow.queue.EmailMessage;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.repository.RecipientRepository;
import com.mailflow.template.dto.TemplateContentRenderRequest;
import com.mailflow.template.dto.TemplateRenderResponse;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.repository.EmailTemplateRepository;
import com.mailflow.template.service.CampaignEmailContent;
import com.mailflow.template.service.TemplateEngineService;
import com.mailflow.template.service.TemplateService;
import freemarker.cache.StringTemplateLoader;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.persistence.Column;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A campaign's own email content: the subject (campaigns.subject) and a plain-text
 * body (campaigns.custom_body, V7) written in the New Campaign form. Covers storage
 * and PUT semantics, the launch gate, what the batch processor sends (and the MIME
 * parts that come out of the SMTP provider), the HTML part made from the text, the
 * preview, and the validation shared with the frontend.
 *
 * <p>Mocked repositories, the production FreeMarker configuration and a mocked
 * {@link JavaMailSender} (the built message is inspected, nothing is sent).
 */
@ExtendWith(MockitoExtension.class)
class CampaignContentTest {

    private static final long CAMPAIGN_ID = 21L;
    private static final String BODY = "Hi {{FirstName}},\nyour team: {{Team}}.\n\nThanks, {{Company}}";
    private static final String NO_CONTENT = "Campaign has no email content: choose a template or write the email";

    @Mock private CampaignRepository campaignRepository;
    @Mock private TemplateService templateService;
    @Mock private CampaignJobLauncher jobLauncher;
    @Mock private RecipientRepository recipientRepository;
    @Mock private CampaignRecipientRepository campaignRecipientRepository;
    @Mock private UnsubscribeService unsubscribeService;
    @Mock private CampaignTargetAddressRepository targetAddressRepository;
    @Mock private EmailTemplateRepository templateRepository;
    @Mock private JavaMailSender mailSender;

    private CampaignService campaignService;
    private TemplateEngineService engine;
    /** The stored campaign: JPA hands out the same managed entity on every read. */
    private Campaign stored;

    @BeforeEach
    void setUp() {
        campaignService = new CampaignService(campaignRepository, templateService, jobLauncher,
                recipientRepository, campaignRecipientRepository, unsubscribeService, targetAddressRepository);
        StringTemplateLoader loader = new StringTemplateLoader();
        // The production FreeMarker configuration, built without a Spring context.
        engine = new TemplateEngineService(new FreemarkerConfig().mailflowFreemarkerConfiguration(loader), loader);

        lenient().when(campaignRepository.findOneById(CAMPAIGN_ID)).thenAnswer(inv -> Optional.ofNullable(stored));
        lenient().when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> {
            Campaign c = inv.getArgument(0);
            if (c.getId() == null) c.setId(CAMPAIGN_ID);
            stored = c;
            return c;
        });
    }

    // ── Fixtures ────────────────────────────────────────────────────────────────

    private static CampaignRequest request(String subject, String customBody) {
        return new CampaignRequest("Welcome", null, subject, null, null, "sender@test.local", null, null,
                null, null, null, customBody);
    }

    /** A draft that can be launched: its own body, no template, a subject, a sender and one contact. */
    private static Campaign launchable() {
        return Campaign.builder().organizationId(com.mailflow.tenant.TestTenant.ORG)
                .id(CAMPAIGN_ID)
                .name("Welcome")
                .subject("Hello {{FirstName}}")
                .customBody(BODY)
                .senderEmail("sender@test.local")
                .status(CampaignStatus.DRAFT)
                .recipientIds(new HashSet<>(Set.of(10L)))
                .build();
    }

    private static EmailTemplate template(String html, String plain) {
        return EmailTemplate.builder().id(3L).name("Newsletter").subject("Template subject")
                .htmlBody(html).plainBody(plain).build();
    }

    private static Recipient ada() {
        return Recipient.builder()
                .id(10L).email("ada@test.local").name("Ada Lovelace").firstName("Ada").lastName("Lovelace")
                .company("Acme").customFields(new HashMap<>(Map.of("Team", "Platform")))
                .status(RecipientStatus.ACTIVE)
                .build();
    }

    private static CampaignRecipient contactRow(Campaign campaign, Recipient r) {
        return CampaignRecipient.builder()
                .id(1L).campaign(campaign).recipient(r)
                .idempotencyKey(CampaignRecipient.buildIdempotencyKey(campaign.getId(), r.getId()))
                .deliveryStatus(CampaignRecipient.DeliveryStatus.PENDING)
                .retryCount(0)
                .build();
    }

    /** The same person as a campaign-only address: a delivery row without a contact. */
    private static CampaignRecipient addressRow(Campaign campaign) {
        return CampaignRecipient.builder()
                .id(2L).campaign(campaign)
                .idempotencyKey(CampaignRecipient.buildAddressIdempotencyKey(campaign.getId(), "ada@test.local"))
                .deliveryStatus(CampaignRecipient.DeliveryStatus.PENDING)
                .snapshotEmail("ada@test.local").snapshotName("Ada Lovelace").snapshotFirstName("Ada")
                .snapshotLastName("Lovelace").snapshotCompany("Acme")
                .snapshotCustomFields(new HashMap<>(Map.of("Team", "Platform")))
                .retryCount(0)
                .build();
    }

    private CampaignItemProcessor processor() {
        // The processor only renders while the campaign is RUNNING.
        when(campaignRepository.findStatusById(CAMPAIGN_ID)).thenReturn(Optional.of(CampaignStatus.RUNNING));
        return new CampaignItemProcessor(engine, unsubscribeService, campaignRecipientRepository, campaignRepository);
    }

    private void assertLaunchRejected(String message) {
        assertThatThrownBy(() -> campaignService.launch(CAMPAIGN_ID))
                .isInstanceOf(ValidationException.class)
                .hasMessage(message);
        assertThat(stored.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }

    // ── Storage and PUT semantics ───────────────────────────────────────────────

    @Test
    void create_stores_the_custom_body_and_a_blank_one_means_none() {
        CampaignResponse created = campaignService.create(request("Hello {{FirstName}}", BODY));

        assertThat(stored.getCustomBody()).isEqualTo(BODY);
        assertThat(created.customBody()).isEqualTo(BODY);
        assertThat(created.hasContent()).isTrue();
        assertThat(created.templateId()).isNull();
        verify(templateService, never()).getOrThrow(any());

        for (String blank : new String[] {"", "   ", "\n\t"}) {
            stored = null;
            CampaignResponse none = campaignService.create(request("Hello", blank));
            assertThat(stored.getCustomBody()).as("blank body %s", blank.length()).isNull();
            assertThat(none.customBody()).isNull();
            assertThat(none.hasContent()).isFalse();
        }
    }

    @Test
    void update_keeps_the_body_on_null_clears_it_on_an_empty_string_and_replaces_it_otherwise() throws Exception {
        stored = launchable();

        // Bound by Jackson as for PUT /api/campaigns/21 with customBody omitted: kept.
        CampaignRequest omitted = new ObjectMapper().findAndRegisterModules().readValue("""
                {"name": "Welcome", "subject": "Hello {{FirstName}}", "senderEmail": "sender@test.local"}
                """, CampaignRequest.class);
        assertThat(omitted.customBody()).isNull();
        assertThat(campaignService.update(CAMPAIGN_ID, omitted).customBody()).isEqualTo(BODY);
        assertThat(stored.getCustomBody()).isEqualTo(BODY);

        // Explicit null: kept.
        assertThat(campaignService.update(CAMPAIGN_ID, request("Hello", null)).customBody()).isEqualTo(BODY);

        // Another text: replaced.
        assertThat(campaignService.update(CAMPAIGN_ID, request("Hello", "New text for {{Name}}")).customBody())
                .isEqualTo("New text for {{Name}}");
        assertThat(stored.getCustomBody()).isEqualTo("New text for {{Name}}");

        // "": cleared, so the template's body is used again (here there is none).
        CampaignResponse cleared = campaignService.update(CAMPAIGN_ID, request("Hello", ""));
        assertThat(cleared.customBody()).isNull();
        assertThat(cleared.hasContent()).isFalse();
        assertThat(stored.getCustomBody()).isNull();
        // The recipients were not part of these requests: null keeps them.
        assertThat(stored.getRecipientIds()).containsExactly(10L);
    }

    @Test
    void only_single_campaign_responses_carry_the_body_and_every_response_says_whether_there_is_content() {
        stored = launchable();
        PageRequest page = PageRequest.of(0, 20);
        when(campaignRepository.findAll(null, null, page)).thenReturn(new PageImpl<>(List.of(stored), page, 1));
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();

        CampaignResponse listed = campaignService.findAll(null, null, page).content().get(0);
        JsonNode listedJson = json.valueToTree(listed);
        assertThat(listedJson.has("customBody")).isFalse();
        assertThat(listedJson.has("targetAddresses")).isFalse();
        assertThat(listedJson.get("hasContent").asBoolean()).isTrue();

        JsonNode detail = json.valueToTree(campaignService.findById(CAMPAIGN_ID));
        assertThat(detail.get("customBody").asText()).isEqualTo(BODY);
        assertThat(detail.get("hasContent").asBoolean()).isTrue();

        // Without a body of its own, the template decides: a usable template is content ...
        stored.setCustomBody(null);
        stored.setTemplate(template("<p>Hi {{FirstName}}</p>", null));
        JsonNode withTemplate = json.valueToTree(campaignService.findById(CAMPAIGN_ID));
        assertThat(withTemplate.has("customBody")).isFalse();
        assertThat(withTemplate.get("hasContent").asBoolean()).isTrue();
        // ... a template without a body is not.
        stored.setTemplate(template(" ", ""));
        assertThat(campaignService.findById(CAMPAIGN_ID).hasContent()).isFalse();
    }

    // ── Launch gate ─────────────────────────────────────────────────────────────

    @Test
    void launch_is_rejected_without_content_without_a_subject_and_without_recipients() {
        // No template and no body of its own.
        stored = launchable();
        stored.setCustomBody(null);
        assertLaunchRejected(NO_CONTENT);

        // Content but a blank subject.
        stored = launchable();
        stored.setSubject("   ");
        assertLaunchRejected("Campaign subject is required");

        // A subject with a line break (saved before the rule) would start a new mail header.
        stored = launchable();
        stored.setSubject("Hello\r\nBcc: someone@test.local");
        assertLaunchRejected("Campaign subject must be one line without control characters");

        // Content and subject, but nobody selected: never all contacts.
        stored = launchable();
        stored.setRecipientIds(new HashSet<>());
        assertLaunchRejected("Campaign has no recipients selected");

        verify(recipientRepository, never()).findAllActiveRecipients();
        verify(campaignRecipientRepository, never()).saveAll(any());
        verify(jobLauncher, never()).launchCampaignJob(any());
    }

    @Test
    void a_template_whose_bodies_are_empty_counts_as_no_content() {
        stored = launchable();
        stored.setCustomBody(null);
        stored.setTemplate(template(null, "   "));
        assertThat(CampaignService.hasEmailContent(stored)).isFalse();
        assertLaunchRejected(NO_CONTENT + ". The template assigned to this campaign has no body.");

        // A deleted template is no content either, even with bodies.
        EmailTemplate deleted = template("<p>Hi</p>", "Hi");
        deleted.setDeletedAt(Instant.now());
        stored.setTemplate(deleted);
        assertThat(CampaignService.hasEmailContent(stored)).isFalse();
        assertLaunchRejected(NO_CONTENT + ". The template assigned to this campaign has been deleted.");

        // The campaign's own body is content whatever the template.
        stored.setCustomBody(BODY);
        assertThat(CampaignService.hasEmailContent(stored)).isTrue();
        stored.setTemplate(template(null, ""));
        assertThat(CampaignService.hasEmailContent(stored)).isTrue();
        verify(jobLauncher, never()).launchCampaignJob(any());
    }

    @Test
    void a_campaign_with_its_own_body_and_no_template_can_be_launched() {
        stored = launchable();
        when(recipientRepository.findByIdsIn(com.mailflow.tenant.TestTenant.ORG, Set.of(10L))).thenReturn(List.of(ada()));
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_ID)).thenReturn(1L);

        CampaignResponse launched = campaignService.launch(CAMPAIGN_ID);

        assertThat(launched.status()).isEqualTo(CampaignStatus.QUEUED);
        assertThat(launched.hasContent()).isTrue();
        verify(campaignRecipientRepository).saveAll(any());
        verify(jobLauncher).launchCampaignJob(CAMPAIGN_ID);
        verify(templateService, never()).getOrThrow(any());
    }

    // ── What is sent ────────────────────────────────────────────────────────────

    @Test
    void the_processor_sends_the_custom_body_instead_of_the_template_as_plain_text_plus_html() throws Exception {
        Campaign campaign = launchable();
        campaign.setTemplate(template("<p>TEMPLATE HTML for {{Name}}</p>", "TEMPLATE PLAIN"));

        EmailMessage message = processor().process(contactRow(campaign, ada()));

        String text = "Hi Ada,\nyour team: Platform.\n\nThanks, Acme";
        assertThat(message).isNotNull();
        assertThat(message.subject()).isEqualTo("Hello Ada");
        assertThat(message.plainBody()).isEqualTo(text);
        assertThat(message.htmlBody()).isEqualTo(CampaignEmailContent.toHtml(text))
                .contains("Hi Ada,<br>\nyour team: Platform.<br>\n<br>\nThanks, Acme")
                .startsWith("<!DOCTYPE html>");
        assertThat(message.plainBody() + message.htmlBody()).doesNotContain("TEMPLATE");

        // Through the email consumer and the SMTP provider: multipart/alternative with
        // the text/plain part first and the text/html part second.
        MimeMessage mime = sendThroughConsumer(message);
        Mime parts = Mime.of(mime);
        assertThat(parts.leaves).containsExactly("text/plain", "text/html");
        assertThat(parts.multiparts).contains("multipart/alternative");
        assertThat(parts.text.get("text/plain")).isEqualTo(text);
        assertThat(parts.text.get("text/html")).isEqualTo(message.htmlBody());
        assertThat(mime.getSubject()).isEqualTo("Hello Ada");
    }

    @Test
    void without_a_custom_body_the_template_is_sent_as_before() {
        Campaign campaign = launchable();
        campaign.setCustomBody(null);
        campaign.setTemplate(template("<p>Hi {{FirstName}}</p>", "Hi {{FirstName}}"));

        EmailMessage message = processor().process(contactRow(campaign, ada()));

        assertThat(message.htmlBody()).isEqualTo("<p>Hi Ada</p>");
        assertThat(message.plainBody()).isEqualTo("Hi Ada");
    }

    @Test
    void the_html_part_escapes_the_values_filled_into_placeholders() {
        Campaign campaign = launchable();
        campaign.setCustomBody("Dear {{Name}}\r\nfrom {{Company}} <3 & more");
        Recipient tricky = ada();
        tricky.setName("<b>Bold</b> & \"Quotes\" 'single'");
        tricky.setCompany("<script>alert(1)</script>");

        EmailMessage message = processor().process(contactRow(campaign, tricky));

        // The text part keeps the values exactly as stored ...
        assertThat(message.plainBody())
                .isEqualTo("Dear <b>Bold</b> & \"Quotes\" 'single'\r\nfrom <script>alert(1)</script> <3 & more");
        // ... the HTML part escapes them, and the typed text, after the placeholders are filled.
        assertThat(message.htmlBody())
                .contains("Dear &lt;b&gt;Bold&lt;/b&gt; &amp; &quot;Quotes&quot; &#39;single&#39;<br>\n"
                        + "from &lt;script&gt;alert(1)&lt;/script&gt; &lt;3 &amp; more")
                .doesNotContain("<script>")
                .doesNotContain("<b>");
    }

    @Test
    void previewing_custom_content_gives_exactly_what_is_sent() {
        String subject = "Hello {{FirstName}} ({{Role}})";
        TemplateRenderResponse preview = new TemplateService(templateRepository, engine).renderContent(
                new TemplateContentRenderRequest(subject, "<p>not used</p>", "not used",
                        new TemplateContentRenderRequest.Sample("ada@test.local", "Ada Lovelace", "Ada", "Lovelace",
                                "Acme", null, Map.of("Team", "Platform")),
                        BODY));

        // The same content sent to the same person, as a campaign-only address (no contact).
        Campaign campaign = launchable();
        campaign.setSubject(subject);
        campaign.setTemplate(template("<p>TEMPLATE</p>", "TEMPLATE"));
        EmailMessage sent = processor().process(addressRow(campaign));

        assertThat(sent).isNotNull();
        assertThat(sent.recipientId()).isNull();
        assertThat(preview.subject()).isEqualTo(sent.subject()).isEqualTo("Hello Ada ()");
        assertThat(preview.plainBody()).isEqualTo(sent.plainBody()).isEqualTo("Hi Ada,\nyour team: Platform.\n\nThanks, Acme");
        assertThat(preview.htmlBody()).isEqualTo(sent.htmlBody());
        // {{Role}} has no value: it renders blank and is reported.
        assertThat(preview.emptyVariables()).containsExactly("Role");
        verifyNoInteractions(templateRepository);
    }

    @Test
    void smtp_parts_for_each_combination_of_bodies() throws Exception {
        // HTML and plain text: multipart/alternative.
        Mime both = Mime.of(sendDirectly("<p>Hi</p>", "Hi"));
        assertThat(both.leaves).containsExactly("text/plain", "text/html");
        assertThat(both.multiparts).contains("multipart/alternative");
        // HTML only: one text/html part.
        Mime html = Mime.of(sendDirectly("<p>Hi</p>", null));
        assertThat(html.leaves).containsExactly("text/html");
        assertThat(html.multiparts).doesNotContain("multipart/alternative");
        // Plain text only: one text/plain part.
        Mime plain = Mime.of(sendDirectly(null, "Hi"));
        assertThat(plain.leaves).containsExactly("text/plain");
        // Neither, as the processor sends it without content ("" bodies): an empty text/plain part.
        Mime empty = Mime.of(sendDirectly("", ""));
        assertThat(empty.leaves).containsExactly("text/plain");
        assertThat(empty.text.get("text/plain")).isEmpty();
        // Neither, with null bodies: no text part at all.
        assertThat(Mime.of(sendDirectly(null, null)).leaves).isEmpty();
    }

    // ── Validation (same rules as frontend/src/lib/campaignContent.ts) ───────────

    @Test
    void the_subject_and_the_body_are_validated() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

        // Subject: one line, at most 998 characters, tab allowed.
        assertThat(paths(validator.validate(request("Hello\nWorld", null)))).containsExactly("subject");
        assertThat(paths(validator.validate(request("Hello\r\nBcc: someone@test.local", null)))).containsExactly("subject");
        assertThat(paths(validator.validate(request("Hello\rWorld", null)))).containsExactly("subject");
        assertThat(paths(validator.validate(request("Bell " + (char) 0x07, null)))).containsExactly("subject");
        assertThat(paths(validator.validate(request("Tab\tis fine {{FirstName}}", null)))).isEmpty();
        assertThat(paths(validator.validate(request("s".repeat(998), null)))).isEmpty();
        assertThat(paths(validator.validate(request("s".repeat(999), null)))).containsExactly("subject");
        assertThat(validator.validate(request("Hello\nWorld", null)))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("must be one line without control characters");

        // Body: at most 100,000 characters, tab and line breaks allowed, other control characters refused.
        assertThat(paths(validator.validate(request("Hi", "b".repeat(100_000))))).isEmpty();
        assertThat(paths(validator.validate(request("Hi", "b".repeat(100_001))))).containsExactly("customBody");
        assertThat(paths(validator.validate(request("Hi", "Line one\r\nLine two\n\tindented\r")))).isEmpty();
        for (int code : new int[] {0x00, 0x07, 0x08, 0x0B, 0x0C, 0x0E, 0x1B, 0x1F, 0x7F, 0x85, 0x9F}) {
            assertThat(paths(validator.validate(request("Hi", "text " + (char) code + " more"))))
                    .as("U+%04X in the body", code)
                    .containsExactly("customBody");
        }
        // Not control characters: non-breaking space, emoji, right-to-left text.
        assertThat(paths(validator.validate(request("Hi", "caf\u00E9 \u00A0 \uD83D\uDE00 \u05E9\u05DC\u05D5\u05DD")))).isEmpty();

        // The preview request applies the body rules to its custom body.
        TemplateContentRenderRequest preview = new TemplateContentRenderRequest("Hi", null, null,
                new TemplateContentRenderRequest.Sample(null, null, null, null, null, null, null),
                "text " + (char) 0x00);
        assertThat(paths(validator.validate(preview))).containsExactly("customBody");

        // The helpers the launch check uses give the same answers.
        assertThat(EmailContentRules.subjectProblem("a\nb")).isEqualTo(EmailContentRules.SUBJECT_NOT_ONE_LINE);
        assertThat(EmailContentRules.subjectProblem("s".repeat(999))).isEqualTo(EmailContentRules.SUBJECT_TOO_LONG);
        assertThat(EmailContentRules.subjectProblem("Tab\tok")).isNull();
        assertThat(EmailContentRules.bodyProblem("b".repeat(100_001))).isEqualTo(EmailContentRules.BODY_TOO_LONG);
        assertThat(EmailContentRules.bodyProblem("x" + (char) 0x00)).isEqualTo(EmailContentRules.BODY_CONTROL_CHARACTERS);
        assertThat(EmailContentRules.bodyProblem("a\r\n\tb")).isNull();
        for (int i = 0; i <= 0xFFFF; i++) {
            char c = (char) i;
            boolean control = Character.isISOControl(c);
            assertThat(EmailContentRules.isAllowedInSubject(c)).as("subject U+%04X", i).isEqualTo(!control || c == '\t');
            assertThat(EmailContentRules.isAllowedInBody(c)).as("body U+%04X", i)
                    .isEqualTo(!control || c == '\t' || c == '\n' || c == '\r');
        }
    }

    @Test
    void the_frontend_applies_the_same_limits_and_control_characters() throws IOException {
        Path source = Path.of("frontend", "src", "lib", "campaignContent.ts");
        assumeTrue(Files.exists(source), "frontend sources are not checked out next to the backend");
        String ts = Files.readString(source, StandardCharsets.UTF_8);

        assertThat(ts).contains("export const SUBJECT_MAX_LENGTH = " + EmailContentRules.SUBJECT_MAX_LENGTH + ";")
                .contains("export const BODY_MAX_LENGTH = 100_000;");
        assertThat(EmailContentRules.BODY_MAX_LENGTH).isEqualTo(100_000);

        // isControlChar: the same set as Character.isISOControl.
        int start = ts.indexOf("export function isControlChar(code: number): boolean {");
        assertThat(start).as("isControlChar in %s", source).isNotNegative();
        String body = ts.substring(start, ts.indexOf("\n}", start));
        Set<Integer> frontend = new HashSet<>();
        Matcher range = Pattern.compile("\\(code >= 0x([0-9a-fA-F]+) && code <= 0x([0-9a-fA-F]+)\\)").matcher(body);
        while (range.find()) {
            for (int i = Integer.parseInt(range.group(1), 16); i <= Integer.parseInt(range.group(2), 16); i++) frontend.add(i);
        }
        Matcher upTo = Pattern.compile("code <= 0x([0-9a-fA-F]+)").matcher(range.replaceAll(""));
        while (upTo.find()) {
            for (int i = 0; i <= Integer.parseInt(upTo.group(1), 16); i++) frontend.add(i);
        }
        for (int i = 0; i <= 0xFFFF; i++) {
            assertThat(frontend.contains(i)).as("U+%04X", i).isEqualTo(Character.isISOControl((char) i));
        }

        // The control characters each field allows.
        assertThat(codes(ts, "SUBJECT_ALLOWED_CONTROLS")).containsExactly((int) '\t');
        assertThat(codes(ts, "BODY_ALLOWED_CONTROLS")).containsExactly((int) '\t', (int) '\n', (int) '\r');
    }

    // ── Migration and mapping ───────────────────────────────────────────────────

    @Test
    void v7_adds_one_nullable_column_that_the_entity_maps() throws Exception {
        Path migrations = Path.of("src", "main", "resources", "db", "migration");
        // The SQL statements of V7, without its comment lines.
        String v7 = String.join("\n", Files.readAllLines(migrations.resolve("V7__campaign_custom_body.sql")).stream()
                .filter(line -> !line.trim().startsWith("--") && !line.isBlank()).toList());
        assertThat(v7).isEqualTo("ALTER TABLE campaigns ADD COLUMN custom_body TEXT;");

        Field field = Campaign.class.getDeclaredField("customBody");
        Column column = field.getAnnotation(Column.class);
        assertThat(column.name()).isEqualTo("custom_body");
        assertThat(column.columnDefinition()).isEqualTo("TEXT");
        assertThat(column.nullable()).isTrue();
        assertThat(field.getType()).isEqualTo(String.class);

        // V7 is the next version after V6; V8 (organizations) is the only later one.
        List<String> versions = new ArrayList<>();
        try (Stream<Path> files = Files.list(migrations)) {
            files.map(p -> p.getFileName().toString()).filter(n -> n.matches("V\\d+__.*\\.sql"))
                    .forEach(n -> versions.add(n.substring(1, n.indexOf("__"))));
        }
        assertThat(versions.stream().map(Integer::parseInt).sorted().toList()).containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private static List<String> paths(Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream().map(v -> v.getPropertyPath().toString()).toList();
    }

    /** Numbers in {@code const NAME = [0x09, ...];} of the frontend source. */
    private static List<Integer> codes(String ts, String name) {
        Matcher m = Pattern.compile("const " + name + " = \\[([^\\]]*)\\];").matcher(ts);
        assertThat(m.find()).as(name).isTrue();
        List<Integer> out = new ArrayList<>();
        Matcher hex = Pattern.compile("0x([0-9a-fA-F]+)").matcher(m.group(1));
        while (hex.find()) out.add(Integer.parseInt(hex.group(1), 16));
        return out;
    }

    /** The message the email consumer hands to the SMTP provider (a mocked JavaMailSender). */
    private MimeMessage sendThroughConsumer(EmailMessage message) {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        RateLimiterService rateLimiter = mock(RateLimiterService.class);
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, Object> values = mock(ValueOperations.class);
        when(redis.hasKey("email:sent:" + message.idempotencyKey())).thenReturn(false);
        when(redis.opsForValue()).thenReturn(values);
        when(rateLimiter.tryAcquire(CAMPAIGN_ID)).thenReturn(true);

        new EmailConsumer(new SmtpEmailProvider(mailSender, ""), campaignRecipientRepository, campaignRepository,
                rateLimiter, mock(EmailLogService.class), redis).consume(message);

        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        return sent.getValue();
    }

    private MimeMessage sendDirectly(String html, String plain) {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(new MimeMessage(Session.getInstance(new Properties())));
        new SmtpEmailProvider(sender, "").send(EmailRequest.builder()
                .to("ada@test.local").from("sender@test.local").subject("Hi").htmlBody(html).plainBody(plain).build());
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        return sent.getValue();
    }

    /** The MIME tree of a built message: multipart types on the way and the leaf parts in order. */
    private record Mime(List<String> multiparts, List<String> leaves, Map<String, String> text) {
        static Mime of(MimeMessage message) throws Exception {
            Mime mime = new Mime(new ArrayList<>(), new ArrayList<>(), new LinkedHashMap<>());
            mime.walk(message);
            return mime;
        }

        private void walk(Part part) throws Exception {
            Object content = part.getContent();
            if (content instanceof Multipart mp) {
                multiparts.add(baseType(mp.getContentType()));
                for (int i = 0; i < mp.getCount(); i++) walk(mp.getBodyPart(i));
            } else {
                String type = baseType(part.getDataHandler().getContentType());
                leaves.add(type);
                text.put(type, String.valueOf(content));
            }
        }

        private static String baseType(String contentType) {
            int semicolon = contentType.indexOf(';');
            return (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim().toLowerCase(Locale.ROOT);
        }
    }
}
