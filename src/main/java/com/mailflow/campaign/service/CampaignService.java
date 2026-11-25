package com.mailflow.campaign.service;

import com.mailflow.auth.entity.User;
import com.mailflow.batch.CampaignJobLauncher;
import com.mailflow.campaign.dto.CampaignRequest;
import com.mailflow.campaign.dto.CampaignResponse;
import com.mailflow.campaign.dto.TargetAddress;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.entity.CampaignTargetAddress;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.campaign.repository.CampaignTargetAddressRepository;
import com.mailflow.common.dto.PageResponse;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.common.util.EmailContentRules;
import com.mailflow.common.util.EmailValidator;
import com.mailflow.config.RabbitMQConfig;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.service.CampaignEmailContent;
import com.mailflow.template.service.TemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Manages the full campaign lifecycle.
 *
 * <p>{@link CampaignJobLauncher} is injected lazily to break a potential
 * initialisation ordering issue with Spring Batch beans.
 *
 * <p>A campaign targets saved contacts (by ID or group) and/or campaign-only
 * addresses ({@link CampaignTargetAddress}): addresses emailed by this campaign
 * without being saved as contacts. Both are resolved into delivery rows at launch.
 */
@Slf4j
@Service
public class CampaignService {

    /** Maximum number of campaign-only addresses per campaign. */
    public static final int MAX_TARGET_ADDRESSES = 10_000;

    /** Invalid addresses named in a validation error before it says "and N more". */
    private static final int MAX_NAMED_INVALID = 20;

    /** Custom-field keys that would shadow a standard placeholder; dropped from target addresses. */
    private static final Set<String> STANDARD_FIELD_KEYS =
            Set.of("email", "name", "firstname", "lastname", "company");

    /**
     * The 400 answer to launching a campaign without email content (no own body and no
     * usable template). It replaced "Campaign has no template assigned" once campaigns
     * could carry their own body.
     */
    public static final String NO_EMAIL_CONTENT =
            "Campaign has no email content: choose a template or write the email";

    /** The 409 answer to deleting a QUEUED or RUNNING campaign. */
    public static final String DELETE_WHILE_SENDING =
            "This campaign is sending. Cancel it, wait until sending stops, then delete it.";

    /**
     * A delivery row QUEUED longer ago than the queue's message TTL can't have a message
     * left in {@code email.queue} (for example it was dead-lettered), so it no longer
     * blocks deletion.
     */
    public static final Duration QUEUED_ROW_STALE_AFTER =
            Duration.ofMillis(RabbitMQConfig.EMAIL_QUEUE_MESSAGE_TTL_MS);

    private static final DateTimeFormatter MESSAGE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'", Locale.US).withZone(ZoneOffset.UTC);

    private final CampaignRepository campaignRepository;
    private final TemplateService templateService;
    private final CampaignJobLauncher jobLauncher;
    private final com.mailflow.recipient.repository.RecipientRepository recipientRepository;
    private final com.mailflow.campaign.repository.CampaignRecipientRepository campaignRecipientRepository;
    private final com.mailflow.email.UnsubscribeService unsubscribeService;
    private final CampaignTargetAddressRepository targetAddressRepository;

    @Autowired
    public CampaignService(CampaignRepository campaignRepository,
                           TemplateService templateService,
                           @Lazy CampaignJobLauncher jobLauncher,
                           com.mailflow.recipient.repository.RecipientRepository recipientRepository,
                           com.mailflow.campaign.repository.CampaignRecipientRepository campaignRecipientRepository,
                           com.mailflow.email.UnsubscribeService unsubscribeService,
                           CampaignTargetAddressRepository targetAddressRepository) {
        this.campaignRepository = campaignRepository;
        this.templateService    = templateService;
        this.jobLauncher        = jobLauncher;
        this.recipientRepository = recipientRepository;
        this.campaignRecipientRepository = campaignRecipientRepository;
        this.unsubscribeService = unsubscribeService;
        this.targetAddressRepository = targetAddressRepository;
    }

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    @CacheEvict(value = "campaigns", allEntries = true)
    public CampaignResponse create(CampaignRequest request) {
        List<TargetAddress> targetAddresses = normalizeTargetAddresses(request.targetAddresses());

        Campaign campaign = Campaign.builder()
                .name(request.name())
                .description(request.description())
                .subject(request.subject())
                // Blank means none: the template's body is used.
                .customBody(CampaignEmailContent.hasCustomBody(request.customBody()) ? request.customBody() : null)
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

        Campaign saved = campaignRepository.save(campaign);
        if (targetAddresses != null && !targetAddresses.isEmpty()) {
            syncTargetAddresses(saved.getId(), targetAddresses);
        }
        return toDetailResponse(saved);
    }

