package com.mailflow.campaign;

import com.mailflow.batch.CampaignJobLauncher;
import com.mailflow.campaign.dto.CampaignRequest;
import com.mailflow.campaign.dto.CampaignResponse;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.campaign.service.CampaignService;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.service.TemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CampaignService.
 *
 * CampaignService uses a manual constructor (not @RequiredArgsConstructor)
 * so we build it manually from mocks instead of using @InjectMocks.
 */
@ExtendWith(MockitoExtension.class)
class CampaignServiceTest {

    @Mock private CampaignRepository campaignRepository;
    @Mock private TemplateService templateService;
    @Mock private CampaignJobLauncher jobLauncher;
    @Mock private com.mailflow.recipient.repository.RecipientRepository recipientRepository;
    @Mock private com.mailflow.campaign.repository.CampaignRecipientRepository campaignRecipientRepository;
    @Mock private com.mailflow.email.UnsubscribeService unsubscribeService;
    @Mock private com.mailflow.campaign.repository.CampaignTargetAddressRepository targetAddressRepository;

    private CampaignService campaignService;

    private Campaign draftCampaign;
    private EmailTemplate template;

    @BeforeEach
    void setUp() {
        campaignService = new CampaignService(
                campaignRepository, templateService, jobLauncher,
                recipientRepository, campaignRecipientRepository, unsubscribeService,
                targetAddressRepository);

        template = new EmailTemplate();
        template.setId(1L);
        template.setName("Test Template");
        template.setSubject("Test Subject");
        template.setHtmlBody("<p>Hello {{FirstName}}</p>");

        draftCampaign = Campaign.builder().organizationId(com.mailflow.tenant.TestTenant.ORG)
                .id(1L)
                .name("Test Campaign")
                .subject("Test Subject")
                .senderEmail("sender@example.com")
                .status(CampaignStatus.DRAFT)
                .template(template)
                .recipientIds(new java.util.HashSet<>(java.util.Set.of(10L)))
                .build();
    }

    private com.mailflow.recipient.entity.Recipient activeRecipient(long id, String email) {
        com.mailflow.recipient.entity.Recipient r = new com.mailflow.recipient.entity.Recipient();
        r.setId(id);
        r.setEmail(email);
        r.setStatus(com.mailflow.recipient.entity.RecipientStatus.ACTIVE);
        return r;
    }

    @Test
    void creates_campaign_as_draft() {
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> {
            Campaign c = inv.getArgument(0);
            return Campaign.builder().organizationId(com.mailflow.tenant.TestTenant.ORG)
                    .id(1L).name(c.getName()).subject(c.getSubject())
                    .senderEmail(c.getSenderEmail()).status(CampaignStatus.DRAFT)
                    .build();
        });

        CampaignRequest request = new CampaignRequest(
                "Test Campaign", null, "Test Subject", null,
                null, "sender@example.com", null, null, null, null, null, null);

        CampaignResponse response = campaignService.create(request);
        assertThat(response.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(response.name()).isEqualTo("Test Campaign");
    }

