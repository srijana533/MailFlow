package com.mailflow.attachment.service;

import com.mailflow.attachment.dto.AttachmentResponse;
import com.mailflow.attachment.entity.Attachment;
import com.mailflow.attachment.repository.AttachmentRepository;
import com.mailflow.common.exception.ResourceNotFoundException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.config.MailFlowProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final MailFlowProperties properties;

    @Transactional
    public AttachmentResponse store(MultipartFile file) {
        if (file.isEmpty()) {
            throw new ValidationException("Cannot store empty file");
        }

        long maxBytes = (long) properties.getStorage().getMaxAttachmentSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw new ValidationException(
                "File size exceeds maximum allowed size of " + properties.getStorage().getMaxAttachmentSizeMb() + "MB");
        }

        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "file";
        String extension = "";
        int dot = originalFilename.lastIndexOf('.');
        if (dot >= 0) {
            extension = originalFilename.substring(dot);
        }

        String storedName = UUID.randomUUID() + extension;
        Path uploadDir = Paths.get(properties.getStorage().getUploadDir()).toAbsolutePath().normalize();

        try {
            Files.createDirectories(uploadDir);
            Path destination = uploadDir.resolve(storedName);
            Files.copy(file.getInputStream(), destination, StandardCopyOption.REPLACE_EXISTING);

            Attachment attachment = Attachment.builder()
                    .originalName(originalFilename)
                    .storedName(storedName)
                    .contentType(file.getContentType() != null ? file.getContentType() : "application/octet-stream")
                    .sizeBytes(file.getSize())
                    .storagePath(destination.toString())
                    .build();

            return toResponse(attachmentRepository.save(attachment));
        } catch (IOException e) {
            log.error("Failed to store file {}", originalFilename, e);
            throw new RuntimeException("Could not store file: " + e.getMessage(), e);
        }
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> findAll() {
        return attachmentRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public AttachmentResponse findById(Long id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    public void delete(Long id) {
        Attachment attachment = getOrThrow(id);
        try {
            Path file = Paths.get(attachment.getStoragePath());
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Could not delete physical file for attachment id={}: {}", id, e.getMessage());
        }
        attachmentRepository.delete(attachment);
    }

    public Attachment getOrThrow(Long id) {
        return attachmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Attachment", id));
    }

    public AttachmentResponse toResponse(Attachment a) {
        return new AttachmentResponse(
                a.getId(),
                a.getOriginalName(),
                a.getContentType(),
                a.getSizeBytes(),
                a.getUploadedAt()
        );
    }
}