    /**
     * Each campaign in the page carries {@code targetAddressCount} but not the
     * addresses: a campaign can hold up to 10,000 of them, so including them would
     * make a page of campaigns very large and load every address row. One grouped
     * query counts the addresses of the whole page.
     */
    @Transactional(readOnly = true)
    public PageResponse<CampaignResponse> findAll(CampaignStatus status, String search, Pageable pageable) {
        Page<Campaign> page = campaignRepository.findAll(status, search, pageable);
        Map<Long, Long> counts = targetAddressCounts(page.getContent());
        return PageResponse.from(page.map(c -> toResponse(c, counts.getOrDefault(c.getId(), 0L), null)));
    }

    /**
     * Not cached: status and counters are written by the batch listener and the
     * email consumer through bulk JPQL updates that bypass the cache, so a cached
     * copy showed RUNNING / 0 sent after the campaign had completed.
     *
     * <p>Includes the campaign-only addresses with all their fields, so a draft can
     * be reopened and saved again without losing any of them.
     */
    @Transactional(readOnly = true)
    public CampaignResponse findById(Long id) {
        return toDetailResponse(getOrThrow(id));
    }

    /**
     * Updates a DRAFT. The scalar fields (name, description, subject, sender name and
     * email, reply-to, scheduledAt) use PUT semantics: they are replaced, so a
     * {@code null} optional value clears it. Saving never changes the status.
     *
     * <p>The other fields are never cleared by omission:
     * <ul>
     *   <li>{@code templateId}: {@code null} keeps the current template;</li>
     *   <li>{@code recipientIds}, {@code recipientGroupIds} and {@code targetAddresses}:
     *       {@code null} keeps the stored value, an empty list clears it and any other
     *       list replaces it (unchanged campaign-only addresses keep their stored row);</li>
     *   <li>{@code customBody}: {@code null} keeps the stored body, {@code ""} (or any
     *       blank value) clears it, so the template's body is used, and any other value
     *       replaces it.</li>
     * </ul>
     */
    @Transactional
    @CacheEvict(value = "campaigns", allEntries = true)
    public CampaignResponse update(Long id, CampaignRequest request) {
        Campaign campaign = getOrThrow(id);

        if (campaign.getStatus() != CampaignStatus.DRAFT) {
            throw new ConflictException(
                    "Only DRAFT campaigns can be edited. Current status: " + campaign.getStatus());
        }

        List<TargetAddress> targetAddresses = normalizeTargetAddresses(request.targetAddresses());

        campaign.setName(request.name());
        campaign.setDescription(request.description());
        campaign.setSubject(request.subject());
        campaign.setSenderName(request.senderName());
        campaign.setSenderEmail(request.senderEmail());
        campaign.setReplyTo(request.replyTo());
        campaign.setScheduledAt(request.scheduledAt());

        // null keeps the stored selection, [] clears it: a client that leaves a list
        // out never removes recipients by accident.
        if (request.recipientGroupIds() != null) {
            campaign.setRecipientGroupIds(new java.util.HashSet<>(request.recipientGroupIds()));
        }

        if (request.recipientIds() != null) {
            campaign.setRecipientIds(new java.util.HashSet<>(request.recipientIds()));
        }

        // null keeps the current template; a draft's template is replaced, never removed.
        if (request.templateId() != null) {
            campaign.setTemplate(templateService.getOrThrow(request.templateId()));
        }

        // null keeps the stored body (as for the lists); "" clears it.
        if (request.customBody() != null) {
            campaign.setCustomBody(CampaignEmailContent.hasCustomBody(request.customBody())
                    ? request.customBody() : null);
        }

        if (targetAddresses != null) {
            syncTargetAddresses(campaign.getId(), targetAddresses);
        }

        return toDetailResponse(campaignRepository.save(campaign));
    }