    @Test
    void launches_valid_campaign() {
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));
        when(recipientRepository.findByIdsIn(com.mailflow.tenant.TestTenant.ORG, java.util.Set.of(10L)))
                .thenReturn(java.util.List.of(activeRecipient(10L, "user@example.com")));
        when(campaignRecipientRepository.countByCampaignId(1L)).thenReturn(1L);
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);
        doNothing().when(jobLauncher).launchCampaignJob(any());

        CampaignResponse response = campaignService.launch(1L);

        assertThat(response).isNotNull();
        assertThat(response.status()).isEqualTo(CampaignStatus.QUEUED);
        verify(campaignRecipientRepository).saveAll(anyList());
        verify(jobLauncher).launchCampaignJob(1L);
        // Only the selected recipients are resolved; there is no "all contacts" fallback.
        verify(recipientRepository, never()).findAllActiveRecipients();
    }

    @Test
    void throws_conflict_when_launching_completed_campaign() {
        draftCampaign.setStatus(CampaignStatus.COMPLETED);
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));

        assertThatThrownBy(() -> campaignService.launch(1L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void throws_validation_error_when_no_template() {
        draftCampaign.setTemplate(null);
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));

        assertThatThrownBy(() -> campaignService.launch(1L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("template");
    }

    // ── Part 1 regression tests: template-less drafts vs launch validation ──────

    @Test
    void launch_without_template_reports_exact_actionable_message() {
        // No template and no body of its own. Before campaigns could carry their own
        // body this message was "Campaign has no template assigned".
        draftCampaign.setTemplate(null);
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));

        assertThatThrownBy(() -> campaignService.launch(1L))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Campaign has no email content: choose a template or write the email");
        // Launch must NOT proceed: no recipients resolved, no job launched.
        verify(jobLauncher, never()).launchCampaignJob(any());
        verify(campaignRecipientRepository, never()).saveAll(any());
    }

    @Test
    void create_allows_draft_without_template() {
        // A campaign may be SAVED as a draft with no template (template only
        // required at launch). create() must not call the template service.
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        CampaignRequest request = new CampaignRequest(
                "Draft no template", null, "Subject", /* templateId */ null,
                null, "sender@example.com", null, null, null, null, null, null);

        CampaignResponse response = campaignService.create(request);

        assertThat(response.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(response.templateId()).isNull();
        verify(templateService, never()).getOrThrow(any());
    }

    @Test
    void update_allows_draft_to_remain_without_template() {
        draftCampaign.setTemplate(null);
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        CampaignRequest request = new CampaignRequest(
                "Edited draft", "desc", "New subject", /* templateId */ null,
                null, "sender@example.com", null, null, null, null, null, null);

        CampaignResponse response = campaignService.update(1L, request);

        assertThat(response.subject()).isEqualTo("New subject");
        assertThat(response.templateId()).isNull();
        verify(templateService, never()).getOrThrow(any());
    }

    @Test
    void repeated_launch_after_queued_is_rejected_as_conflict() {
        // First launch succeeds and moves the campaign to QUEUED (not runnable again).
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));
        when(recipientRepository.findByIdsIn(com.mailflow.tenant.TestTenant.ORG, java.util.Set.of(10L)))
                .thenReturn(java.util.List.of(activeRecipient(10L, "user@example.com")));
        when(campaignRecipientRepository.countByCampaignId(1L)).thenReturn(1L);
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);
        doNothing().when(jobLauncher).launchCampaignJob(any());

        campaignService.launch(1L);              // draftCampaign.status becomes QUEUED
        assertThat(draftCampaign.getStatus()).isEqualTo(CampaignStatus.QUEUED);

        // A second launch attempt on the now-QUEUED campaign must be rejected,
        // and must not launch the job a second time.
        assertThatThrownBy(() -> campaignService.launch(1L))
                .isInstanceOf(ConflictException.class);
        verify(jobLauncher, times(1)).launchCampaignJob(1L);
    }

    @Test
    void cancels_running_campaign() {
        draftCampaign.setStatus(CampaignStatus.RUNNING);
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);

        campaignService.cancel(1L);

        verify(campaignRepository).save(argThat(c -> c.getStatus() == CampaignStatus.CANCELLED));
    }

    @Test
    void pauses_running_campaign() {
        draftCampaign.setStatus(CampaignStatus.RUNNING);
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);

        campaignService.pause(1L);

        verify(campaignRepository).save(argThat(c -> c.getStatus() == CampaignStatus.PAUSED));
    }

    @Test
    void resume_paused_campaign_re_launches_job() {
        draftCampaign.setStatus(CampaignStatus.PAUSED);
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);
        doNothing().when(jobLauncher).launchCampaignJob(any());

        campaignService.resume(1L);

        verify(jobLauncher).launchCampaignJob(1L);
    }

    @Test
    void throws_conflict_when_pausing_non_running_campaign() {
        // DRAFT is not pauseable
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));

        assertThatThrownBy(() -> campaignService.pause(1L))
                .isInstanceOf(ConflictException.class);
    }

    // ── Launch requires recipients and a live template ─────────────────────────

    @Test
    void launch_without_recipients_is_rejected_and_never_targets_all_contacts() {
        draftCampaign.setRecipientIds(new java.util.HashSet<>());
        draftCampaign.setRecipientGroupIds(new java.util.HashSet<>());
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));

        assertThatThrownBy(() -> campaignService.launch(1L))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Campaign has no recipients selected");
        // The old behaviour silently fell back to every active contact.
        verify(recipientRepository, never()).findAllActiveRecipients();
        verify(campaignRecipientRepository, never()).saveAll(any());
        verify(jobLauncher, never()).launchCampaignJob(any());
        assertThat(draftCampaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }

    @Test
    void launch_with_deleted_template_is_rejected() {
        template.setDeletedAt(java.time.Instant.now());
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));

        assertThatThrownBy(() -> campaignService.launch(1L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("template assigned to this campaign has been deleted");
        verify(jobLauncher, never()).launchCampaignJob(any());
    }

    @Test
    void launch_with_a_template_without_any_body_is_rejected() {
        // Neither an HTML nor a plain-text body: every email would carry only a subject.
        template.setHtmlBody(null);
        template.setPlainBody("   ");
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));

        assertThatThrownBy(() -> campaignService.launch(1L))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Campaign has no email content: choose a template or write the email. "
                        + "The template assigned to this campaign has no body.");
        verify(campaignRecipientRepository, never()).saveAll(any());
        verify(jobLauncher, never()).launchCampaignJob(any());
        assertThat(draftCampaign.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }

    @Test
    void launch_accepts_a_template_with_only_a_plain_text_body() {
        template.setHtmlBody(null);
        template.setPlainBody("Hello {{FirstName}}");
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));
        when(recipientRepository.findByIdsIn(com.mailflow.tenant.TestTenant.ORG, java.util.Set.of(10L)))
                .thenReturn(java.util.List.of(activeRecipient(10L, "user@example.com")));
        when(campaignRecipientRepository.countByCampaignId(1L)).thenReturn(1L);
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);

        assertThat(campaignService.launch(1L).status()).isEqualTo(CampaignStatus.QUEUED);
        verify(jobLauncher).launchCampaignJob(1L);
    }

    @Test
    void resume_starts_the_batch_job_only_after_the_transaction_commits() {
        draftCampaign.setStatus(CampaignStatus.PAUSED);
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);

        TransactionSynchronizationManager.initSynchronization();
        try {
            campaignService.resume(1L);
            assertThat(draftCampaign.getStatus()).isEqualTo(CampaignStatus.QUEUED);
            verify(jobLauncher, never()).launchCampaignJob(any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(jobLauncher, never()).launchCampaignJob(any());

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(jobLauncher, times(1)).launchCampaignJob(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void reply_to_must_be_an_email_address() {
        jakarta.validation.Validator validator =
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

        CampaignRequest invalid = new CampaignRequest(
                "Campaign", null, "Subject", null,
                null, "sender@example.com", "not an email", null, null, null, null, null);
        assertThat(validator.validate(invalid))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("replyTo");

        CampaignRequest valid = new CampaignRequest(
                "Campaign", null, "Subject", null,
                null, "sender@example.com", "replies@example.com", null, null, null, null, null);
        assertThat(validator.validate(valid)).isEmpty();
        // Optional: no reply-to at all is still allowed.
        assertThat(validator.validate(new CampaignRequest(
                "Campaign", null, "Subject", null,
                null, "sender@example.com", null, null, null, null, null, null))).isEmpty();
    }

    @Test
    void launch_is_rejected_when_no_selected_recipient_can_be_emailed() {
        com.mailflow.recipient.entity.Recipient unsubscribed = activeRecipient(10L, "user@example.com");
        unsubscribed.setStatus(com.mailflow.recipient.entity.RecipientStatus.UNSUBSCRIBED);
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));
        when(recipientRepository.findByIdsIn(com.mailflow.tenant.TestTenant.ORG, java.util.Set.of(10L)))
                .thenReturn(java.util.List.of(unsubscribed));
        when(campaignRecipientRepository.countByCampaignId(1L)).thenReturn(0L);

        assertThatThrownBy(() -> campaignService.launch(1L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("None of the selected recipients can be emailed");
        verify(jobLauncher, never()).launchCampaignJob(any());
    }

    @Test
    void launch_starts_batch_job_only_after_the_transaction_commits() {
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));
        when(recipientRepository.findByIdsIn(com.mailflow.tenant.TestTenant.ORG, java.util.Set.of(10L)))
                .thenReturn(java.util.List.of(activeRecipient(10L, "user@example.com")));
        when(campaignRecipientRepository.countByCampaignId(1L)).thenReturn(1L);
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);

        TransactionSynchronizationManager.initSynchronization();
        try {
            campaignService.launch(1L);
            // Still inside the (simulated) transaction: the job must not have started,
            // otherwise it could read uncommitted rows or survive a rollback.
            verify(jobLauncher, never()).launchCampaignJob(any());

            // A rollback must not start it either.
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verify(jobLauncher, never()).launchCampaignJob(any());

            // Commit: the job starts exactly once.
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(jobLauncher, times(1)).launchCampaignJob(1L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ── Draft edit round-trip (template, recipients, reply-to persist) ─────────

    @Test
    void update_persists_template_recipients_and_reply_to() {
        EmailTemplate other = new EmailTemplate();
        other.setId(2L);
        other.setName("Other template");
        when(campaignRepository.findOneById(1L)).thenReturn(Optional.of(draftCampaign));
        when(templateService.getOrThrow(2L)).thenReturn(other);
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> inv.getArgument(0));

        CampaignRequest request = new CampaignRequest(
                "Edited", "desc", "Hello {{FirstName}}", 2L,
                "Sender", "sender@example.com", "replies@example.com", null,
                java.util.List.of(), java.util.List.of(10L, 11L), null, null);

        CampaignResponse response = campaignService.update(1L, request);

        assertThat(response.templateId()).isEqualTo(2L);
        assertThat(response.templateName()).isEqualTo("Other template");
        assertThat(response.replyTo()).isEqualTo("replies@example.com");
        assertThat(response.recipientIds()).containsExactlyInAnyOrder(10L, 11L);
        assertThat(response.recipientGroupIds()).isEmpty();
    }
}
