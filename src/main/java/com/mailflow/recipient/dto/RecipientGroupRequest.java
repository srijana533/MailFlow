package com.mailflow.recipient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RecipientGroupRequest(
    @NotBlank @Size(max = 255) String name,
    String description,
    List<Long> recipientIds
) {}
