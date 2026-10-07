package com.mailflow.batch;

import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignRecipient.DeliveryStatus;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.email.UnsubscribeService;
import com.mailflow.queue.EmailMessage;
import com.mailflow.recipient.entity.Recipient;
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
 * from the chunk before the writer sees them).
 */
@Slf4j
@RequiredArgsConstructor
public class CampaignItemProcessor implements ItemProcessor<CampaignRecipient, EmailMessage> {

    private final TemplateEngineService templateEngine;
    private final UnsubscribeService unsubscribeService;
    private final CampaignRecipientRepository campaignRecipientRepository;

    @Override
    public EmailMessage process(CampaignRecipient cr) {
        Recipient recipient = cr.getRecipient();
        Campaign campaign   = cr.getCampaign();

        // Already sent — skip (idempotency guard for batch restarts)
        if (cr.getDeliveryStatus() == DeliveryStatus.SENT) {
            log.debug("Skip already-sent: {}", recipient.getEmail());
            return null;
        }

        // Unsubscribe check
        if (unsubscribeService.isUnsubscribed(recipient.getEmail())) {
            log.debug("Skip unsubscribed: {}", recipient.getEmail());
            campaignRecipientRepository.markFailed(
                    cr.getIdempotencyKey(), DeliveryStatus.SKIPPED,
                    Instant.now(), "Unsubscribed");
            return null;
        }

        // Build personalisation model
        Map<String, Object> model = buildModel(recipient);

        String personalizedSubject = templateEngine.render(campaign.getSubject(), model);
        String htmlBody  = "";
        String plainBody = "";

        if (campaign.getTemplate() != null) {
            htmlBody  = templateEngine.render(
                    nvl(campaign.getTemplate().getHtmlBody()), model);
            plainBody = templateEngine.render(
                    nvl(campaign.getTemplate().getPlainBody()), model);
        }

        return EmailMessage.builder()
                .idempotencyKey(cr.getIdempotencyKey())
                .campaignId(campaign.getId())
                .recipientId(recipient.getId())
                .toAddress(recipient.getEmail())
                .senderName(campaign.getSenderName())
                .senderEmail(campaign.getSenderEmail())
                .replyTo(campaign.getReplyTo())
                .subject(personalizedSubject)
                .htmlBody(htmlBody)
                .plainBody(plainBody)
                .attemptNumber(cr.getRetryCount() + 1)
                .createdAt(Instant.now())
                .build();
    }

    private Map<String, Object> buildModel(Recipient recipient) {
        Map<String, Object> model = new HashMap<>();

        // Standard column fields (lower-camel keys).
        model.put("name",      nvl(recipient.getName()));
        model.put("email",     recipient.getEmail());
        model.put("firstName", nvl(recipient.getFirstName()));
        model.put("lastName",  nvl(recipient.getLastName()));
        model.put("company",   nvl(recipient.getCompany()));
        model.put("course",    nvl(recipient.getCourse()));

        // Custom fields (role, phone, location, interest, …) stored in JSONB.
        // Added before the aliases so canonical names below take precedence
        // if a custom field happened to collide.
        if (recipient.getCustomFields() != null) {
            model.putAll(recipient.getCustomFields());
        }

        // Capitalised aliases so templates can use {{Name}}, {{Company}},
        // {{Role}}, {{Location}}, {{Interest}} etc. in addition to the
        // lower-camel keys. Missing values resolve to a safe empty string.
        putAlias(model, "Name",      "name");
        putAlias(model, "Email",     "email");
        putAlias(model, "FirstName", "firstName");
        putAlias(model, "LastName",  "lastName");
        putAlias(model, "Company",   "company");
        putAlias(model, "Course",    "course");

        // Extended custom fields: expose both the raw key and a capitalised alias.
        ensureCustom(model, "role",      "Role");
        ensureCustom(model, "phone",     "Phone");
        ensureCustom(model, "location",  "Location");
        ensureCustom(model, "interest",  "Interest");
        ensureCustom(model, "department","Department");

        return model;
    }

    /** Copies the value at {@code sourceKey} to {@code aliasKey} (empty if absent). */
    private void putAlias(Map<String, Object> model, String aliasKey, String sourceKey) {
        Object v = model.get(sourceKey);
        model.put(aliasKey, v != null ? v : "");
    }

    /**
     * Ensures a custom field is present under both its lower-case key and a
     * capitalised alias, defaulting to empty string so {{Role}} etc. never
     * fail template rendering.
     */
    private void ensureCustom(Map<String, Object> model, String lowerKey, String aliasKey) {
        Object v = model.get(lowerKey);
        String safe = v != null ? v.toString() : "";
        model.put(lowerKey, safe);
        model.put(aliasKey, safe);
    }

    private String nvl(String s) { return s != null ? s : ""; }
}
