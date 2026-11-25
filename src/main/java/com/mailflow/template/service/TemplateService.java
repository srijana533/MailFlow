package com.mailflow.template.service;

import com.mailflow.auth.entity.User;
import com.mailflow.common.dto.PageResponse;
import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.template.dto.TemplateContentRenderRequest;
import com.mailflow.template.dto.TemplateRenderRequest;
import com.mailflow.template.dto.TemplateRenderResponse;
import com.mailflow.template.dto.TemplateRequest;
import com.mailflow.template.dto.TemplateResponse;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.repository.EmailTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final EmailTemplateRepository templateRepository;
    private final TemplateEngineService templateEngine;

    // ── CRUD ─────────────────────────────────────────────────────────────────

    @Transactional
    @CacheEvict(value = "templates", allEntries = true)
    public TemplateResponse create(TemplateRequest request) {
        EmailTemplate template = EmailTemplate.builder()
                .name(request.name())
                .description(request.description())
                .subject(request.subject())
                .htmlBody(request.htmlBody())
                .plainBody(request.plainBody())
                .variables(extractAllVariables(request))
                .build();

        return toResponse(templateRepository.save(template));
    }

    @Transactional(readOnly = true)
    public PageResponse<TemplateResponse> findAll(String search, Pageable pageable) {
        return PageResponse.from(
            templateRepository.findAllActive(search, pageable).map(this::toResponse));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "templates", key = "T(com.mailflow.tenant.TenantContext).key(#id)")
    public TemplateResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    @CacheEvict(value = "templates", key = "T(com.mailflow.tenant.TenantContext).key(#id)")
    public TemplateResponse update(Long id, TemplateRequest request) {
        EmailTemplate template = getOrThrow(id);
        template.setName(request.name());
        template.setDescription(request.description());
        template.setSubject(request.subject());
        template.setHtmlBody(request.htmlBody());
        template.setPlainBody(request.plainBody());
        template.setVariables(extractAllVariables(request));
        return toResponse(templateRepository.save(template));
    }

    @Transactional
    @CacheEvict(value = "templates", key = "T(com.mailflow.tenant.TenantContext).key(#id)")
    public void delete(Long id) {
        EmailTemplate template = getOrThrow(id);
        template.setDeletedAt(Instant.now());
        templateRepository.save(template);
    }

    // ── Preview ───────────────────────────────────────────────────────────────

    public Map<String, String> preview(Long id, Map<String, String> vars) {
        EmailTemplate template = getOrThrow(id);
        Map<String, Object> model = new HashMap<>(vars);

        String renderedSubject = templateEngine.render(template.getSubject(), model);
        String renderedHtml    = templateEngine.render(template.getHtmlBody(), model);
        String renderedPlain   = templateEngine.render(template.getPlainBody(), model);

        return Map.of(
            "subject", renderedSubject,
            "htmlBody", renderedHtml != null ? renderedHtml : "",
            "plainBody", renderedPlain != null ? renderedPlain : ""
        );
    }

    /**
     * Renders the template for one recipient using the same personalisation
     * model as a real campaign send ({@link PersonalizationModel}).
     *
     * <p>The recipient is a transient object built from the request and is never
     * persisted. If {@code request.subject()} is blank the template subject is used.
     */
    @Transactional(readOnly = true)
    public TemplateRenderResponse renderForRecipient(Long id, TemplateRenderRequest request) {
        EmailTemplate template = getOrThrow(id);
        String subjectSource = (request.subject() != null && !request.subject().isBlank())
                ? request.subject() : template.getSubject();
        return renderForSample(request.recipient(), subjectSource,
                null, template.getHtmlBody(), template.getPlainBody());
    }

    /**
     * Renders content that has not been saved for one sample recipient: a template
     * being created or edited (the Templates page), or a campaign's own body (the
     * campaign form; {@code customBody}). It uses the same {@link PersonalizationModel},
     * template engine and {@link CampaignEmailContent} as a real send, and it neither
     * reads nor writes the database.
     */
    public TemplateRenderResponse renderContent(TemplateContentRenderRequest request) {
        return renderForSample(request.recipient().asRenderSample(),
                request.subject(), request.customBody(), request.htmlBody(), request.plainBody());
    }

    /**
     * Renders a subject and bodies for a transient sample recipient, which is never
     * persisted, and reports the placeholders that resolve to an empty value. A
     * non-blank {@code customBody} is rendered instead of the HTML and plain bodies,
     * exactly as {@code CampaignItemProcessor} sends it.
     */
    private TemplateRenderResponse renderForSample(TemplateRenderRequest.Sample s, String subjectSource,
                                                   String customBody, String htmlSource, String plainSource) {
        Recipient sample = Recipient.builder()
                .email(s.email())
                .name(s.name())
                .firstName(s.firstName())
                .lastName(s.lastName())
                .company(s.company())
                .course(s.course())
                .customFields(s.fields() != null ? new HashMap<>(s.fields()) : new HashMap<>())
                .build();
        Map<String, Object> model = PersonalizationModel.forRecipient(sample);

        CampaignEmailContent.Rendered email = CampaignEmailContent.render(
                templateEngine, model, subjectSource, customBody, htmlSource, plainSource);

        java.util.Set<String> used = new java.util.LinkedHashSet<>();
        used.addAll(templateEngine.extractVariables(subjectSource));
        if (CampaignEmailContent.hasCustomBody(customBody)) {
            used.addAll(templateEngine.extractVariables(customBody));
        } else {
            used.addAll(templateEngine.extractVariables(htmlSource));
            used.addAll(templateEngine.extractVariables(plainSource));
        }
        List<String> empty = used.stream()
                .filter(v -> {
                    Object value = model.get(v);
                    return value == null || value.toString().isBlank();
                })
                .toList();

        return new TemplateRenderResponse(email.subject(), email.htmlBody(), email.plainBody(), empty);
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    public EmailTemplate getOrThrow(Long id) {
        return templateRepository.findByIdAndDeletedAtIsNull(id)
                .orElseThrow(() -> new ResourceNotFoundException("Template", id));
    }

    private List<String> extractAllVariables(TemplateRequest request) {
        // Merge variables from subject + html + plain body
        java.util.Set<String> vars = new java.util.LinkedHashSet<>();
        if (request.variables() != null) vars.addAll(request.variables());
        vars.addAll(templateEngine.extractVariables(request.subject()));
        if (request.htmlBody()  != null) vars.addAll(templateEngine.extractVariables(request.htmlBody()));
        if (request.plainBody() != null) vars.addAll(templateEngine.extractVariables(request.plainBody()));
        return List.copyOf(vars);
    }

    public TemplateResponse toResponse(EmailTemplate t) {
        return new TemplateResponse(
            t.getId(), t.getName(), t.getDescription(),
            t.getSubject(), t.getHtmlBody(), t.getPlainBody(),
            t.getVariables(), t.getCreatedAt(), t.getUpdatedAt()
        );
    }
}
