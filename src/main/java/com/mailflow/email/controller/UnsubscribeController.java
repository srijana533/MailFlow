package com.mailflow.email.controller;

import com.mailflow.email.UnsubscribeService;
import com.mailflow.email.entity.UnsubscribeEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Public unsubscribe endpoints.
 * No authentication required — accessed via links in emails.
 */
@RestController
@RequestMapping("/api/unsubscribe")
@RequiredArgsConstructor
@Tag(name = "Unsubscribe", description = "Email opt-out management")
public class UnsubscribeController {

    private final UnsubscribeService unsubscribeService;

    @GetMapping("/{token}")
    @Operation(summary = "Unsubscribe via token link in email")
    public ResponseEntity<String> unsubscribeByToken(@PathVariable String token) {
        UnsubscribeEntry entry = unsubscribeService.findByToken(token);
        // Idempotent — already unsubscribed, return confirmation
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(buildConfirmationHtml(entry.getEmail()));
    }

    @PostMapping
    @Operation(summary = "Unsubscribe an email address directly")
    public ResponseEntity<Void> unsubscribe(
            @RequestParam String email,
            @RequestParam(required = false) String reason) {
        unsubscribeService.unsubscribe(email, reason, null);
        return ResponseEntity.ok().build();
    }

    private String buildConfirmationHtml(String email) {
        return """
               <!DOCTYPE html><html><body style="font-family:Arial,sans-serif;max-width:600px;margin:40px auto">
               <h2>Unsubscribed successfully</h2>
               <p>The address <strong>%s</strong> has been removed from our mailing list.</p>
               <p>You will no longer receive emails from this platform.</p>
               </body></html>
               """.formatted(email);
    }
}
