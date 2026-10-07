package com.mailflow.template.service;

import com.mailflow.auth.entity.User;
import com.mailflow.common.dto.PageResponse;
import com.mailflow.common.exception.ResourceNotFoundException;
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
    @Cacheable(value = "templates", key = "#id")
    public TemplateResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    @CacheEvict(value = "templates", key = "#id")
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
    @CacheEvict(value = "templates", key = "#id")
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
