package com.mailflow.batch;

import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.email.UnsubscribeService;
import com.mailflow.queue.EmailMessage;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.template.service.TemplateEngineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies that {@link CampaignItemProcessor} exposes the full personalization
 * model — standard fields, custom fields (role/phone/location/interest), and
 * capitalized aliases ({{Name}}, {{Company}}, {{Role}}…) — to the template engine.
 */
@ExtendWith(MockitoExtension.class)
class CampaignItemProcessorModelTest {

    @Mock private TemplateEngineService templateEngine;
    @Mock private UnsubscribeService unsubscribeService;
    @Mock private CampaignRecipientRepository campaignRecipientRepository;

    @Test
    @SuppressWarnings("unchecked")
    void builds_model_with_custom_fields_and_capitalized_aliases() {
        CampaignItemProcessor processor =
                new CampaignItemProcessor(templateEngine, unsubscribeService, campaignRecipientRepository);

        Map<String, String> custom = new HashMap<>();
        custom.put("role", "Engineer");
        custom.put("location", "London");
        custom.put("interest", "Mathematics");

        Recipient recipient = Recipient.builder()
                .id(1L)
                .email("ada@example.com")
                .name("Ada Lovelace")
                .firstName("Ada")
                .lastName("Lovelace")
                .company("Analytical Engines")
                .customFields(custom)
                .build();

        Campaign campaign = Campaign.builder()
                .id(10L)
                .subject("Hello {{Name}} from {{Company}}")
                .senderName("MailFlow")
                .senderEmail("noreply@mailflow.local")
                .build();

        CampaignRecipient cr = CampaignRecipient.builder()
                .id(100L)
                .campaign(campaign)
                .recipient(recipient)
                .idempotencyKey(CampaignRecipient.buildIdempotencyKey(10L, 1L))
                .deliveryStatus(CampaignRecipient.DeliveryStatus.PENDING)
                .retryCount(0)
                .build();

        when(unsubscribeService.isUnsubscribed("ada@example.com")).thenReturn(false);
        // Echo the subject back; capture the model that was passed in.
        when(templateEngine.render(anyString(), any(Map.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        EmailMessage msg = processor.process(cr);
        assertThat(msg).isNotNull();
        assertThat(msg.toAddress()).isEqualTo("ada@example.com");

        ArgumentCaptor<Map<String, Object>> modelCaptor = ArgumentCaptor.forClass(Map.class);
        // The subject render call carries the model.
        org.mockito.Mockito.verify(templateEngine)
                .render(org.mockito.ArgumentMatchers.eq("Hello {{Name}} from {{Company}}"),
                        modelCaptor.capture());
        Map<String, Object> model = modelCaptor.getValue();

        // Standard lower-camel keys
        assertThat(model).containsEntry("name", "Ada Lovelace");
        assertThat(model).containsEntry("firstName", "Ada");
        assertThat(model).containsEntry("company", "Analytical Engines");

        // Capitalized aliases
        assertThat(model).containsEntry("Name", "Ada Lovelace");
        assertThat(model).containsEntry("Company", "Analytical Engines");
        assertThat(model).containsEntry("FirstName", "Ada");

        // Custom fields + their capitalized aliases
        assertThat(model).containsEntry("role", "Engineer");
        assertThat(model).containsEntry("Role", "Engineer");
        assertThat(model).containsEntry("location", "London");
        assertThat(model).containsEntry("Location", "London");
        assertThat(model).containsEntry("interest", "Mathematics");
        assertThat(model).containsEntry("Interest", "Mathematics");
    }
}
