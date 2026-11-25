package com.mailflow.recipient;

import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.email.repository.UnsubscribeRepository;
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

    @org.junit.jupiter.api.BeforeEach
    void signInTestOrganization() {
        com.mailflow.tenant.TestTenant.signIn();
    }

    @org.junit.jupiter.api.AfterEach
    void signOutTestOrganization() {
        com.mailflow.tenant.TestTenant.signOut();
    }

    @Mock private RecipientRepository recipientRepository;
    @Mock private UnsubscribeRepository unsubscribeRepository;
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
        when(recipientRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(sampleRecipient));
        RecipientResponse response = recipientService.findById(1L);
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.email()).isEqualTo("test@example.com");
    }

    @Test
    void soft_deletes_recipient() {
        when(recipientRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(sampleRecipient));
        when(recipientRepository.save(any(Recipient.class))).thenReturn(sampleRecipient);

        recipientService.delete(1L);

        verify(recipientRepository).save(argThat(r -> r.getDeletedAt() != null));
    }

    @Test
    void lookup_finds_saved_contacts_by_id_and_email_without_writing() {
        Recipient byEmail = Recipient.builder()
                .id(2L).email("grace@example.com").status(RecipientStatus.UNSUBSCRIBED).build();
        when(recipientRepository.findByIdInAndDeletedAtIsNull(java.util.Set.of(1L, 99L)))
                .thenReturn(java.util.List.of(sampleRecipient));
        // Emails are normalised (trim + lower-case); invalid ones are ignored.
        when(recipientRepository.findByEmailIn(com.mailflow.tenant.TestTenant.ORG, java.util.List.of("grace@example.com", "old@example.com")))
                .thenReturn(java.util.List.of(byEmail));
        when(recipientRepository.findDeletedEmails(com.mailflow.tenant.TestTenant.ORG, java.util.List.of("grace@example.com", "old@example.com")))
                .thenReturn(java.util.List.of("old@example.com"));
        when(unsubscribeRepository.findEmailsIn(com.mailflow.tenant.TestTenant.ORG, java.util.List.of("grace@example.com", "old@example.com")))
                .thenReturn(java.util.List.of("grace@example.com"));

        var response = recipientService.lookup(new com.mailflow.recipient.dto.RecipientLookupRequest(
                java.util.List.of(1L, 99L),
                // Normalised with the shared rule, invisible characters included.
                java.util.List.of(" Grace@Example.com\u00A0", "\u200Bold@example.com\uFEFF", "not-an-email")));

        assertThat(response.recipients()).extracting(RecipientResponse::id).containsExactly(1L, 2L);
        assertThat(response.recipients().get(1).status()).isEqualTo(RecipientStatus.UNSUBSCRIBED);
        assertThat(response.deletedEmails()).containsExactly("old@example.com");
        assertThat(response.unsubscribedEmails()).containsExactly("grace@example.com");
        verify(recipientRepository, never()).save(any());
        verify(recipientRepository, never()).saveAll(any());
        verify(unsubscribeRepository, never()).save(any());
    }

    @Test
    void create_normalises_the_email_before_checking_and_storing_it() {
        when(recipientRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull("ada@test.local")).thenReturn(false);
        when(recipientRepository.save(any(Recipient.class))).thenAnswer(inv -> inv.getArgument(0));

        RecipientResponse response = recipientService.create(new RecipientRequest(
                "\uFEFF Ada@Test.Local\u00A0", "Ada", null, null, null, null, null));

        assertThat(response.email()).isEqualTo("ada@test.local");
        verify(recipientRepository).existsByEmailIgnoreCaseAndDeletedAtIsNull("ada@test.local");
        verify(recipientRepository).save(argThat(r -> r.getEmail().equals("ada@test.local")));
    }

    @Test
    void the_request_normalises_the_email_before_bean_validation() {
        jakarta.validation.Validator validator =
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        RecipientRequest padded = new RecipientRequest(" ada@test.local\u200B ", null, null, null, null, null, null);

        assertThat(padded.email()).isEqualTo("ada@test.local");
        assertThat(validator.validate(padded)).isEmpty();
        assertThat(new RecipientRequest(null, null, null, null, null, null, null).email()).isNull();
    }

    @Test
    void update_compares_and_stores_the_normalised_email() {
        when(recipientRepository.findByIdAndDeletedAtIsNull(1L)).thenReturn(Optional.of(sampleRecipient));
        when(recipientRepository.save(any(Recipient.class))).thenAnswer(inv -> inv.getArgument(0));

        // Same address, other spelling: no "already in use" check against itself.
        recipientService.update(1L, new RecipientRequest(
                "\u2060TEST@example.com ", "Test User", null, null, null, null, null));
        assertThat(sampleRecipient.getEmail()).isEqualTo("test@example.com");
        verify(recipientRepository, never()).existsByEmailIgnoreCaseAndDeletedAtIsNull(any());

        // A different address is checked and stored normalised.
        when(recipientRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull("new@example.com")).thenReturn(false);
        recipientService.update(1L, new RecipientRequest(
                " New@Example.com\u00A0", "Test User", null, null, null, null, null));
        assertThat(sampleRecipient.getEmail()).isEqualTo("new@example.com");
        verify(recipientRepository).existsByEmailIgnoreCaseAndDeletedAtIsNull("new@example.com");
    }
}