    /**
     * Soft-deletes a campaign. The row stays, with {@code deleted_at} and
     * {@code deleted_by} set, and so does its delivery history (delivery rows with
     * their address snapshots, email logs, unsubscribe entries). The campaign is hidden
     * from lists, search, GET, analytics, the dashboard and the scheduler, and can no
     * longer be edited, launched, paused, resumed or cancelled. Its campaign-only
     * address list is deleted. Contacts, groups, templates and other campaigns are
     * never touched: every write is scoped to this campaign's id.
     *
     * <p>DRAFT, SCHEDULED, PAUSED, COMPLETED, FAILED and CANCELLED campaigns can be
     * deleted while no batch job of the campaign is running and none of its emails is
     * still waiting in the send queue; otherwise 409 says what is still running.
     * QUEUED and RUNNING campaigns always get 409. Bounded rule: a QUEUED delivery row
     * older than the queue's 24-hour message TTL, or a job execution without progress
     * for 24 hours, no longer blocks deletion.
     *
     * <p>The campaign row is locked and the save increments {@code version}: a launch,
     * resume or scheduled launch that loaded the campaign before this commit fails its
     * version check (409), and one that loads it afterwards finds it deleted (404).
     */
    @Transactional
    @CacheEvict(value = "campaigns", allEntries = true)
    public void delete(Long id) {
        Campaign campaign = campaignRepository.findByIdForUpdate(id)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign", id));

        String blocker = deleteBlocker(campaign);
        if (blocker != null) {
            throw new ConflictException(blocker);
        }

        int addresses = targetAddressRepository.deleteByCampaignId(id);
        campaign.setDeletedAt(Instant.now());
        campaign.setDeletedBy(currentUserId());
        campaignRepository.save(campaign);
        log.info("Campaign {} deleted (status {}, {} campaign-only addresses removed)",
                id, campaign.getStatus(), addresses);
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

        // Resolve recipients. validateForLaunch guarantees at least one explicit
        // target, so a campaign never falls back to "every active contact".
        Set<Recipient> resolvedRecipients = new LinkedHashSet<>();

        if (hasDirectRecipients(campaign)) {
            resolvedRecipients.addAll(recipientRepository.findByIdsIn(campaign.getOrganizationId(), campaign.getRecipientIds()));
        }
        if (hasGroupRecipients(campaign)) {
            resolvedRecipients.addAll(recipientRepository.findByGroupIds(campaign.getOrganizationId(), campaign.getRecipientGroupIds()));
        }

        // Normalised addresses that already have a delivery row IN THIS CAMPAIGN (a
        // relaunch from PAUSED or FAILED) plus those claimed below: each address gets at
        // most one row, and so at most one email, per campaign. Other campaigns' rows are
        // never read: a contact emailed by another campaign is still eligible here.
        Set<String> claimed = new HashSet<>();
        for (String e : campaignRecipientRepository.findTargetedEmails(campaign.getId())) {
            claimed.add(EmailValidator.normalize(e));
        }

        // Filter valid & non-unsubscribed recipients
        List<CampaignRecipient> newCampaignRecipients = new ArrayList<>();
        for (Recipient r : resolvedRecipients) {
            if (r.getDeletedAt() != null || r.getStatus() != RecipientStatus.ACTIVE) {
                continue;
            }
            if (r.getEmail() == null || r.getEmail().isBlank() || !r.getEmail().contains("@")) {
                continue;
            }
            if (unsubscribeService.isUnsubscribed(campaign.getOrganizationId(), r.getEmail())) {
                continue;
            }
            if (!claimed.add(EmailValidator.normalize(r.getEmail()))) {
                continue;
            }

            if (!campaignRecipientRepository.existsByCampaignIdAndRecipientId(campaign.getId(), r.getId())) {
                newCampaignRecipients.add(contactRow(campaign, r));
            }
        }

        // Campaign-only addresses, after the saved contacts so a contact selected
        // for this campaign always wins over the same campaign-only address.
        newCampaignRecipients.addAll(campaignOnlyRows(campaign,
                targetAddressRepository.findByCampaignIdOrderByIdAsc(campaign.getId()), claimed));

        if (!newCampaignRecipients.isEmpty()) {
            campaignRecipientRepository.saveAll(newCampaignRecipients);
        }

        long totalCount = campaignRecipientRepository.countByCampaignId(campaign.getId());
        if (totalCount == 0) {
            throw new ValidationException(
                    "None of the selected recipients can be emailed: they are unsubscribed, "
                    + "inactive or deleted. Choose other recipients before launching.");
        }

        campaign.setTotalRecipients(totalCount);
        campaign.setStatus(CampaignStatus.QUEUED);
        campaign.setStartedAt(Instant.now());
        campaignRepository.save(campaign);

        launchJobAfterCommit(campaign.getId());

        log.info("Campaign {} queued for processing with {} recipients", id, totalCount);
        return toResponse(campaign);
    }

