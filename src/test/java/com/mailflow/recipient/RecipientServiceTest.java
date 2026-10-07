package com.mailflow.recipient;

import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.recipient.dto.RecipientRequest;
import com.mailflow.recipient.dto.RecipientResponse;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.repository.RecipientRepository;
import com.mailflow.recipient.service.RecipientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecipientServiceTest {

    @Mock private RecipientRepository recipientRepository;
    @InjectMocks private RecipientService recipientService;

    private Recipient sampleRecipient;

    @BeforeEach
    void setUp() {
        sampleRecipient = Recipient.builder()
                .id(1L)
                .email("test@example.com")
                .name("Test User")
                .course("Computer Science")
                .status(RecipientStatus.ACTIVE)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .customFields(Map.of("code", "ABC123"))
                .build();
    }

    @Test
    void creates_recipient_successfully() {
        when(recipientRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull("test@example.com")).thenReturn(false);
        when(recipientRepository.save(any(Recipient.class))).thenReturn(sampleRecipient);

        RecipientRequest request = new RecipientRequest(
            "test@example.com", "Test User", null, null, null, "Computer Science", null);

        RecipientResponse response = recipientService.create(request);

        assertThat(response.email()).isEqualTo("test@example.com");
        assertThat(response.name()).isEqualTo("Test User");
        verify(recipientRepository).save(any(Recipient.class));
    }

    @Test
    void throws_conflict_when_email_already_exists() {
        when(recipientRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull("test@example.com")).thenReturn(true);

        RecipientRequest request = new RecipientRequest(
            "test@example.com", "Test User", null, null, null, null, null);

        assertThatThrownBy(() -> recipientService.create(request))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void throws_validation_error_for_invalid_email() {
        RecipientRequest request = new RecipientRequest(
            "not-an-email", "Test User", null, null, null, null, null);

        assertThatThrownBy(() -> recipientService.create(request))
            .isInstanceOf(ValidationException.class);
    }

    @Test
    void finds_recipient_by_id() {
        when(recipientRepository.findById(1L)).thenReturn(Optional.of(sampleRecipient));
        RecipientResponse response = recipientService.findById(1L);
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("test@example.com");
    }

    @Test
    void soft_deletes_recipient() {
        when(recipientRepository.findById(1L)).thenReturn(Optional.of(sampleRecipient));
        when(recipientRepository.save(any(Recipient.class))).thenReturn(sampleRecipient);

        recipientService.delete(1L);

        verify(recipientRepository).save(argThat(r -> r.getDeletedAt() != null));
    }
}
