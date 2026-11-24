package com.mailflow.campaign;

import com.mailflow.analytics.EmailLogService;
import com.mailflow.batch.CampaignItemWriter;
import com.mailflow.batch.CampaignJobLauncher;
import com.mailflow.campaign.dto.CampaignRequest;
import com.mailflow.campaign.dto.TargetAddress;
import com.mailflow.campaign.entity.Campaign;
import com.mailflow.campaign.entity.CampaignRecipient;
import com.mailflow.campaign.entity.CampaignStatus;
import com.mailflow.campaign.entity.CampaignTargetAddress;
import com.mailflow.campaign.repository.CampaignRecipientRepository;
import com.mailflow.campaign.repository.CampaignRepository;
import com.mailflow.campaign.repository.CampaignTargetAddressRepository;
import com.mailflow.campaign.service.CampaignService;
import com.mailflow.common.exception.ConflictException;
import com.mailflow.common.exception.MailFlowException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.email.EmailProvider;
import com.mailflow.email.RateLimiterService;
import com.mailflow.email.UnsubscribeService;
import com.mailflow.email.repository.UnsubscribeRepository;
import com.mailflow.queue.EmailConsumer;
import com.mailflow.queue.EmailMessage;
import com.mailflow.recipient.dto.BulkRecipientRequest;
import com.mailflow.recipient.dto.BulkRecipientResponse;
import com.mailflow.recipient.dto.ExcelPreviewResponse;
import com.mailflow.recipient.dto.ExcelRowPreview;
import com.mailflow.recipient.dto.RecipientLookupRequest;
import com.mailflow.recipient.dto.RecipientLookupResponse;
import com.mailflow.recipient.dto.RecipientRequest;
import com.mailflow.recipient.dto.RecipientResponse;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.repository.RecipientRepository;
import com.mailflow.recipient.service.RecipientImportService;
import com.mailflow.recipient.service.RecipientService;
import com.mailflow.template.entity.EmailTemplate;
import com.mailflow.template.service.TemplateService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.batch.item.Chunk;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Feature 1, "reuse saved contacts across campaigns": one test per acceptance item.
 *
 * <p>The user's own numbering of the "Recipient reuse" list was not available to this
 * step; the items are the acceptance checks of FEATURES_REUSE_DELETE_BRIEF.md, numbered
 * so that item 7 (two campaigns keep independent histories) and item 10 (retries and
 * repeated launches don't send twice) match the numbers the workflow gave.
 *
 * <p>Repositories are mocked. Items 7 and 10 also run the delivery-row unique constraints
 * on an in-memory H2 table with the same constraints as migrations V1 and V5, and check
 * that those constraints are in the migration files. PostgreSQL itself is not used.
 */
@ExtendWith(MockitoExtension.class)
class RecipientReuseTest {

    private static final long CAMPAIGN_A = 31L;
    private static final long CAMPAIGN_B = 32L;

    @Mock private CampaignRepository campaignRepository;
    @Mock private TemplateService templateService;
    @Mock private CampaignJobLauncher jobLauncher;
    @Mock private RecipientRepository recipientRepository;
    @Mock private CampaignRecipientRepository campaignRecipientRepository;
    @Mock private UnsubscribeService unsubscribeService;
    @Mock private CampaignTargetAddressRepository targetAddressRepository;
    @Mock private UnsubscribeRepository unsubscribeRepository;

    private CampaignService campaignService;
    private RecipientImportService importService;
    private RecipientService recipientService;
    private EmailTemplate template;

    @BeforeEach
    void setUp() {
        campaignService = new CampaignService(campaignRepository, templateService, jobLauncher,
                recipientRepository, campaignRecipientRepository, unsubscribeService, targetAddressRepository);
        // The upload preview, /bulk and /lookup are built from the contact and unsubscribe
        // repositories only: no campaign history (campaign_recipients, email_logs) is reachable.
        importService = new RecipientImportService(recipientRepository, unsubscribeRepository);
        recipientService = new RecipientService(recipientRepository, unsubscribeRepository);

        template = new EmailTemplate();
        template.setId(1L);
        template.setName("Welcome");
        template.setSubject("Hello");
        template.setPlainBody("Hello {{FirstName}}");
    }

    // ── Item 1 ──────────────────────────────────────────────────────────────────

    @Test
    void item01_a_contact_emailed_by_an_earlier_campaign_is_eligible_again_and_gets_its_own_row() {
        Recipient ada = contact(10L, "ada@test.local", RecipientStatus.ACTIVE);
        // Campaign A already emailed ada. These stubs stand for A's history; nothing may read them.
        lenient().when(campaignRecipientRepository.findTargetedEmails(CAMPAIGN_A)).thenReturn(List.of("ada@test.local"));
        lenient().when(campaignRecipientRepository.existsByCampaignIdAndRecipientId(CAMPAIGN_A, 10L)).thenReturn(true);

        // Upload for campaign B: ada is Valid (a saved contact), not Duplicate.
        when(recipientRepository.findByEmailIn(List.of("ada@test.local"))).thenReturn(List.of(ada));
        ExcelPreviewResponse preview = importService.previewExcel(xlsx(new String[] {"Email"}, new String[][] {{"ada@test.local"}}));
        assertThat(preview.validRows()).isEqualTo(1);
        assertThat(preview.existingRows()).isEqualTo(1);
        assertThat(preview.duplicateRows()).isZero();
        assertThat(preview.rows().get(0).status()).isEqualTo(ExcelRowPreview.EXISTING);
        assertThat(preview.rows().get(0).recipientId()).isEqualTo(10L);

        // Lookup (used by typed addresses and the Review step) finds her too.
        RecipientLookupResponse lookup = recipientService.lookup(new RecipientLookupRequest(null, List.of("ada@test.local")));
        assertThat(lookup.recipients()).extracting(RecipientResponse::id).containsExactly(10L);

        // Launching B gives ada a row of B's own; only B's rows are consulted.
        Campaign b = draft(CAMPAIGN_B);
        b.setRecipientIds(new HashSet<>(Set.of(10L)));
        when(campaignRepository.findById(CAMPAIGN_B)).thenReturn(Optional.of(b));
        when(recipientRepository.findByIdsIn(Set.of(10L))).thenReturn(List.of(ada));
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_B)).thenReturn(1L);

        campaignService.launch(CAMPAIGN_B);

        assertThat(savedRows(1).get(0)).singleElement().satisfies(row -> {
            assertThat(row.getRecipient()).isSameAs(ada);
            assertThat(row.getCampaign()).isSameAs(b);
            assertThat(row.getIdempotencyKey()).isEqualTo("campaign_32_recipient_10");
        });
        verify(campaignRecipientRepository, never()).findTargetedEmails(CAMPAIGN_A);
        verify(campaignRecipientRepository, never()).existsByCampaignIdAndRecipientId(eq(CAMPAIGN_A), any());
        assertThat(b.getStatus()).isEqualTo(CampaignStatus.QUEUED);
    }

    // ── Item 2 ──────────────────────────────────────────────────────────────────

    @Test
    void item02_a_file_of_three_saved_contacts_shows_valid_3_and_duplicate_0() {
        // Same columns as the user's mailflow11.xlsx (3 rows, all saved contacts); test.local data.
        String[] headers = {"Email", "Name", "FirstName", "LastName", "Company", "Role", "Phone", "Location", "Interest"};
        String[][] rows = {
                {"reuse.one@test.local", "Reuse One", "Reuse", "One", "Test Co", "Engineer", "+1-555-0101", "Pune", "Email"},
                {"reuse.two@test.local", "Reuse Two", "Reuse", "Two", "Test Co", "Analyst", "+1-555-0102", "Delhi", "Data"},
                {"reuse.three@test.local", "Reuse Three", "Reuse", "Three", "Test Co", "Manager", "+1-555-0103", "Goa", "Ops"},
        };
        when(recipientRepository.findByEmailIn(List.of("reuse.one@test.local", "reuse.two@test.local", "reuse.three@test.local")))
                .thenReturn(List.of(
                        contact(1L, "reuse.one@test.local", RecipientStatus.ACTIVE),
                        contact(2L, "reuse.two@test.local", RecipientStatus.ACTIVE),
                        contact(3L, "reuse.three@test.local", RecipientStatus.ACTIVE)));

        ExcelPreviewResponse res = importService.previewExcel(xlsx(headers, rows));

        assertThat(res.totalRows()).isEqualTo(3);
        assertThat(res.validRows()).isEqualTo(3);
        assertThat(res.existingRows()).isEqualTo(3);
        assertThat(res.newRows()).isZero();
        assertThat(res.duplicateRows()).isZero();
        assertThat(res.invalidRows()).isZero();
        assertThat(res.skippedRows()).isZero();
        // The eligible rows are in the preview, with their contact IDs and mapped columns.
        assertThat(res.rows()).extracting(ExcelRowPreview::status).containsOnly(ExcelRowPreview.EXISTING);
        assertThat(res.rows()).extracting(ExcelRowPreview::recipientId).containsExactly(1L, 2L, 3L);
        assertThat(res.rows()).extracting(ExcelRowPreview::reason)
                .noneMatch(r -> r.toLowerCase().contains("duplicate") || r.toLowerCase().contains("exists"));
        assertThat(res.rows().get(0).fields()).containsEntry("firstName", "Reuse").containsEntry("role", "Engineer");
        verifyNoWrites();
    }

    // ── Item 3 ──────────────────────────────────────────────────────────────────

    @Test
    void item03_duplicate_means_only_a_repeat_inside_the_same_file_and_names_the_first_row() {
        when(recipientRepository.findByEmailIn(List.of("saved@test.local", "new@test.local", "other@test.local")))
                .thenReturn(List.of(
                        contact(1L, "saved@test.local", RecipientStatus.ACTIVE),
                        contact(2L, "other@test.local", RecipientStatus.ACTIVE)));

        ExcelPreviewResponse res = importService.previewExcel(xlsx(new String[] {"Email"}, new String[][] {
                {"saved@test.local"},      // row 2
                {" SAVED@Test.Local "},    // row 3: same address, other spelling
                {"new@test.local"},        // row 4
                {"new@test.local"},        // row 5
                {"other@test.local"},      // row 6: a saved contact, not a repeat
        }));

        assertThat(res.rows()).extracting(ExcelRowPreview::status).containsExactly(
                ExcelRowPreview.EXISTING, ExcelRowPreview.DUPLICATE, ExcelRowPreview.VALID,
                ExcelRowPreview.DUPLICATE, ExcelRowPreview.EXISTING);
        assertThat(res.rows().get(1).reason()).isEqualTo("Duplicate email within file (first on row 2)");
        assertThat(res.rows().get(3).reason()).isEqualTo("Duplicate email within file (first on row 4)");
        assertThat(res.duplicateRows()).isEqualTo(2);
        assertThat(res.validRows()).isEqualTo(3);
        assertThat(res.newRows()).isEqualTo(1);
        assertThat(res.existingRows()).isEqualTo(2);
    }

    // ── Item 4 ──────────────────────────────────────────────────────────────────

    @Test
    void item04_every_invalid_row_has_its_own_reason_and_the_counts_add_up() {
        when(recipientRepository.findDeletedEmails(anyList())).thenReturn(List.of("gone@test.local"));

        ExcelPreviewResponse res = importService.previewExcel(xlsx(new String[] {"Email", "Name"}, new String[][] {
                {"not-an-email", "Bad"},
                {null, "No address"},
                {"gone@test.local", "Deleted contact"},
                {"ok@test.local", "Fine"},
        }));

        assertThat(res.rows()).extracting(ExcelRowPreview::reason).containsExactly(
                "Not a valid email address",
                "No email address in this row",
                "Belongs to a deleted contact, so it can't be added",
                null);
        assertThat(res.invalidRows()).isEqualTo(3);
        assertThat(res.validRows()).isEqualTo(1);
        assertThat(res.totalRows())
                .isEqualTo(res.validRows() + res.invalidRows() + res.duplicateRows() + res.skippedRows());

        ExcelPreviewResponse noEmailColumn = importService.previewExcel(
                xlsx(new String[] {"Name"}, new String[][] {{"Ada"}}));
        assertThat(noEmailColumn.rows()).extracting(ExcelRowPreview::reason)
                .containsExactly("No email column detected in file");
    }

    // ── Item 5 ──────────────────────────────────────────────────────────────────

    @Test
    void item05_one_normalisation_rule_matches_the_same_address_everywhere() {
        String nbsp = " Ada@Test.Local\u00A0";
        String zeroWidth = "\u200BADA@test.local\uFEFF";
        String wordJoiner = "ada@test.local\u2060";
        Recipient ada = contact(10L, "ada@test.local", RecipientStatus.ACTIVE);
        when(recipientRepository.findByEmailIn(List.of("ada@test.local"))).thenReturn(List.of(ada));

        // Upload preview: all three spellings are one address, the saved contact.
        ExcelPreviewResponse preview = importService.previewExcel(
                xlsx(new String[] {"Email"}, new String[][] {{nbsp}, {zeroWidth}, {wordJoiner}}));
        assertThat(preview.rows()).extracting(ExcelRowPreview::status).containsExactly(
                ExcelRowPreview.EXISTING, ExcelRowPreview.DUPLICATE, ExcelRowPreview.DUPLICATE);
        assertThat(preview.rows()).extracting(ExcelRowPreview::email).containsOnly("ada@test.local");

        // Lookup and /bulk find the same contact.
        assertThat(recipientService.lookup(new RecipientLookupRequest(null, List.of(zeroWidth))).recipients())
                .extracting(RecipientResponse::id).containsExactly(10L);
        BulkRecipientResponse bulk = importService.bulkCreateOrReuse(new BulkRecipientRequest(List.of(
                new BulkRecipientRequest.Row(wordJoiner, null, null, null, null, null)), "MANUAL"));
        assertThat(bulk.reused()).isEqualTo(1);
        assertThat(bulk.created()).isZero();
        assertThat(bulk.recipientIds()).containsExactly(10L);

        // Contact create: the normalised address is checked, so no second contact is made.
        when(recipientRepository.existsByEmailIgnoreCaseAndDeletedAtIsNull("ada@test.local")).thenReturn(true);
        assertThatThrownBy(() -> recipientService.create(
                new RecipientRequest(zeroWidth, null, null, null, null, null, null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Recipient already exists: ada@test.local");

        // Campaign-only addresses: stored once, normalised.
        when(campaignRepository.save(any(Campaign.class))).thenAnswer(inv -> {
            Campaign c = inv.getArgument(0);
            c.setId(CAMPAIGN_A);
            return c;
        });
        campaignService.create(new CampaignRequest("Normalised", null, "Hello", null, null,
                "sender@test.local", null, null, null, null,
                List.of(address(nbsp), address(zeroWidth), address(wordJoiner)), null));
        verify(targetAddressRepository).saveAll(argThat((List<CampaignTargetAddress> list) ->
                list.size() == 1 && list.get(0).getEmail().equals("ada@test.local")));

        // Delivery keys and the unsubscribe list use the same form.
        assertThat(CampaignRecipient.buildAddressIdempotencyKey(CAMPAIGN_A, nbsp))
                .isEqualTo(CampaignRecipient.buildAddressIdempotencyKey(CAMPAIGN_A, zeroWidth))
                .isEqualTo(CampaignRecipient.buildAddressIdempotencyKey(CAMPAIGN_A, "ada@test.local"));
        new UnsubscribeService(unsubscribeRepository, recipientRepository).isUnsubscribed(zeroWidth);
        verify(unsubscribeRepository).existsByEmailIgnoreCase("ada@test.local");

        verify(recipientRepository, never()).save(any());
    }

    // ── Item 6 ──────────────────────────────────────────────────────────────────

    @Test
    void item06_reusing_an_address_never_creates_a_second_contact() {
        Recipient grace = contact(42L, "grace@test.local", RecipientStatus.ACTIVE);
        when(recipientRepository.findByEmailIn(List.of("grace@test.local", "new@test.local"))).thenReturn(List.of(grace));
        when(recipientRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Recipient> created = inv.getArgument(0);
            created.forEach(r -> r.setId(500L));
            return created;
        });

        // /bulk reuses grace by address and creates only the new one.
        BulkRecipientResponse bulk = importService.bulkCreateOrReuse(new BulkRecipientRequest(List.of(
                new BulkRecipientRequest.Row("GRACE@test.local ", "Grace", null, null, null, null),
                new BulkRecipientRequest.Row("new@test.local", "New", null, null, null, null)), "UPLOAD"));
        assertThat(bulk.reused()).isEqualTo(1);
        assertThat(bulk.created()).isEqualTo(1);
        assertThat(bulk.recipientIds()).containsExactlyInAnyOrder(42L, 500L);
        verify(recipientRepository).saveAll(argThat((List<Recipient> list) ->
                list.size() == 1 && list.get(0).getEmail().equals("new@test.local")));

        // A campaign-only address that matches a contact resolves to that contact at launch.
        Campaign a = draft(CAMPAIGN_A);
        when(campaignRepository.findById(CAMPAIGN_A)).thenReturn(Optional.of(a));
        when(targetAddressRepository.countByCampaignId(CAMPAIGN_A)).thenReturn(1L);
        when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_A))
                .thenReturn(List.of(stored(CAMPAIGN_A, "grace@test.local")));
        when(recipientRepository.findByEmailIn(List.of("grace@test.local"))).thenReturn(List.of(grace));
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_A)).thenReturn(1L);

        campaignService.launch(CAMPAIGN_A);

        assertThat(savedRows(1).get(0)).singleElement().satisfies(row -> {
            assertThat(row.getRecipient()).isSameAs(grace);
            assertThat(row.getSnapshotEmail()).isNull();
            assertThat(row.getIdempotencyKey()).isEqualTo("campaign_31_recipient_42");
        });
        // Only the one contact /bulk created for "new@test.local" was ever inserted.
        verify(recipientRepository, times(1)).saveAll(any());
        verify(recipientRepository, never()).save(any());
    }

    // ── Item 7 ──────────────────────────────────────────────────────────────────

    @Test
    void item07_two_campaigns_keep_independent_histories() throws Exception {
        // A and B both target contact ada and the campaign-only address bob.
        Recipient ada = contact(10L, "ada@test.local", RecipientStatus.ACTIVE);
        Campaign a = draft(CAMPAIGN_A);
        Campaign b = draft(CAMPAIGN_B);
        for (Campaign c : List.of(a, b)) {
            c.setRecipientIds(new HashSet<>(Set.of(10L)));
            when(campaignRepository.findById(c.getId())).thenReturn(Optional.of(c));
            when(targetAddressRepository.findByCampaignIdOrderByIdAsc(c.getId()))
                    .thenReturn(List.of(stored(c.getId(), "bob@test.local")));
            when(campaignRecipientRepository.countByCampaignId(c.getId())).thenReturn(2L);
        }
        when(recipientRepository.findByIdsIn(Set.of(10L))).thenReturn(List.of(ada));

        campaignService.launch(CAMPAIGN_A);
        campaignService.launch(CAMPAIGN_B);

        List<List<CampaignRecipient>> launches = savedRows(2);
        List<CampaignRecipient> rowsA = launches.get(0);
        List<CampaignRecipient> rowsB = launches.get(1);
        assertThat(rowsA).extracting(CampaignRecipient::getIdempotencyKey).containsExactly(
                "campaign_31_recipient_10", CampaignRecipient.buildAddressIdempotencyKey(CAMPAIGN_A, "bob@test.local"));
        assertThat(rowsB).extracting(CampaignRecipient::getIdempotencyKey).containsExactly(
                "campaign_32_recipient_10", CampaignRecipient.buildAddressIdempotencyKey(CAMPAIGN_B, "bob@test.local"));
        assertThat(rowsA).allSatisfy(r -> assertThat(r.getCampaign()).isSameAs(a));
        assertThat(rowsB).allSatisfy(r -> assertThat(r.getCampaign()).isSameAs(b));

        // Launching B read only B's rows and changed nothing of A's: these are all the calls.
        for (long id : new long[] {CAMPAIGN_A, CAMPAIGN_B}) {
            verify(campaignRecipientRepository).findTargetedEmails(id);
            verify(campaignRecipientRepository).existsByCampaignIdAndRecipientId(id, 10L);
            verify(campaignRecipientRepository).countByCampaignId(id);
        }
        verify(campaignRecipientRepository, times(2)).saveAll(anyList());
        verifyNoMoreInteractions(campaignRecipientRepository);

        // The database accepts both histories side by side and keeps each one unique.
        assertDeliveryRowConstraintsAreInTheMigrations();
        try (Connection db = deliveryRowTable("reuse_item07")) {
            for (CampaignRecipient row : rowsA) insert(db, row);
            for (CampaignRecipient row : rowsB) insert(db, row);
            assertThat(count(db)).isEqualTo(4);

            // A second row for ada in A, even under another key, is rejected ...
            assertThatThrownBy(() -> insert(db, CAMPAIGN_A, 10L, "campaign_31_recipient_10_again", null))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            // ... and so is a second row with one of A's keys.
            assertThatThrownBy(() -> insert(db, CAMPAIGN_A, null, rowsA.get(1).getIdempotencyKey(), "bob@test.local"))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            assertThat(count(db)).isEqualTo(4);
        }
    }

    // ── Item 8 ──────────────────────────────────────────────────────────────────

    @Test
    void item08_unsubscribed_and_inactive_contacts_are_skipped_and_the_reason_says_so() {
        Recipient unsubscribed = contact(1L, "unsub@test.local", RecipientStatus.UNSUBSCRIBED);
        Recipient bounced = contact(2L, "bounced@test.local", RecipientStatus.BOUNCED);
        Recipient fine = contact(4L, "fine@test.local", RecipientStatus.ACTIVE);
        List<String> fileAddresses = List.of("unsub@test.local", "bounced@test.local", "listed@test.local", "fine@test.local");
        List<Recipient> contacts = List.of(unsubscribed, bounced, fine);
        when(recipientRepository.findByEmailIn(anyList())).thenAnswer(inv -> {
            List<String> wanted = inv.getArgument(0);
            return contacts.stream().filter(c -> wanted.contains(c.getEmail())).toList();
        });
        when(unsubscribeRepository.findEmailsIn(anyList())).thenReturn(List.of("listed@test.local"));

        // Upload: each skipped row says why; none is called a duplicate or "used before".
        ExcelPreviewResponse preview = importService.previewExcel(xlsx(new String[] {"Email"},
                fileAddresses.stream().map(e -> new String[] {e}).toArray(String[][]::new)));
        assertThat(preview.rows()).extracting(ExcelRowPreview::status).containsExactly(
                ExcelRowPreview.SKIPPED, ExcelRowPreview.SKIPPED, ExcelRowPreview.SKIPPED, ExcelRowPreview.EXISTING);
        assertThat(preview.rows().get(0).reason()).isEqualTo("Unsubscribed: this saved contact won't be emailed");
        assertThat(preview.rows().get(1).reason()).startsWith("Bounced");
        assertThat(preview.rows().get(2).reason()).isEqualTo("Unsubscribed: this address won't be emailed");
        assertThat(preview.rows()).extracting(ExcelRowPreview::reason).noneMatch(r -> r.toLowerCase().contains("duplicate")
                || r.toLowerCase().contains("used before") || r.toLowerCase().contains("already"));
        assertThat(preview.duplicateRows()).isZero();
        assertThat(preview.skippedRows()).isEqualTo(3);
        assertThat(preview.validRows()).isEqualTo(1);

        // Lookup reports the unsubscribe list too, so typed addresses can say why.
        RecipientLookupResponse lookup = recipientService.lookup(new RecipientLookupRequest(null, fileAddresses));
        assertThat(lookup.unsubscribedEmails()).containsExactly("listed@test.local");

        // Launch still skips them for compliance; only the ACTIVE contact gets a row.
        Campaign a = draft(CAMPAIGN_A);
        a.setRecipientIds(new HashSet<>(Set.of(1L, 2L, 4L)));
        when(campaignRepository.findById(CAMPAIGN_A)).thenReturn(Optional.of(a));
        when(recipientRepository.findByIdsIn(Set.of(1L, 2L, 4L))).thenReturn(List.of(unsubscribed, bounced, fine));
        when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_A))
                .thenReturn(List.of(stored(CAMPAIGN_A, "listed@test.local")));
        when(unsubscribeService.isUnsubscribed(anyString()))
                .thenAnswer(inv -> "listed@test.local".equals(inv.getArgument(0)));
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_A)).thenReturn(1L);

        campaignService.launch(CAMPAIGN_A);

        assertThat(savedRows(1).get(0)).extracting(CampaignRecipient::targetEmail).containsExactly("fine@test.local");
    }

    // ── Item 9 ──────────────────────────────────────────────────────────────────

    @Test
    void item09_launch_sends_only_to_explicit_targets_and_never_to_all_contacts() {
        // Any call to these fails the test: launch must never load "every active contact".
        lenient().when(recipientRepository.findAllActiveRecipients())
                .thenThrow(new AssertionError("launch called findAllActiveRecipients"));
        lenient().when(recipientRepository.findEligibleRecipients(any(), any()))
                .thenThrow(new AssertionError("launch called findEligibleRecipients"));

        // No targets at all: 400.
        Campaign none = draft(41L);
        when(campaignRepository.findById(41L)).thenReturn(Optional.of(none));
        assertThatThrownBy(() -> campaignService.launch(41L))
                .isInstanceOf(ValidationException.class)
                .hasMessage("Campaign has no recipients selected")
                .satisfies(e -> assertThat(((MailFlowException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(none.getStatus()).isEqualTo(CampaignStatus.DRAFT);

        // Targets, but none can be emailed: 400 as well.
        Campaign unsendable = draft(42L);
        unsendable.setRecipientIds(new HashSet<>(Set.of(1L)));
        when(campaignRepository.findById(42L)).thenReturn(Optional.of(unsendable));
        when(recipientRepository.findByIdsIn(Set.of(1L)))
                .thenReturn(List.of(contact(1L, "unsub@test.local", RecipientStatus.UNSUBSCRIBED)));
        assertThatThrownBy(() -> campaignService.launch(42L))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("None of the selected recipients can be emailed")
                .satisfies(e -> assertThat(((MailFlowException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(unsendable.getStatus()).isEqualTo(CampaignStatus.DRAFT);
        verify(campaignRecipientRepository, never()).saveAll(any());
        verifyNoInteractions(jobLauncher);

        // Contact IDs, a group and a campaign-only address: exactly those are emailed.
        Campaign explicit = draft(43L);
        explicit.setRecipientIds(new HashSet<>(Set.of(10L)));
        explicit.setRecipientGroupIds(new HashSet<>(Set.of(5L)));
        when(campaignRepository.findById(43L)).thenReturn(Optional.of(explicit));
        when(recipientRepository.findByIdsIn(Set.of(10L)))
                .thenReturn(List.of(contact(10L, "picked@test.local", RecipientStatus.ACTIVE)));
        when(recipientRepository.findByGroupIds(Set.of(5L)))
                .thenReturn(List.of(contact(11L, "member@test.local", RecipientStatus.ACTIVE)));
        when(targetAddressRepository.findByCampaignIdOrderByIdAsc(43L))
                .thenReturn(List.of(stored(43L, "typed@test.local")));
        when(campaignRecipientRepository.countByCampaignId(43L)).thenReturn(3L);

        campaignService.launch(43L);

        assertThat(savedRows(1).get(0)).extracting(CampaignRecipient::targetEmail)
                .containsExactly("picked@test.local", "member@test.local", "typed@test.local");
        verify(recipientRepository, never()).findAllActiveRecipients();
        verify(recipientRepository, never()).findEligibleRecipients(any(), any());
        verify(jobLauncher).launchCampaignJob(43L);
    }

    // ── Item 10 ─────────────────────────────────────────────────────────────────

    @Test
    void item10_retries_and_repeated_launches_do_not_send_twice() throws Exception {
        Recipient ada = contact(10L, "ada@test.local", RecipientStatus.ACTIVE);
        Campaign a = draft(CAMPAIGN_A);
        a.setRecipientIds(new HashSet<>(Set.of(10L)));
        when(campaignRepository.findById(CAMPAIGN_A)).thenReturn(Optional.of(a));
        when(recipientRepository.findByIdsIn(Set.of(10L))).thenReturn(List.of(ada));
        when(targetAddressRepository.findByCampaignIdOrderByIdAsc(CAMPAIGN_A))
                .thenReturn(List.of(stored(CAMPAIGN_A, "bob@test.local")));
        // First launch: no rows yet. Relaunch: both addresses already have their row.
        when(campaignRecipientRepository.findTargetedEmails(CAMPAIGN_A))
                .thenReturn(List.of(), List.of("ada@test.local", "BOB@test.local"));
        when(campaignRecipientRepository.countByCampaignId(CAMPAIGN_A)).thenReturn(2L);

        campaignService.launch(CAMPAIGN_A);
        List<CampaignRecipient> rows = savedRows(1).get(0);
        String adaKey = rows.get(0).getIdempotencyKey();
        String bobKey = rows.get(1).getIdempotencyKey();
        assertThat(adaKey).isEqualTo(CampaignRecipient.buildIdempotencyKey(CAMPAIGN_A, 10L));
        // Every spelling of the address gives the same key, so it can't sneak in twice.
        assertThat(bobKey).isEqualTo(CampaignRecipient.buildAddressIdempotencyKey(CAMPAIGN_A, " BOB@test.local\u200B"));

        // A repeated launch while queued (a double click, a second tab) is refused with 409.
        assertThatThrownBy(() -> campaignService.launch(CAMPAIGN_A))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((MailFlowException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        // A relaunch after a failure adds no row for an address that already has one.
        a.setStatus(CampaignStatus.FAILED);
        campaignService.launch(CAMPAIGN_A);
        verify(campaignRecipientRepository, times(1)).saveAll(anyList());
        verify(jobLauncher, times(2)).launchCampaignJob(CAMPAIGN_A);

        // Batch retry: a row that is already QUEUED or SENT is not published again.
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(campaignRecipientRepository.markQueued(eq(adaKey), any(Instant.class))).thenReturn(0);
        when(campaignRepository.findStatusById(CAMPAIGN_A)).thenReturn(Optional.of(CampaignStatus.RUNNING));
        new CampaignItemWriter(rabbitTemplate, campaignRecipientRepository, campaignRepository)
                .write(Chunk.of(message(adaKey)));
        verifyNoInteractions(rabbitTemplate);

        // RabbitMQ redelivery: a message whose key was already sent is dropped without sending.
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> redis = mock(RedisTemplate.class);
        EmailProvider emailProvider = mock(EmailProvider.class);
        when(redis.hasKey("email:sent:" + bobKey)).thenReturn(true);
        new EmailConsumer(emailProvider, campaignRecipientRepository, campaignRepository,
                mock(RateLimiterService.class), mock(EmailLogService.class), redis).consume(message(bobKey));
        verifyNoInteractions(emailProvider);
        verify(campaignRecipientRepository, never()).markSent(anyString(), any());

        // And the database refuses a second delivery row for either key.
        assertDeliveryRowConstraintsAreInTheMigrations();
        try (Connection db = deliveryRowTable("reuse_item10")) {
            for (CampaignRecipient row : rows) insert(db, row);
            assertThatThrownBy(() -> insert(db, CAMPAIGN_A, 10L, adaKey, null))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            assertThatThrownBy(() -> insert(db, CAMPAIGN_A, null, bobKey, "bob@test.local"))
                    .isInstanceOf(SQLIntegrityConstraintViolationException.class);
            assertThat(count(db)).isEqualTo(2);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private Campaign draft(long id) {
        return Campaign.builder()
                .id(id)
                .name("[test] campaign " + id)
                .subject("Hello {{FirstName}}")
                .senderEmail("sender@test.local")
                .status(CampaignStatus.DRAFT)
                .template(template)
                .build();
    }

    private static Recipient contact(long id, String email, RecipientStatus status) {
        return Recipient.builder().id(id).email(email).status(status).build();
    }

    private static CampaignTargetAddress stored(long campaignId, String email) {
        return CampaignTargetAddress.builder().campaignId(campaignId).email(email).build();
    }

    private static TargetAddress address(String email) {
        return new TargetAddress(email, null, null, null, null, null);
    }

    private static EmailMessage message(String key) {
        return EmailMessage.builder()
                .idempotencyKey(key)
                .campaignId(CAMPAIGN_A)
                .toAddress("ada@test.local")
                .senderEmail("sender@test.local")
                .subject("Hello")
                .plainBody("Hello")
                .attemptNumber(2)
                .createdAt(Instant.now())
                .build();
    }

    /** The lists of delivery rows passed to saveAll, one per launch that created rows. */
    @SuppressWarnings("unchecked")
    private List<List<CampaignRecipient>> savedRows(int launches) {
        ArgumentCaptor<List<CampaignRecipient>> captor = ArgumentCaptor.forClass(List.class);
        verify(campaignRecipientRepository, times(launches)).saveAll(captor.capture());
        return new ArrayList<>(captor.getAllValues());
    }

    private void verifyNoWrites() {
        verify(recipientRepository, never()).save(any());
        verify(recipientRepository, never()).saveAll(any());
        verifyNoInteractions(campaignRecipientRepository);
    }

    private static MockMultipartFile xlsx(String[] headers, String[][] rows) {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            return new MockMultipartFile("file", "recipients.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The constraints that make delivery idempotent, as declared by the real migrations:
     * V1's campaign_recipients has a unique idempotency key and UNIQUE (campaign_id,
     * recipient_id); V5 adds a unique index per campaign and lower-cased address for rows
     * without a contact.
     */
    private static void assertDeliveryRowConstraintsAreInTheMigrations() throws IOException {
        String v1 = migration("V1__init_schema.sql");
        int start = v1.indexOf("CREATE TABLE campaign_recipients (");
        assertThat(start).isNotNegative();
        String table = v1.substring(start, v1.indexOf(");", start));
        assertThat(table).contains("idempotency_key VARCHAR(100) NOT NULL UNIQUE")
                .contains("UNIQUE (campaign_id, recipient_id)");
        assertThat(migration("V5__campaign_only_addresses.sql")).contains(
                "CREATE UNIQUE INDEX uq_campaign_recipients_address ON campaign_recipients "
                        + "(campaign_id, lower(email)) WHERE recipient_id IS NULL");
    }

    private static String migration(String name) throws IOException {
        String sql = new ClassPathResource("db/migration/" + name).getContentAsString(StandardCharsets.UTF_8);
        return sql.replaceAll("\\s+", " ");
    }

    /**
     * An in-memory H2 table with the same unique constraints as campaign_recipients. H2
     * has no partial or expression indexes, so V5's per-address index is checked as text
     * above; the unique idempotency key covers address rows here.
     */
    private static Connection deliveryRowTable(String name) throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:h2:mem:" + name, "sa", "");
        try (Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE campaign_recipients (
                        id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        campaign_id BIGINT NOT NULL,
                        recipient_id BIGINT,
                        idempotency_key VARCHAR(100) NOT NULL UNIQUE,
                        email VARCHAR(255),
                        UNIQUE (campaign_id, recipient_id),
                        CHECK (recipient_id IS NOT NULL OR email IS NOT NULL))""");
        }
        return c;
    }

    private static void insert(Connection db, CampaignRecipient row) throws SQLException {
        insert(db, row.getCampaign().getId(), row.getRecipient() != null ? row.getRecipient().getId() : null,
                row.getIdempotencyKey(), row.getSnapshotEmail());
    }

    private static void insert(Connection db, long campaignId, Long recipientId, String key, String email)
            throws SQLException {
        try (PreparedStatement ps = db.prepareStatement(
                "INSERT INTO campaign_recipients (campaign_id, recipient_id, idempotency_key, email) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, campaignId);
            if (recipientId != null) ps.setLong(2, recipientId); else ps.setNull(2, Types.BIGINT);
            ps.setString(3, key);
            if (email != null) ps.setString(4, email); else ps.setNull(4, Types.VARCHAR);
            ps.executeUpdate();
        }
    }

    private static long count(Connection db) throws SQLException {
        try (Statement s = db.createStatement(); var rs = s.executeQuery("SELECT COUNT(*) FROM campaign_recipients")) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