    /**
     * Pauses a RUNNING campaign by setting its status; the pipeline honours it:
     * the batch reader stops at its next page (rows not yet read stay PENDING), the
     * job listener keeps PAUSED when the job ends, and the email consumer doesn't send
     * messages already in RabbitMQ but puts their rows back to PENDING. At most the
     * messages being sent at that moment (one per consumer) still go out.
     */
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

    /**
     * Resumes a PAUSED campaign: QUEUED, then a new batch job after the commit. The job
     * reads only PENDING and FAILED rows, so recipients already sent (or still queued
     * in RabbitMQ) are not sent again; rows put back to PENDING during the pause are
     * published again.
     */
    @Transactional
    @CacheEvict(value = "campaigns", allEntries = true)
    public CampaignResponse resume(Long id) {
        Campaign campaign = getOrThrow(id);

        if (campaign.getStatus() != CampaignStatus.PAUSED) {
            throw new ConflictException("Campaign is not paused");
        }

        campaign.setStatus(CampaignStatus.QUEUED);
        campaignRepository.save(campaign);

        launchJobAfterCommit(campaign.getId());
        log.info("Campaign {} resumed", id);
        return toResponse(campaign);
    }

    /**
     * Cancels a campaign and stops its sending. After the commit, its running batch job
     * execution is stopped through Spring Batch ({@code JobOperator.stop}) and ends
     * STOPPED at its next chunk boundary. Until then the status already stops the
     * pipeline: the reader reads no further page, the processor and writer skip the
     * items already read, the job listener keeps CANCELLED, and the email consumer marks
     * messages still in RabbitMQ SKIPPED ("Campaign cancelled") instead of sending them.
     * At most the email being sent at that moment still goes out.
     */
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
        CampaignResponse response = toResponse(campaignRepository.save(campaign));
        stopJobsAfterCommit(campaign.getId());
        return response;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /** A campaign that exists and isn't deleted; 404 otherwise, so a deleted campaign is gone for every action. */
    public Campaign getOrThrow(Long id) {
        return campaignRepository.findOneById(id)
                .filter(c -> !c.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException("Campaign", id));
    }

