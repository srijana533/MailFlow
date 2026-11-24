package com.mailflow.campaign;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mailflow.batch.CampaignJobLauncher;
import com.mailflow.campaign.dto.CampaignRequest;
import com.mailflow.campaign.dto.CampaignResponse;
import com.mailflow.campaign.dto.TargetAddress;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.entity.CampaignTargetAddress;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.campaign.repository.CampaignTargetAddressRepository;
import com.mailflow.campaign.service.CampaignService;
import com.mailflow.email.UnsubscribeService;
import com.mailflow.recipient.repository.RecipientRepository;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.service.TemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Editing a draft never silently clears data. PUT replaces the scalar fields, but
 * {@code templateId}, {@code recipientIds}, {@code recipientGroupIds} and
 * {@code targetAddresses} change only when the request sends them: {@code null}
 * (or omitted) keeps the stored value and {@code []} clears it.
 */
@ExtendWith(MockitoExtension.class)
class CampaignDraftUpdateTest {

    private static final long CAMPAIGN_ID = 9L;

    private static final TargetAddress ADA = new TargetAddress(
            "ada@test.local", "Ada Lovelace", "Ada", "Lovelace", "Acme", Map.of("Team", "Platform"));
    private static final TargetAddress BOB = new TargetAddress(
            "bob@test.local", null, null, null, null, Map.of());

    @Mock private CampaignRepository campaignRepository;
    @Mock private TemplateService templateService;
    @Mock private CampaignJobLauncher jobLauncher;
    @Mock private RecipientRepository recipientRepository;
    @Mock private CampaignRecipientRepository campaignRecipientRepository;
    @Mock private UnsubscribeService unsubscribeService;
    @Mock private CampaignTargetAddressRepository targetAddressRepository;

    private CampaignService campaignService;
    private EmailTemplate template;

    /** The stored campaign: JPA hands out the same managed entity on every read. */
    private Campaign stored;
    /** In-memory campaign_target_addresses rows of the campaign, in ID order. */
    private final List<CampaignTargetAddress> addresses = new ArrayList<>();
    private long nextAddressId = 1;

