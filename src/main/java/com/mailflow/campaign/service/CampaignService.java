package com.mailflow.campaign.service;

import com.mailflow.batch.CampaignJobLauncher;
import com.mailflow.campaign.dto.CampaignRequest;
import com.mailflow.campaign.dto.CampaignResponse;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.common.dto.PageResponse;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.service.TemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Manages the full campaign lifecycle.
 *
 * <p>{@link CampaignJobLauncher} is injected lazily to break a potential
 * initialisation ordering issue with Spring Batch beans.
 */
@Slf4j
@Service
public class CampaignService {

    private final CampaignRepository campaignRepository;
    private final TemplateService templateService;
    private final CampaignJobLauncher jobLauncher;
    private final com.mailflow.recipient.repository.RecipientRepository recipientRepository;
    private final com.mailflow.campaign.repository.CampaignRecipientRepository campaignRecipientRepository;
    private final com.mailflow.email.UnsubscribeService unsubscribeService;

    @Autowired
    public CampaignService(CampaignRepository campaignRepository,
                           TemplateService templateService,
                           @Lazy CampaignJobLauncher jobLauncher,
                           com.mailflow.recipient.repository.RecipientRepository recipientRepository,
                           com.mailflow.campaign.repository.CampaignRecipientRepository campaignRecipientRepository,
                           com.mailflow.email.UnsubscribeService unsubscribeService) {
        this.campaignRepository = campaignRepository;
        this.templateService    = templateService;
        this.jobLauncher        = jobLauncher;
        this.recipientRepository = recipientRepository;
        this.campaignRecipientRepository = campaignRecipientRepository;
        this.unsubscribeService = unsubscribeService;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    @CacheEvict(value = "campaigns", allEntries = true)
    public CampaignResponse create(CampaignRequest request) {
        Campaign campaign = Campaign.builder()
                .name(request.name())
                .description(request.description())
                .subject(request.subject())
                .senderName(request.senderName())
                .senderEmail(request.senderEmail())
                .replyTo(request.replyTo())
                .scheduledAt(request.scheduledAt())
                .status(request.scheduledAt() != null
                        ? CampaignStatus.SCHEDULED : CampaignStatus.DRAFT)
                .build();

        if (request.recipientGroupIds() != null) {
            campaign.setRecipientGroupIds(new java.util.HashSet<>(request.recipientGroupIds()));
        }
        if (request.recipientIds() != null) {
            campaign.setRecipientIds(new java.util.HashSet<>(request.recipientIds()));
        }

        if (request.templateId() != null) {
            EmailTemplate template = templateService.getOrThrow(request.templateId());
            campaign.setTemplate(template);
        }

        return toResponse(campaignRepository.save(campaign));
    }

    @Transactional(readOnly = true)
    public PageResponse<CampaignResponse> findAll(CampaignStatus status, String search, Pageable pageable) {
        Page<Campaign> page = campaignRepository.findAll(status, search, pageable);
        return PageResponse.from(page.map(this::toResponse));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "campaigns", key = "#id")
    public CampaignResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    @CacheEvict(value = "campaigns", allEntries = true)
    public CampaignResponse update(Long id, CampaignRequest request) {
        Campaign campaign = getOrThrow(id);

        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new ConflictException(
                    "Only DRAFT campaigns can be edited. Current status: " + campaign.getStatus());
        }

        campaign.setName(request.name());
        campaign.setDescription(request.description());
        campaign.setSubject(request.subject());
        campaign.setSenderName(request.senderName());
        campaign.setSenderEmail(request.senderEmail());
        campaign.setReplyTo(request.replyTo());
        campaign.setScheduledAt(request.scheduledAt());

        if (request.recipientGroupIds() != null) {
            campaign.setRecipientGroupIds(new java.util.HashSet<>(request.recipientGroupIds()));
        } else {
            campaign.getRecipientGroupIds().clear();
        }

        if (request.recipientIds() != null) {
            campaign.setRecipientIds(new java.util.HashSet<>(request.recipientIds()));
        } else {
            campaign.getRecipientIds().clear();
        }

        if (request.templateId() != null) {
            campaign.setTemplate(templateService.getOrThrow(request.templateId()));
        }

        return toResponse(campaignRepository.save(campaign));
    }

    @Transactional
    @CacheEvict(value = "campaigns", allEntries = true)
    public void delete(Long id) {
        Campaign campaign = getOrThrow(id);
        if (!campaign.isCancellable() && campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new ConflictException(
                    "Campaign cannot be deleted in status: " + campaign.getStatus());
        }
        campaignRepository.delete(campaign);
    }

    // ── Lifecycle transitions ─────────────────────────────────────────────────

