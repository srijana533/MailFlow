package com.mailflow.template.dto;

import java.util.Map;

public record TemplatePreviewRequest(
    Map<String, String> variables
) {}