    @BeforeEach
    void setUp() {
        campaignService = new CampaignService(
                campaignRepository, templateService, jobLauncher,
                recipientRepository, campaignRecipientRepository, unsubscribeService,
                targetAddressRepository);

        template = new EmailTemplate();
        template.setId(1L);
        template.setName("Welcome");
        template.setSubject("Hello");

        lenient().when(campaignRepository.findById(CAMPAIGN_ID)).thenAnswer(inv -> Optional.ofNullable(stored));
        lenient().when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> {
            Campaign c = inv.getArgument(0);
            if (c.getId() == null) c.setId(CAMPAIGN_ID);
            stored = c;
            return c;
        });
        lenient().when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_ID))
                .thenAnswer(inv -> new ArrayList<>(addresses));
        lenient().when(targetAddressRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<CampaignTargetAddress> rows = inv.getArgument(0);
            for (CampaignTargetAddress t : rows) {
                if (t.getId() == null) {
                    t.setId(nextAddressId++);
                    addresses.add(t);
                }
            }
            return rows;
        });
        lenient().doAnswer(inv -> {
            Iterable<CampaignTargetAddress> gone = inv.getArgument(0);
            gone.forEach(addresses::remove);
            return null;
        }).when(targetAddressRepository).deleteAll(anyIterable());
    }

    /** A saved draft with every field set: template, contacts, a group and two campaign-only addresses. */
    private void seedDraft() {
        stored = Campaign.builder()
                .id(CAMPAIGN_ID)
                .name("Spring launch")
                .description("Internal note")
                .subject("Hello {{FirstName}}")
                .template(template)
                .senderName("MailFlow Team")
                .senderEmail("sender@test.local")
                .replyTo("replies@test.local")
                .status(CampaignStatus.DRAFT)
                .recipientIds(new HashSet<>(Set.of(10L, 11L)))
                .recipientGroupIds(new HashSet<>(Set.of(3L)))
                .build();
        addresses.add(CampaignTargetAddress.builder()
                .id(nextAddressId++).campaignId(CAMPAIGN_ID).email(ADA.email()).name(ADA.name())
                .firstName(ADA.firstName()).lastName(ADA.lastName()).company(ADA.company())
                .customFields(new HashMap<>(ADA.fields())).build());
        addresses.add(CampaignTargetAddress.builder()
                .id(nextAddressId++).campaignId(CAMPAIGN_ID).email(BOB.email()).build());
    }

    private void assertTargetsKept(CampaignResponse response) {
        assertThat(response.templateId()).isEqualTo(1L);
        assertThat(response.recipientIds()).containsExactlyInAnyOrder(10L, 11L);
        assertThat(response.recipientGroupIds()).containsExactly(3L);
        assertThat(response.targetAddresses()).containsExactly(ADA, BOB);
        assertThat(response.targetAddressCount()).isEqualTo(2);
        assertThat(stored.getTemplate()).isSameAs(template);
        assertThat(stored.getRecipientIds()).containsExactlyInAnyOrder(10L, 11L);
        assertThat(stored.getRecipientGroupIds()).containsExactly(3L);
    }

    // ── null keeps ──────────────────────────────────────────────────────────────

    @Test
    void update_with_template_and_lists_omitted_or_null_keeps_them() throws Exception {
        seedDraft();
        // Bound by Jackson as for PUT /api/campaigns/9: templateId and recipientIds are
        // omitted, recipientGroupIds and targetAddresses are explicitly null.
        CampaignRequest request = new ObjectMapper().findAndRegisterModules().readValue("""
                {"name": "Renamed", "description": "Internal note", "subject": "New subject",
                 "senderName": "MailFlow Team", "senderEmail": "sender@test.local",
                 "replyTo": "replies@test.local", "recipientGroupIds": null, "targetAddresses": null}
                """, CampaignRequest.class);
        assertThat(request.templateId()).isNull();
        assertThat(request.recipientIds()).isNull();

        CampaignResponse response = campaignService.update(CAMPAIGN_ID, request);

        assertThat(response.name()).isEqualTo("Renamed");
        assertThat(response.subject()).isEqualTo("New subject");
        assertTargetsKept(response);
        verify(templateService, never()).getOrThrow(any());
        verify(targetAddressRepository, never()).saveAll(any());
        verify(targetAddressRepository, never()).deleteAll(anyIterable());
    }

    // ── [] clears ───────────────────────────────────────────────────────────────

    @Test
    void update_with_empty_lists_clears_recipients_groups_and_campaign_only_addresses() {
        seedDraft();

        CampaignResponse response = campaignService.update(CAMPAIGN_ID, new CampaignRequest(
                "Spring launch", null, "Hello {{FirstName}}", null, null, "sender@test.local", null, null,
                List.of(), List.of(), List.of(), null));

        assertThat(response.recipientIds()).isEmpty();
        assertThat(response.recipientGroupIds()).isEmpty();
        assertThat(response.targetAddresses()).isEmpty();
        assertThat(response.targetAddressCount()).isZero();
        assertThat(stored.getRecipientIds()).isEmpty();
        assertThat(stored.getRecipientGroupIds()).isEmpty();
        assertThat(addresses).isEmpty();
        // templateId null still keeps the template: it can be replaced, never removed.
        assertThat(response.templateId()).isEqualTo(1L);
    }

    // ── Scalar fields: PUT semantics ────────────────────────────────────────────

    @Test
    void scalar_fields_are_replaced_and_a_null_optional_field_is_cleared() {
        seedDraft();
        Instant at = Instant.parse("2030-01-01T09:00:00Z");

        CampaignResponse replaced = campaignService.update(CAMPAIGN_ID, new CampaignRequest(
                "Renamed", "New note", "New subject", null, "New Team", "new.sender@test.local",
                "new.replies@test.local", at, null, null, null, null));

        assertThat(replaced.name()).isEqualTo("Renamed");
        assertThat(replaced.description()).isEqualTo("New note");
        assertThat(replaced.subject()).isEqualTo("New subject");
        assertThat(replaced.senderName()).isEqualTo("New Team");
        assertThat(replaced.senderEmail()).isEqualTo("new.sender@test.local");
        assertThat(replaced.replyTo()).isEqualTo("new.replies@test.local");
        assertThat(replaced.scheduledAt()).isEqualTo(at);
        // Saving never changes the status, so a scheduledAt on a draft doesn't schedule it.
        assertThat(replaced.status()).isEqualTo(CampaignStatus.DRAFT);

        CampaignResponse cleared = campaignService.update(CAMPAIGN_ID, new CampaignRequest(
                "Renamed", null, "New subject", null, null, null, null, null, null, null, null, null));

        assertThat(cleared.description()).isNull();
        assertThat(cleared.senderName()).isNull();
        assertThat(cleared.senderEmail()).isNull();
        assertThat(cleared.replyTo()).isNull();
        assertThat(cleared.scheduledAt()).isNull();
        assertThat(cleared.status()).isEqualTo(CampaignStatus.DRAFT);
        // The template and the recipient lists are not scalar fields: null keeps them.
        assertTargetsKept(cleared);
    }

    // ── Round trip: save, reopen, edit, reopen ──────────────────────────────────

    @Test
    void round_trip_edit_keeps_template_recipients_campaign_only_addresses_sender_and_reply_to() {
        when(templateService.getOrThrow(1L)).thenReturn(template);

        // Save a new draft, then reopen it (GET) as the campaign form does.
        campaignService.create(new CampaignRequest(
                "Spring launch", "Internal note", "Hello {{FirstName}}", 1L,
                "MailFlow Team", "sender@test.local", "replies@test.local", null,
                List.of(3L), List.of(10L, 11L), List.of(ADA, BOB), "Dear {{FirstName}},\nsee you soon."));
        CampaignResponse opened = campaignService.findById(CAMPAIGN_ID);

        // Change the subject and save the full payload: every other field comes from
        // the reopened draft, including scheduledAt, the groups and the body.
        campaignService.update(CAMPAIGN_ID, new CampaignRequest(
                opened.name(), opened.description(), "Hi {{FirstName}}, it's here", opened.templateId(),
                opened.senderName(), opened.senderEmail(), opened.replyTo(), opened.scheduledAt(),
                opened.recipientGroupIds(), opened.recipientIds(), opened.targetAddresses(), opened.customBody()));
        CampaignResponse reopened = campaignService.findById(CAMPAIGN_ID);

        assertThat(reopened.subject()).isEqualTo("Hi {{FirstName}}, it's here");
        assertThat(reopened.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(reopened.name()).isEqualTo("Spring launch");
        assertThat(reopened.description()).isEqualTo("Internal note");
        assertThat(reopened.templateName()).isEqualTo("Welcome");
        assertThat(reopened.senderName()).isEqualTo("MailFlow Team");
        assertThat(reopened.senderEmail()).isEqualTo("sender@test.local");
        assertThat(reopened.replyTo()).isEqualTo("replies@test.local");
        assertThat(reopened.scheduledAt()).isNull();
        assertThat(reopened.customBody()).isEqualTo("Dear {{FirstName}},\nsee you soon.");
        assertTargetsKept(reopened);
        // The unchanged campaign-only addresses keep their rows: only create() wrote them.
        verify(targetAddressRepository, times(1)).saveAll(anyList());
        verify(targetAddressRepository, never()).deleteAll(anyIterable());
    }
}