    @Transactional
    @CacheEvict(value = "campaigns", allEntries = true)
    public CampaignResponse launch(Long id) {
        Campaign campaign = getOrThrow(id);

        if (!campaign.isRunnable()) {
            throw new ConflictException(
                    "Campaign cannot be launched from status: " + campaign.getStatus());
        }

        validateForLaunch(campaign);

        // Resolve recipients
        java.util.Set<com.mailflow.recipient.entity.Recipient> resolvedRecipients = new java.util.LinkedHashSet<>();
        
        boolean hasDirectRecipients = campaign.getRecipientIds() != null && !campaign.getRecipientIds().isEmpty();
        boolean hasGroupRecipients  = campaign.getRecipientGroupIds() != null && !campaign.getRecipientGroupIds().isEmpty();

        if (hasDirectRecipients) {
            resolvedRecipients.addAll(recipientRepository.findByIdsIn(campaign.getRecipientIds()));
        }
        if (hasGroupRecipients) {
            resolvedRecipients.addAll(recipientRepository.findByGroupIds(campaign.getRecipientGroupIds()));
        }
        if (!hasDirectRecipients && !hasGroupRecipients) {
            // Default: target all active recipients
            resolvedRecipients.addAll(recipientRepository.findAllActiveRecipients());
        }

        // Filter valid & non-unsubscribed recipients
        java.util.List<com.mailflow.campaign.entity.CampaignRecipient> newCampaignRecipients = new java.util.ArrayList<>();
        for (com.mailflow.recipient.entity.Recipient r : resolvedRecipients) {
            if (r.getDeletedAt() != null || r.getStatus() != com.mailflow.recipient.entity.RecipientStatus.ACTIVE) {
                continue;
            }
            if (r.getEmail() == null || r.getEmail().isBlank() || !r.getEmail().contains("@")) {
                continue;
            }
            if (unsubscribeService.isUnsubscribed(r.getEmail())) {
                continue;
            }
            
            if (!campaignRecipientRepository.existsByCampaignIdAndRecipientId(campaign.getId(), r.getId())) {
                String idempotencyKey = com.mailflow.campaign.entity.CampaignRecipient.buildIdempotencyKey(campaign.getId(), r.getId());
                com.mailflow.campaign.entity.CampaignRecipient cr = com.mailflow.campaign.entity.CampaignRecipient.builder()
                        .campaign(campaign)
                        .recipient(r)
                        .deliveryStatus(com.mailflow.campaign.entity.CampaignRecipient.DeliveryStatus.PENDING)
                        .idempotencyKey(idempotencyKey)
                        .build();
                newCampaignRecipients.add(cr);
            }
        }

        if (!newCampaignRecipients.isEmpty()) {
            campaignRecipientRepository.saveAll(newCampaignRecipients);
        }

        long totalCount = campaignRecipientRepository.countByCampaignId(campaign.getId());
        if (totalCount == 0) {
            throw new ValidationException("Campaign contains zero valid recipients to process");
        }

        campaign.setTotalRecipients(totalCount);
        campaign.setStatus(CampaignStatus.QUEUED);
        campaign.setStartedAt(Instant.now());
        campaignRepository.save(campaign);

        jobLauncher.launchCampaignJob(campaign.getId());

        log.info("Campaign {} queued for processing with {} recipients", id, totalCount);
        return toResponse(campaign);
    }

    @Transactional
    @CacheEvict(value = "campaigns", allEntries = true)
    public CampaignResponse pause(Long id) {
        Campaign campaign = getOrThrow(id);

        if (!campaign.isPauseable()) {
            throw new ConflictException("Campaign is not running and cannot be paused");
        }

        campaign.setStatus(CampaignStatus.PAUSED);
        log.info("Campaign {} paused", id);
        return toResponse(campaignRepository.save(campaign));
    }

    @Transactional
    @CacheEvict(value = "campaigns", allEntries = true)
    public CampaignResponse resume(Long id) {
        Campaign campaign = getOrThrow(id);

        if (campaign.getStatus() != CampaignStatus.PAUSED) {
            throw new ConflictException("Campaign is not paused");
        }

        campaign.setStatus(CampaignStatus.QUEUED);
        campaignRepository.save(campaign);

        jobLauncher.launchCampaignJob(campaign.getId());
        log.info("Campaign {} resumed", id);
        return toResponse(campaign);
    }

    @Transactional
    @CacheEvict(value = "campaigns", allEntries = true)
    public CampaignResponse cancel(Long id) {
        Campaign campaign = getOrThrow(id);

        if (!campaign.isCancellable()) {
            throw new ConflictException(
                    "Campaign cannot be cancelled from status: " + campaign.getStatus());
        }

        campaign.setStatus(CampaignStatus.CANCELLED);
        campaign.setCompletedAt(Instant.now());
        log.info("Campaign {} cancelled", id);
        return toResponse(campaignRepository.save(campaign));
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    public Campaign getOrThrow(Long id) {
        return campaignRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Campaign", id));
    }

    private void validateForLaunch(Campaign campaign) {
        if (campaign.getTemplate() == null) {
            throw new ValidationException("Campaign has no template assigned");
        }
        if (campaign.getSubject() == null || campaign.getSubject().isBlank()) {
            throw new ValidationException("Campaign subject is required");
        }
        if (campaign.getSenderEmail() == null || campaign.getSenderEmail().isBlank()) {
            throw new ValidationException("Campaign sender email is required");
        }
    }

    /** Pure mapping method — no cache annotation, just converts entity to DTO. */
    public CampaignResponse toResponse(Campaign c) {
        return new CampaignResponse(
                c.getId(), c.getName(), c.getDescription(), c.getSubject(),
                c.getTemplate() != null ? c.getTemplate().getId() : null,
                c.getTemplate() != null ? c.getTemplate().getName() : null,
                c.getSenderName(), c.getSenderEmail(),
                c.getStatus(), c.getScheduledAt(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getStartedAt(), c.getCompletedAt(),
                c.getTotalRecipients(), c.getSentCount(), c.getFailedCount(),
                c.getSkippedCount(), c.getBouncedCount(), c.getRetryCount(),
                c.successRate(), c.getBatchJobId(),
                c.getRecipientGroupIds() != null ? new java.util.ArrayList<>(c.getRecipientGroupIds()) : java.util.Collections.emptyList(),
                c.getRecipientIds() != null ? new java.util.ArrayList<>(c.getRecipientIds()) : java.util.Collections.emptyList()
        );
    }
}
