package com.mailflow.batch;

import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignRecipient.DeliveryStatus;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.email.UnsubscribeService;
import com.mailflow.queue.EmailMessage;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.template.service.CampaignEmailContent;
import com.mailflow.template.service.PersonalizationModel;
import com.mailflow.template.service.TemplateEngineService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.item.ItemProcessor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Validates and personalises each campaign-recipient record.
 *
 * <p>This class is <em>not</em> annotated with {@code @Component} — it is
 * constructed by {@link CampaignBatchConfig} as a {@code @Bean @StepScope}
 * so that Spring Batch's step-scoped lifecycle is correctly applied.
 *
 * <p>Returns {@code null} to skip a recipient (Spring Batch filters nulls
 * from the chunk before the writer sees them). Every skip of an unprocessed row
 * also marks it SKIPPED, so it leaves the reader's result set (see the paging
 * note in {@link CampaignBatchConfig}).
 *
 * <p>A row without a saved contact is a campaign-only address: the email goes to
 * the row's snapshot email and is personalised from the snapshot values.
 *
 * <p>Content: the campaign's own plain-text body when it has one (sent as the
 * text/plain part, with an HTML part made from the rendered text), otherwise the
 * template's bodies ({@link CampaignEmailContent}).
 *
 * <p>Each item first re-reads the campaign status: once the campaign is no longer
 * RUNNING (paused, cancelled or deleted), the items the reader had already read for
 * the current page are not rendered or published. Their rows stay PENDING, like the
 * rows the reader no longer reads, so a resume picks them up.
 */
@Slf4j
@RequiredArgsConstructor
public class CampaignItemProcessor implements ItemProcessor<CampaignRecipient, EmailMessage> {

    private final TemplateEngineService templateEngine;
    private final UnsubscribeService unsubscribeService;
    private final CampaignRecipientRepository campaignRecipientRepository;
    private final CampaignRepository campaignRepository;

    @Override
    public EmailMessage process(CampaignRecipient cr) {
        Campaign campaign = cr.getCampaign();
        CampaignStatus status = campaignRepository.findStatusById(campaign.getId()).orElse(null);
        if (status != CampaignStatus.RUNNING) {
            log.debug("Campaign {} is {}: delivery row {} not processed",
                    campaign.getId(), status != null ? status : "deleted", cr.getId());
            return null;
        }

        Recipient recipient = cr.getRecipient() != null ? cr.getRecipient() : snapshotRecipient(cr);
        String toAddress    = recipient.getEmail();

        // Already sent — skip (idempotency guard for batch restarts)
        if (cr.getDeliveryStatus() == DeliveryStatus.SENT) {
            log.debug("Skip already-sent: {}", toAddress);
            return null;
        }

        // A row always has an address (database CHECK); never send without one.
        if (toAddress == null || toAddress.isBlank()) {
            log.warn("Skip delivery row {} without an email address", cr.getId());
            campaignRecipientRepository.markFailed(
                    cr.getIdempotencyKey(), DeliveryStatus.SKIPPED,
                    Instant.now(), "No email address");
            return null;
        }

        // Unsubscribe check (by address, so it covers campaign-only addresses too)
        if (unsubscribeService.isUnsubscribed(campaign.getOrganizationId(), toAddress)) {
            log.debug("Skip unsubscribed: {}", toAddress);
            campaignRecipientRepository.markFailed(
                    cr.getIdempotencyKey(), DeliveryStatus.SKIPPED,
                    Instant.now(), "Unsubscribed");
            return null;
        }

        // Build personalisation model
        Map<String, Object> model = buildModel(recipient);

        // The campaign's own body when it has one (plain text, plus an HTML part made
        // from the rendered text), otherwise the template's bodies. Without either,
        // both bodies are "". The preview endpoint renders through the same method.
        CampaignEmailContent.Rendered email = CampaignEmailContent.render(
                templateEngine, model, campaign.getSubject(), campaign.getCustomBody(),
                campaign.getTemplate() != null ? nvl(campaign.getTemplate().getHtmlBody()) : null,
                campaign.getTemplate() != null ? nvl(campaign.getTemplate().getPlainBody()) : null);

        return EmailMessage.builder()
                .idempotencyKey(cr.getIdempotencyKey())
                .campaignId(campaign.getId())
                .recipientId(cr.getRecipient() != null ? cr.getRecipient().getId() : null)
                .toAddress(toAddress)
                .senderName(campaign.getSenderName())
                .senderEmail(campaign.getSenderEmail())
                .replyTo(campaign.getReplyTo())
                .subject(email.subject())
                .htmlBody(email.htmlBody())
                .plainBody(email.plainBody())
                .attemptNumber(cr.getRetryCount() + 1)
                .createdAt(Instant.now())
                .build();
    }

    /**
     * Builds the personalisation model. Delegates to {@link PersonalizationModel}
     * so the template render-preview endpoint resolves placeholders exactly the
     * same way as a real send.
     */
    private Map<String, Object> buildModel(Recipient recipient) {
        return PersonalizationModel.forRecipient(recipient);
    }

    /**
     * A transient Recipient carrying a campaign-only address's snapshot, so the
     * same {@link PersonalizationModel} builds its merge variables. It is never
     * attached to the row or persisted.
     */
    private static Recipient snapshotRecipient(CampaignRecipient cr) {
        return Recipient.builder()
                .email(cr.getSnapshotEmail())
                .name(cr.getSnapshotName())
                .firstName(cr.getSnapshotFirstName())
                .lastName(cr.getSnapshotLastName())
                .company(cr.getSnapshotCompany())
                .customFields(cr.getSnapshotCustomFields() != null
                        ? new HashMap<>(cr.getSnapshotCustomFields()) : new HashMap<>())
                .build();
    }

    private String nvl(String s) { return s != null ? s : ""; }
}
