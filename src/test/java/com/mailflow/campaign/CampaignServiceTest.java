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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private CampaignService campaignService;

    private Campaign draftCampaign;
    private EmailTemplate template;

    @BeforeEach
    void setUp() {
        campaignService = new CampaignService(
                campaignRepository, templateService, jobLauncher,
                recipientRepository, campaignRecipientRepository, unsubscribeService);

        template = new EmailTemplate();
        template.setId(1L);
        template.setName("Test Template");
        template.setSubject("Test Subject");

        draftCampaign = Campaign.builder()
                .id(1L)
                .name("Test Campaign")
                .subject("Test Subject")
                .senderEmail("sender@example.com")
                .status(CampaignStatus.DRAFT)
                .template(template)
                .build();
    }

    @Test
    void creates_campaign_as_draft() {
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> {
            Campaign c = inv.getArgument(0);
            return Campaign.builder()
                    .id(1L).name(c.getName()).subject(c.getSubject())
                    .senderEmail(c.getSenderEmail()).status(CampaignStatus.DRAFT)
                    .build();
        });

        CampaignRequest request = new CampaignRequest(
                "Test Campaign", null, "Test Subject", null,
                null, "sender@example.com", null, null, null, null);

        CampaignResponse response = campaignService.create(request);
        assertThat(response.status()).isEqualTo(CampaignStatus.DRAFT);
        assertThat(response.name()).isEqualTo("Test Campaign");
    }

    @Test
    void launches_valid_campaign() {
        com.mailflow.recipient.entity.Recipient r = new com.mailflow.recipient.entity.Recipient();
        r.setId(10L);
        r.setEmail("user@example.com");
        r.setStatus(com.mailflow.recipient.entity.RecipientStatus.ACTIVE);

        when(campaignRepository.findById(1L)).thenReturn(Optional.of(draftCampaign));
        when(recipientRepository.findAllActiveRecipients()).thenReturn(java.util.List.of(r));
        when(campaignRecipientRepository.countByCampaignId(1L)).thenReturn(1L);
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);
        doNothing().when(jobLauncher).launchCampaignJob(any());

        CampaignResponse response = campaignService.launch(1L);

        assertThat(response).isNotNull();
        verify(jobLauncher).launchCampaignJob(1L);
    }

    @Test
    void throws_conflict_when_launching_completed_campaign() {
        draftCampaign.setStatus(CampaignStatus.COMPLETED);
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(draftCampaign));

        assertThatThrownBy(() -> campaignService.launch(1L))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void throws_validation_error_when_no_template() {
        draftCampaign.setTemplate(null);
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(draftCampaign));

        assertThatThrownBy(() -> campaignService.launch(1L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("template");
    }

    @Test
    void cancels_running_campaign() {
        draftCampaign.setStatus(CampaignStatus.RUNNING);
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(draftCampaign));
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);

        campaignService.cancel(1L);

        verify(campaignRepository).save(argThat(c -> c.getStatus() == CampaignStatus.CANCELLED));
    }

    @Test
    void pauses_running_campaign() {
        draftCampaign.setStatus(CampaignStatus.RUNNING);
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(draftCampaign));
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);

        campaignService.pause(1L);

        verify(campaignRepository).save(argThat(c -> c.getStatus() == CampaignStatus.PAUSED));
    }

    @Test
    void resume_paused_campaign_re_launches_job() {
        draftCampaign.setStatus(CampaignStatus.PAUSED);
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(draftCampaign));
        when(campaignRepository.save(any(Campaign.class))).thenReturn(draftCampaign);
        doNothing().when(jobLauncher).launchCampaignJob(any());

        campaignService.resume(1L);

        verify(jobLauncher).launchCampaignJob(1L);
    }

    @Test
    void throws_conflict_when_pausing_non_running_campaign() {
        // DRAFT is not pauseable
        when(campaignRepository.findById(1L)).thenReturn(Optional.of(draftCampaign));

        assertThatThrownBy(() -> campaignService.pause(1L))
                .isInstanceOf(ConflictException.class);
    }
}
