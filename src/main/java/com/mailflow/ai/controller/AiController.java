package com.mailflow.ai.controller;

import com.mailflow.ai.AiContentProvider;
import com.mailflow.ai.AiGeneratedContent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * AI Email Copilot — generates email content from a natural language prompt.
 *
 * <p>The user reviews and edits the output before using it in a campaign.
 */
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "AI Email Copilot", description = "AI-assisted email content generation")
public class AiController {

    private final AiContentProvider aiContentProvider;

    @PostMapping("/generate")
    @PreAuthorize("hasAnyRole('ADMIN','CAMPAIGN_MANAGER')")
    @Operation(summary = "Generate email content from a prompt",
               description = "Returns suggested subject, HTML body, and plain text. " +
                             "User must review before using in a campaign.")
    public ResponseEntity<AiGeneratedContent> generate(
            @RequestBody @NotBlank String prompt) {
        return ResponseEntity.ok(aiContentProvider.generate(prompt));
    }

    @GetMapping("/status")
    @Operation(summary = "Check if AI copilot is configured and available")
    public ResponseEntity<java.util.Map<String, Object>> status() {
        return ResponseEntity.ok(java.util.Map.of(
            "available", aiContentProvider.isAvailable(),
            "message", aiContentProvider.isAvailable()
                ? "AI Email Copilot is ready"
                : "AI not configured. Set AI_ENABLED=true and OPENAI_API_KEY."
        ));
    }
}
