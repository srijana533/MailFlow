package com.mailflow.template.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record TemplateRequest(
    @NotBlank @Size(max = 255) String name,
    String description,
    @NotBlank String subject,
    String htmlBody,
    String plainBody,
    List<String> variables
) {}
