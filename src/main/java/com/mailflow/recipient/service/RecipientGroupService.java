package com.mailflow.recipient.service;

import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.recipient.dto.RecipientGroupRequest;
import com.mailflow.recipient.dto.RecipientGroupResponse;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientGroup;
import com.mailflow.recipient.repository.RecipientGroupRepository;
import com.mailflow.recipient.repository.RecipientRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecipientGroupService {

    private final RecipientGroupRepository groupRepository;
    private final RecipientRepository recipientRepository;

    @Transactional
    public RecipientGroupResponse create(RecipientGroupRequest request) {
        RecipientGroup group = RecipientGroup.builder()
                .name(request.name())
                .description(request.description())
                .recipients(new HashSet<>())
                .build();

        if (request.recipientIds() != null && !request.recipientIds().isEmpty()) {
            List<Recipient> recipients = recipientRepository.findAllById(request.recipientIds());
            group.getRecipients().addAll(recipients);
        }

        return toResponse(groupRepository.save(group));
    }

    @Transactional(readOnly = true)
    public List<RecipientGroupResponse> findAll() {
        return groupRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecipientGroupResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    public RecipientGroupResponse update(Long id, RecipientGroupRequest request) {
        RecipientGroup group = getOrThrow(id);
        group.setName(request.name());
        group.setDescription(request.description());

        if (request.recipientIds() != null) {
            group.getRecipients().clear();
            List<Recipient> recipients = recipientRepository.findAllById(request.recipientIds());
            group.getRecipients().addAll(recipients);
        }

        return toResponse(groupRepository.save(group));
    }

    @Transactional
    public void delete(Long id) {
        RecipientGroup group = getOrThrow(id);
        groupRepository.delete(group);
    }

    @Transactional
    public RecipientGroupResponse addMember(Long groupId, Long recipientId) {
        RecipientGroup group = getOrThrow(groupId);
        Recipient recipient = recipientRepository.findById(recipientId)
                .orElseThrow(() -> new ResourceNotFoundException("Recipient", recipientId));
        group.getRecipients().add(recipient);
        return toResponse(groupRepository.save(group));
    }

    @Transactional
    public RecipientGroupResponse removeMember(Long groupId, Long recipientId) {
        RecipientGroup group = getOrThrow(groupId);
        group.getRecipients().removeIf(r -> r.getId().equals(recipientId));
        return toResponse(groupRepository.save(group));
    }

    @Transactional(readOnly = true)
    public Page<Recipient> getMembers(Long groupId, Pageable pageable) {
        RecipientGroup group = getOrThrow(groupId);
        List<Recipient> list = new ArrayList<>(group.getRecipients());
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), list.size());
        if (start > list.size()) {
            return new PageImpl<>(List.of(), pageable, list.size());
        }
        return new PageImpl<>(list.subList(start, end), pageable, list.size());
    }

    public RecipientGroup getOrThrow(Long id) {
        return groupRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("RecipientGroup", id));
    }

    private RecipientGroupResponse toResponse(RecipientGroup g) {
        return new RecipientGroupResponse(
                g.getId(),
                g.getName(),
                g.getDescription(),
                g.getRecipients() != null ? g.getRecipients().size() : 0,
                g.getCreatedAt(),
                g.getUpdatedAt()
        );
    }
}