    /**
     * Why the campaign can't be deleted now, or null when it can (see {@link #delete}).
     * Only this campaign's job executions and delivery rows are looked at.
     */
    private String deleteBlocker(Campaign campaign) {
        if (campaign.isSending()) {
            return DELETE_WHILE_SENDING;
        }

        List<JobExecution> running = jobLauncher.findRunningExecutions(campaign.getId());
        if (!running.isEmpty()) {
            JobExecution job = running.get(0);
            LocalDateTime started = job.getStartTime() != null ? job.getStartTime() : job.getCreateTime();
            return "This campaign can't be deleted yet: its send job is still running"
                    + (started != null ? " (started " + MESSAGE_TIME.format(started.atZone(ZoneId.systemDefault())) + ")" : "")
                    + ". Wait until it has stopped, then delete the campaign. A send job without progress for "
                    + "24 hours (for example after a server restart) no longer blocks deletion.";
        }

        Instant since = Instant.now().minus(QUEUED_ROW_STALE_AFTER);
        long queued = campaignRecipientRepository.countQueuedSince(campaign.getId(), since);
        if (queued > 0) {
            Instant latest = campaignRecipientRepository.findLatestQueuedAtSince(campaign.getId(), since)
                    .orElseGet(Instant::now);
            return String.format(Locale.US,
                    "This campaign can't be deleted yet: %,d of its emails %s still waiting in the send queue. "
                    + "Wait until they have been sent or skipped, then delete the campaign. Emails queued more "
                    + "than 24 hours ago no longer block deletion, so it can be deleted from %s at the latest.",
                    queued, queued == 1 ? "is" : "are",
                    MESSAGE_TIME.format(latest.plus(QUEUED_ROW_STALE_AFTER)));
        }
        return null;
    }

