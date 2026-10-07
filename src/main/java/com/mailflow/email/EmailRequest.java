package com.mailflow.email;

import lombok.Builder;

import java.util.List;

/**
 * Provider-agnostic email send request.
 */
@Builder
public record EmailRequest(
    String to,
    String from,
    String fromName,
    String replyTo,
    String subject,
    String htmlBody,
    String plainBody,
    List<String> cc,
    List<String> bcc,
    List<AttachmentRef> attachments
) {
    public record AttachmentRef(String filename, String path, String contentType) {}
}
