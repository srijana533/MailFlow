package com.mailflow.recipient.controller;

import com.mailflow.common.dto.PageResponse;
import com.mailflow.recipient.dto.RecipientGroupRequest;
import com.mailflow.recipient.dto.RecipientGroupResponse;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.service.RecipientGroupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Recipient Groups", description = "Manage recipient groups and memberships")
public class RecipientGroupController {

    private final RecipientGroupService groupService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Create a new recipient group")
    public ResponseEntity<RecipientGroupResponse> create(@Valid @RequestBody RecipientGroupRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(groupService.create(request));
    }

    @GetMapping
    @Operation(summary = "List all recipient groups")
    public ResponseEntity<List<RecipientGroupResponse>> list() {
        return ResponseEntity.ok(groupService.findAll());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get group details by ID")
    public ResponseEntity<RecipientGroupResponse> getById(@PathVariable Long id) {
        return ResponseEntity.ok(groupService.findById(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Update a recipient group")
    public ResponseEntity<RecipientGroupResponse> update(
            @PathVariable Long id, @Valid @RequestBody RecipientGroupRequest request) {
        return ResponseEntity.ok(groupService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Delete a recipient group")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        groupService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/members/{recipientId}")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Add a recipient to group")
    public ResponseEntity<RecipientGroupResponse> addMember(
            @PathVariable Long id, @PathVariable Long recipientId) {
        return ResponseEntity.ok(groupService.addMember(id, recipientId));
    }

    @DeleteMapping("/{id}/members/{recipientId}")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Remove a recipient from group")
    public ResponseEntity<RecipientGroupResponse> removeMember(
            @PathVariable Long id, @PathVariable Long recipientId) {
        return ResponseEntity.ok(groupService.removeMember(id, recipientId));
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "Get members of a group")
    public ResponseEntity<PageResponse<Recipient>> getMembers(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(PageResponse.from(groupService.getMembers(id, PageRequest.of(page, size))));
    }
}