    /** ID of the signed-in user, or null when there is none (scheduler, tests). */
    private static Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof User user ? user.getId() : null;
    }

    /**
     * Launch requires email content (see {@link #hasEmailContent}), a valid subject, a
     * sender email and at least one explicitly selected recipient. Without content every
     * recipient would get an email with only a subject.
     */
    private void validateForLaunch(Campaign campaign) {
        if (!hasEmailContent(campaign)) {
            EmailTemplate template = campaign.getTemplate();
            if (template == null) {
                throw new ValidationException(NO_EMAIL_CONTENT);
            }
            throw new ValidationException(NO_EMAIL_CONTENT + (template.getDeletedAt() != null
                    ? ". The template assigned to this campaign has been deleted."
                    : ". The template assigned to this campaign has no body."));
        }
        if (campaign.getSubject() == null || campaign.getSubject().isBlank()) {
            throw new ValidationException("Campaign subject is required");
        }
        // Checked on save as well; this also covers a subject stored before that rule.
        String subjectProblem = EmailContentRules.subjectProblem(campaign.getSubject());
        if (subjectProblem != null) {
            throw new ValidationException("Campaign subject " + subjectProblem);
        }
        if (campaign.getSenderEmail() == null || campaign.getSenderEmail().isBlank()) {
            throw new ValidationException("Campaign sender email is required");
        }
        if (!hasDirectRecipients(campaign) && !hasGroupRecipients(campaign)
                && targetAddressRepository.countByCampaignId(campaign.getId()) == 0) {
            throw new ValidationException("Campaign has no recipients selected");
        }
    }

    /**
     * Whether the campaign has something to send: its own non-blank body, or a
     * non-deleted template with a non-blank HTML or plain-text body. The own body wins
     * when both exist (it is what the batch processor sends).
     */
    public static boolean hasEmailContent(Campaign campaign) {
        if (CampaignEmailContent.hasCustomBody(campaign.getCustomBody())) return true;
        EmailTemplate template = campaign.getTemplate();
        return template != null && template.getDeletedAt() == null
                && (!isBlank(template.getHtmlBody()) || !isBlank(template.getPlainBody()));
    }

    private static CampaignRecipient contactRow(Campaign campaign, Recipient r) {
        return CampaignRecipient.builder()
                .campaign(campaign)
                .recipient(r)
                .deliveryStatus(CampaignRecipient.DeliveryStatus.PENDING)
                .idempotencyKey(CampaignRecipient.buildIdempotencyKey(campaign.getId(), r.getId()))
                .build();
    }

    /**
     * Delivery rows for campaign-only addresses. A saved contact's data and status
     * always win for the same address:
     * <ul>
     *   <li>an address already {@code claimed} (a saved contact resolved for this
     *       launch, or an existing row on relaunch) is skipped;</li>
     *   <li>an address that now belongs to a live contact uses that contact, and is
     *       skipped unless the contact is ACTIVE and not unsubscribed;</li>
     *   <li>an address of a deleted contact, or an unsubscribed address, is skipped;</li>
     *   <li>any other address gets a row with no recipient and a snapshot of its values.</li>
     * </ul>
     * Addresses that get a row are added to {@code claimed}.
     */
    private List<CampaignRecipient> campaignOnlyRows(Campaign campaign,
                                                     List<CampaignTargetAddress> addresses,
                                                     Set<String> claimed) {
        if (addresses.isEmpty()) return List.of();

        List<String> emails = addresses.stream().map(CampaignTargetAddress::getEmail).toList();
        Map<String, Recipient> contacts = new HashMap<>();
        for (Recipient r : recipientRepository.findByEmailIn(campaign.getOrganizationId(), emails)) {
            contacts.put(EmailValidator.normalize(r.getEmail()), r);
        }
        Set<String> deletedContacts = new HashSet<>();
        for (String e : recipientRepository.findDeletedEmails(campaign.getOrganizationId(), emails)) {
            deletedContacts.add(EmailValidator.normalize(e));
        }

        List<CampaignRecipient> rows = new ArrayList<>();
        for (CampaignTargetAddress a : addresses) {
            String email = EmailValidator.normalize(a.getEmail());
            if (claimed.contains(email)) continue;

            Recipient contact = contacts.get(email);
            if (contact != null) {
                if (contact.getStatus() != RecipientStatus.ACTIVE || unsubscribeService.isUnsubscribed(campaign.getOrganizationId(), email)) {
                    continue;
                }
                claimed.add(email);
                if (!campaignRecipientRepository.existsByCampaignIdAndRecipientId(campaign.getId(), contact.getId())) {
                    rows.add(contactRow(campaign, contact));
                }
                continue;
            }
            if (deletedContacts.contains(email) || unsubscribeService.isUnsubscribed(campaign.getOrganizationId(), email)) {
                continue;
            }
            claimed.add(email);
            rows.add(CampaignRecipient.builder()
                    .campaign(campaign)
                    .deliveryStatus(CampaignRecipient.DeliveryStatus.PENDING)
                    .idempotencyKey(CampaignRecipient.buildAddressIdempotencyKey(campaign.getId(), email))
                    .snapshotEmail(email)
                    .snapshotName(a.getName())
                    .snapshotFirstName(a.getFirstName())
                    .snapshotLastName(a.getLastName())
                    .snapshotCompany(a.getCompany())
                    .snapshotCustomFields(a.getCustomFields() != null
                            ? new HashMap<>(a.getCustomFields()) : new HashMap<>())
                    .build());
        }
        return rows;
    }

    // ── Campaign-only addresses: validation and storage ─────────────────────────

    /**
     * Validates and normalises campaign-only addresses from a request: emails are
     * normalised ({@link EmailValidator#normalize}: trimmed, including invisible
     * characters, and lower-cased), repeats are dropped (the first one wins), other
     * values are trimmed and blank ones dropped, and custom fields that would shadow a
     * standard field are ignored. A {@code null} list stays {@code null}.
     *
     * @throws ValidationException naming the invalid addresses, or when the list has
     *         more than {@link #MAX_TARGET_ADDRESSES} entries
     */
    static List<TargetAddress> normalizeTargetAddresses(List<TargetAddress> input) {
        if (input == null) return null;
        if (input.size() > MAX_TARGET_ADDRESSES) {
            throw new ValidationException(String.format(Locale.US,
                    "A campaign can have at most %,d campaign-only addresses; this request has %,d.",
                    MAX_TARGET_ADDRESSES, input.size()));
        }

        Map<String, TargetAddress> byEmail = new LinkedHashMap<>();
        List<String> invalid = new ArrayList<>();
        for (TargetAddress a : input) {
            String raw = a != null ? a.email() : null;
            String email = raw != null ? EmailValidator.normalize(raw) : "";
            if (email.length() > 255 || !EmailValidator.isValid(email)) {
                String shown = EmailValidator.trim(raw);
                invalid.add(shown == null || shown.isEmpty() ? "(blank)" : abbreviate(shown));
                continue;
            }
            byEmail.putIfAbsent(email, new TargetAddress(email,
                    blankToNull(a.name()), blankToNull(a.firstName()), blankToNull(a.lastName()),
                    blankToNull(a.company()), cleanFields(a.fields())));
        }

        if (!invalid.isEmpty()) {
            int named = Math.min(invalid.size(), MAX_NAMED_INVALID);
            String list = String.join(", ", invalid.subList(0, named))
                    + (invalid.size() > named ? " and " + (invalid.size() - named) + " more" : "");
            throw new ValidationException(invalid.size() == 1
                    ? "Invalid campaign-only email address: " + list
                    : "Invalid campaign-only email addresses (" + invalid.size() + "): " + list);
        }
        return new ArrayList<>(byEmail.values());
    }

    /**
     * Makes the stored campaign-only addresses equal {@code wanted} (normalised, no
     * repeats). Unchanged addresses keep their row, changed ones are updated, new ones
     * inserted and missing ones deleted; an empty list deletes them all.
     */
    private void syncTargetAddresses(Long campaignId, List<TargetAddress> wanted) {
        Map<String, CampaignTargetAddress> stored = new HashMap<>();
        for (CampaignTargetAddress t : targetAddressRepository.findByCampaignIdOrderByIdAsc(campaignId)) {
            stored.put(t.getEmail(), t);
        }

        List<CampaignTargetAddress> toSave = new ArrayList<>();
        for (TargetAddress a : wanted) {
            CampaignTargetAddress t = stored.remove(a.email());
            if (t == null) {
                t = CampaignTargetAddress.builder().campaignId(campaignId).email(a.email()).build();
            } else if (Objects.equals(t.getName(), a.name())
                    && Objects.equals(t.getFirstName(), a.firstName())
                    && Objects.equals(t.getLastName(), a.lastName())
                    && Objects.equals(t.getCompany(), a.company())
                    && Objects.equals(t.getCustomFields() != null ? t.getCustomFields() : Map.of(), a.fields())) {
                continue;
            }
            t.setName(a.name());
            t.setFirstName(a.firstName());
            t.setLastName(a.lastName());
            t.setCompany(a.company());
            t.setCustomFields(new HashMap<>(a.fields()));
            toSave.add(t);
        }

        // Inserts never reuse a deleted address, so the UNIQUE(campaign_id, email)
        // constraint holds whatever order the statements run in.
        if (!stored.isEmpty()) targetAddressRepository.deleteAll(stored.values());
        if (!toSave.isEmpty()) targetAddressRepository.saveAll(toSave);
    }

    private Map<Long, Long> targetAddressCounts(List<Campaign> campaigns) {
        List<Long> ids = campaigns.stream().map(Campaign::getId).filter(Objects::nonNull).toList();
        if (ids.isEmpty()) return Map.of();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : targetAddressRepository.countByCampaignIds(ids)) {
            counts.put((Long) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private static Map<String, String> cleanFields(Map<String, String> fields) {
        Map<String, String> out = new LinkedHashMap<>();
        if (fields == null) return out;
        for (var e : fields.entrySet()) {
            String key = e.getKey() != null ? e.getKey().trim() : "";
            String value = e.getValue() != null ? e.getValue().trim() : "";
            if (key.isEmpty() || value.isEmpty() || STANDARD_FIELD_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }
            out.put(key, value);
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String abbreviate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 77) + "...";
    }

    private static TargetAddress toDto(CampaignTargetAddress t) {
        return new TargetAddress(t.getEmail(), t.getName(), t.getFirstName(), t.getLastName(),
                t.getCompany(), t.getCustomFields() != null ? new LinkedHashMap<>(t.getCustomFields()) : Map.of());
    }

    private static boolean hasDirectRecipients(Campaign c) {
        return c.getRecipientIds() != null && !c.getRecipientIds().isEmpty();
    }

    private static boolean hasGroupRecipients(Campaign c) {
        return c.getRecipientGroupIds() != null && !c.getRecipientGroupIds().isEmpty();
    }

    /**
     * Starts the (asynchronous) batch job only after the surrounding transaction
     * commits, so the job always sees the committed QUEUED status and
     * campaign_recipients rows. If the transaction rolls back (for example a
     * concurrent duplicate launch losing the optimistic-lock race), no job starts.
     * Without an active transaction (unit tests) the job is launched directly.
     */
    private void launchJobAfterCommit(Long campaignId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    jobLauncher.launchCampaignJob(campaignId);
                }
            });
        } else {
            jobLauncher.launchCampaignJob(campaignId);
        }
    }

    /**
     * Stops the campaign's running batch job (asynchronous) only after the cancel
     * commits; a rolled-back cancel stops nothing. Without an active transaction (unit
     * tests) it is called directly.
     */
    private void stopJobsAfterCommit(Long campaignId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    jobLauncher.stopRunningJobs(campaignId);
                }
            });
        } else {
            jobLauncher.stopRunningJobs(campaignId);
        }
    }

    /**
     * Converts the entity to a DTO with the campaign-only address count but not the
     * addresses themselves (used by lifecycle actions). No cache annotation.
     */
    public CampaignResponse toResponse(Campaign c) {
        long count = c.getId() != null ? targetAddressRepository.countByCampaignId(c.getId()) : 0;
        return toResponse(c, count, null);
    }

    /**
     * Single-campaign view (get, create, update): includes every campaign-only address
     * and the campaign's own body.
     */
    private CampaignResponse toDetailResponse(Campaign c) {
        List<TargetAddress> addresses = c.getId() == null ? List.of()
                : targetAddressRepository.findByCampaignIdOrderByIdAsc(c.getId()).stream()
                        .map(CampaignService::toDto)
                        .toList();
        return toResponse(c, addresses.size(), addresses);
    }

    private CampaignResponse toResponse(Campaign c, long targetAddressCount, List<TargetAddress> targetAddresses) {
        return new CampaignResponse(
                c.getId(), c.getName(), c.getDescription(), c.getSubject(),
                c.getTemplate() != null ? c.getTemplate().getId() : null,
                c.getTemplate() != null ? c.getTemplate().getName() : null,
                c.getSenderName(), c.getSenderEmail(), c.getReplyTo(),
                c.getStatus(), c.getScheduledAt(), c.getCreatedAt(), c.getUpdatedAt(),
                c.getStartedAt(), c.getCompletedAt(),
                c.getTotalRecipients(), c.getSentCount(), c.getFailedCount(),
                c.getSkippedCount(), c.getBouncedCount(), c.getRetryCount(),
                c.successRate(), c.getBatchJobId(),
                c.getRecipientGroupIds() != null ? new java.util.ArrayList<>(c.getRecipientGroupIds()) : java.util.Collections.emptyList(),
                c.getRecipientIds() != null ? new java.util.ArrayList<>(c.getRecipientIds()) : java.util.Collections.emptyList(),
                targetAddressCount,
                targetAddresses,
                // Only single-campaign responses (the ones with the address list) carry
                // the body; lists and lifecycle actions carry only hasContent.
                targetAddresses != null ? c.getCustomBody() : null,
                hasEmailContent(c)
        );
    }
}
