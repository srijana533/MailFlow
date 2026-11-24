package com.mailflow.recipient;

import com.mailflow.common.exception.ValidationException;
import com.mailflow.email.repository.UnsubscribeRepository;
import com.mailflow.recipient.dto.BulkRecipientRequest;
import com.mailflow.recipient.dto.BulkRecipientResponse;
import com.mailflow.recipient.dto.ExcelPreviewResponse;
import com.mailflow.recipient.dto.ExcelRowPreview;
import com.mailflow.recipient.dto.ImportResult;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.repository.RecipientRepository;
import com.mailflow.recipient.service.RecipientImportService;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Tests the validate-only preview, bulk create/reuse, and template generation
 * added for the "bulk email from Excel" feature. No emails are sent and
 * nothing touches a real database (the repository is mocked).
 */
@ExtendWith(MockitoExtension.class)
class RecipientImportPreviewTest {

    @Mock private RecipientRepository recipientRepository;
    @Mock private UnsubscribeRepository unsubscribeRepository;
    @InjectMocks private RecipientImportService importService;

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Builds an in-memory .xlsx with the given header row and data rows. */
    private MockMultipartFile excel(String name, String[] headers, String[][] rows) throws IOException {
        return workbookFile(new XSSFWorkbook(), name,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", headers, rows);
    }

    private MockMultipartFile workbookFile(Workbook wb, String name, String contentType,
                                           String[] headers, String[][] rows) throws IOException {
        try (wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            wb.write(out);
            return new MockMultipartFile("file", name, contentType, out.toByteArray());
        }
    }

    private MockMultipartFile csv(String name, byte[] content) {
        return new MockMultipartFile("file", name, "text/csv", content);
    }

    private Recipient saved(long id, String email, RecipientStatus status) {
        return Recipient.builder().id(id).email(email).status(status).build();
    }

    // ── Preview: valid / invalid / duplicate / existing detection ──────────────

    @Test
    void preview_flags_valid_invalid_and_in_file_duplicates() throws IOException {
        MockMultipartFile file = excel(
                "contacts.xlsx",
                new String[] {"Email", "Name", "Company"},
                new String[][] {
                        {"ada@example.com",  "Ada",  "Acme"},   // valid
                        {"not-an-email",     "Bob",  "Beta"},    // invalid
                        {"ada@example.com",  "Ada2", "Acme"},    // duplicate (in file)
                        {"grace@example.com","Grace","Gamma"},   // valid
                });

        ExcelPreviewResponse res = importService.previewExcel(file);

        assertThat(res.totalRows()).isEqualTo(4);
        assertThat(res.validRows()).isEqualTo(2);
        assertThat(res.newRows()).isEqualTo(2);
        assertThat(res.invalidRows()).isEqualTo(1);
        assertThat(res.duplicateRows()).isEqualTo(1);
        assertThat(res.existingRows()).isZero();
        assertThat(res.skippedRows()).isZero();
        assertThat(res.hasEmailColumn()).isTrue();
        assertThat(res.columns()).containsExactly("Email", "Name", "Company");
        assertThat(res.rows()).hasSize(4);

        assertThat(res.rows().get(1).status()).isEqualTo(ExcelRowPreview.INVALID);
        assertThat(res.rows().get(1).reason()).isEqualTo("Not a valid email address");
        assertThat(res.rows().get(2).status()).isEqualTo(ExcelRowPreview.DUPLICATE);
        assertThat(res.rows().get(2).reason()).contains("first on row 2");
    }

    @Test
    void preview_marks_saved_contacts_as_existing_with_their_id() throws IOException {
        // grace is already a saved contact: she is reused, not reported as a problem
        when(recipientRepository.findByEmailIn(anyList()))
                .thenReturn(List.of(saved(42L, "grace@example.com", RecipientStatus.ACTIVE)));

        MockMultipartFile file = excel(
                "contacts.xlsx",
                new String[] {"Email", "Name"},
                new String[][] {
                        {"ada@example.com",   "Ada"},
                        {"GRACE@example.com", "Grace"},
                });

        ExcelPreviewResponse res = importService.previewExcel(file);

        // Valid counts every address that can be emailed: the new one and the saved contact.
        assertThat(res.validRows()).isEqualTo(2);
        assertThat(res.newRows()).isEqualTo(1);
        assertThat(res.existingRows()).isEqualTo(1);
        assertThat(res.duplicateRows()).isZero();
        ExcelRowPreview graceRow = res.rows().get(1);
        assertThat(graceRow.status()).isEqualTo(ExcelRowPreview.EXISTING);
        assertThat(graceRow.recipientId()).isEqualTo(42L);
        assertThat(graceRow.email()).isEqualTo("grace@example.com");
        assertThat(graceRow.reason()).containsIgnoringCase("saved contact");
        assertThat(res.rows().get(0).recipientId()).isNull();
    }

    @Test
    void preview_skips_saved_contacts_that_are_not_active_and_says_why() throws IOException {
        when(recipientRepository.findByEmailIn(anyList())).thenReturn(List.of(
                saved(7L, "gone@example.com", RecipientStatus.UNSUBSCRIBED),
                saved(8L, "bounced@example.com", RecipientStatus.BOUNCED),
                saved(9L, "bad@example.com", RecipientStatus.INVALID)));

        ExcelPreviewResponse res = importService.previewExcel(excel(
                "contacts.xlsx", new String[] {"Email"},
                new String[][] {{"gone@example.com"}, {"bounced@example.com"}, {"bad@example.com"}}));

        assertThat(res.rows()).extracting(ExcelRowPreview::status).containsOnly(ExcelRowPreview.SKIPPED);
        assertThat(res.rows()).extracting(ExcelRowPreview::recipientId).containsExactly(7L, 8L, 9L);
        assertThat(res.rows().get(0).reason()).startsWith("Unsubscribed");
        assertThat(res.rows().get(1).reason()).startsWith("Bounced");
        assertThat(res.rows().get(2).reason()).startsWith("Marked invalid");
        assertThat(res.rows()).extracting(ExcelRowPreview::reason)
                .noneMatch(r -> r.toLowerCase().contains("duplicate") || r.toLowerCase().contains("used before"));
        assertThat(res.skippedRows()).isEqualTo(3);
        assertThat(res.validRows()).isZero();
        assertThat(res.existingRows()).isZero();
    }

    @Test
    void preview_skips_addresses_on_the_unsubscribe_list_and_says_why() throws IOException {
        // A saved ACTIVE contact whose address is on the unsubscribe list, and an address
        // that is only on the list. Both are skipped when sending, so neither is Valid.
        when(recipientRepository.findByEmailIn(anyList()))
                .thenReturn(List.of(saved(5L, "listed@example.com", RecipientStatus.ACTIVE)));
        when(unsubscribeRepository.findEmailsIn(anyList()))
                .thenReturn(List.of("listed@example.com", "optout@example.com"));

        ExcelPreviewResponse res = importService.previewExcel(excel(
                "contacts.xlsx", new String[] {"Email"},
                new String[][] {{"listed@example.com"}, {"optout@example.com"}, {"fine@example.com"}}));

        assertThat(res.rows()).extracting(ExcelRowPreview::status)
                .containsExactly(ExcelRowPreview.SKIPPED, ExcelRowPreview.SKIPPED, ExcelRowPreview.VALID);
        assertThat(res.rows().get(0).recipientId()).isEqualTo(5L);
        assertThat(res.rows().get(0).reason()).startsWith("Unsubscribed");
        assertThat(res.rows().get(1).recipientId()).isNull();
        assertThat(res.rows().get(1).reason()).isEqualTo("Unsubscribed: this address won't be emailed");
        assertThat(res.skippedRows()).isEqualTo(2);
        assertThat(res.validRows()).isEqualTo(1);
        assertThat(res.totalRows())
                .isEqualTo(res.validRows() + res.invalidRows() + res.duplicateRows() + res.skippedRows());
    }

    @Test
    void preview_normalises_addresses_before_matching_and_returns_the_normalised_address() throws IOException {
        when(recipientRepository.findByEmailIn(List.of("grace@example.com", "ada@example.com")))
                .thenReturn(List.of(saved(42L, "grace@example.com", RecipientStatus.ACTIVE)));

        ExcelPreviewResponse res = importService.previewExcel(excel(
                "contacts.xlsx", new String[] {"Email"},
                new String[][] {
                        {" GRACE@Example.com\u00A0"},     // saved contact, NBSP after it
                        {"\u200Bgrace@example.com\uFEFF"}, // same address with invisible characters
                        {"\u2060Ada@Example.com\u200D"},   // new address
                }));

        assertThat(res.rows()).extracting(ExcelRowPreview::status)
                .containsExactly(ExcelRowPreview.EXISTING, ExcelRowPreview.DUPLICATE, ExcelRowPreview.VALID);
        assertThat(res.rows().get(1).reason()).contains("first on row 2");
        assertThat(res.rows()).extracting(ExcelRowPreview::email)
                .containsExactly("grace@example.com", "grace@example.com", "ada@example.com");
        assertThat(res.rows().get(2).fields()).containsEntry("email", "ada@example.com");
        verify(unsubscribeRepository).findEmailsIn(List.of("grace@example.com", "ada@example.com"));
    }

    @Test
    void preview_accepts_display_name_and_mailto_cells() throws IOException {
        ExcelPreviewResponse res = importService.previewUpload(csv("pasted.csv",
                ("Email,Name\n"
                        + "\"Ada Lovelace <Ada@Example.com>\",Ada\n"
                        + "mailto:grace@example.com?subject=Hello,Grace\n"
                        + "\"Alan <not an address>\",Alan\n").getBytes(StandardCharsets.UTF_8)), null);

        assertThat(res.rows()).extracting(ExcelRowPreview::status)
                .containsExactly(ExcelRowPreview.VALID, ExcelRowPreview.VALID, ExcelRowPreview.INVALID);
        assertThat(res.rows().get(0).email()).isEqualTo("ada@example.com");
        assertThat(res.rows().get(0).name()).isEqualTo("Ada");
        assertThat(res.rows().get(1).email()).isEqualTo("grace@example.com");
        assertThat(res.rows().get(2).email()).isEqualTo("Alan <not an address>");
        assertThat(res.rows().get(2).reason()).isEqualTo("Not a valid email address");
    }

    @Test
    void preview_reports_a_row_without_an_address() {
        ExcelPreviewResponse res = importService.previewUpload(csv("gaps.csv",
                "Email,Name\n,Nobody\nada@example.com,Ada\n".getBytes(StandardCharsets.UTF_8)), null);

        assertThat(res.rows().get(0).status()).isEqualTo(ExcelRowPreview.INVALID);
        assertThat(res.rows().get(0).reason()).isEqualTo("No email address in this row");
        assertThat(res.validRows()).isEqualTo(1);
    }

    @Test
    void preview_rejects_addresses_of_deleted_contacts() throws IOException {
        // The email column is unique across deleted rows too, so these can't be re-created.
        when(recipientRepository.findDeletedEmails(anyList())).thenReturn(List.of("old@example.com"));

        ExcelPreviewResponse res = importService.previewExcel(excel(
                "contacts.xlsx", new String[] {"Email"},
                new String[][] {{"old@example.com"}, {"new@example.com"}}));

        assertThat(res.rows().get(0).status()).isEqualTo(ExcelRowPreview.INVALID);
        assertThat(res.rows().get(0).reason()).contains("deleted contact");
        assertThat(res.rows().get(1).status()).isEqualTo(ExcelRowPreview.VALID);
        assertThat(res.invalidRows()).isEqualTo(1);
        assertThat(res.validRows()).isEqualTo(1);
    }

    @Test
    void preview_detects_missing_email_column() throws IOException {
        MockMultipartFile file = excel(
                "no-email.xlsx",
                new String[] {"Name", "Company"},
                new String[][] {
                        {"Ada", "Acme"},
                });

        ExcelPreviewResponse res = importService.previewExcel(file);

        assertThat(res.hasEmailColumn()).isFalse();
        assertThat(res.validRows()).isZero();
        assertThat(res.rows().get(0).status()).isEqualTo(ExcelRowPreview.INVALID);
        verifyNoInteractions(recipientRepository);
    }

    @Test
    void preview_maps_case_insensitive_and_aliased_headers() throws IOException {
        MockMultipartFile file = excel(
                "aliases.xlsx",
                new String[] {"E-MAIL", "First Name", "Surname", "Organisation", "Role", "Location"},
                new String[][] {
                        {"ada@example.com", "Ada", "Lovelace", "Acme", "Engineer", "London"},
                });

        ExcelPreviewResponse res = importService.previewExcel(file);

        assertThat(res.hasEmailColumn()).isTrue();
        assertThat(res.validRows()).isEqualTo(1);

        var fields = res.rows().get(0).fields();
        assertThat(fields).containsEntry("email", "ada@example.com");
        assertThat(fields).containsEntry("firstName", "Ada");
        assertThat(fields).containsEntry("lastName", "Lovelace");
        assertThat(fields).containsEntry("company", "Acme");
        // role + location are stored as custom fields
        assertThat(fields).containsEntry("role", "Engineer");
        assertThat(fields).containsEntry("location", "London");
    }

    // ── Column mapping overrides ────────────────────────────────────────────────

    @Test
    void preview_applies_column_mapping_overrides() {
        MockMultipartFile file = csv("crm-export.csv",
                "Contact,Org,Team,Notes\nada@example.com,Acme,Platform,call back\n"
                        .getBytes(StandardCharsets.UTF_8));

        // Without a mapping, "Contact" is not recognised as the email column.
        assertThat(importService.previewUpload(file, null).hasEmailColumn()).isFalse();

        ExcelPreviewResponse res = importService.previewUpload(file, Map.of(
                "Contact", "email",
                "Org", "company",
                "Team", "custom:Team",
                "Notes", "ignore"));

        assertThat(res.hasEmailColumn()).isTrue();
        assertThat(res.detectedColumns())
                .containsEntry("Contact", "email")
                .containsEntry("Org", "company")
                .containsEntry("Team", "custom:Team")
                .doesNotContainKey("Notes");
        assertThat(res.unmappedColumns()).containsExactly("Notes");
        ExcelRowPreview row = res.rows().get(0);
        assertThat(row.status()).isEqualTo(ExcelRowPreview.VALID);
        assertThat(row.fields())
                .containsEntry("email", "ada@example.com")
                .containsEntry("company", "Acme")
                .containsEntry("Team", "Platform")
                .doesNotContainKey("Notes");
    }

    @Test
    void explicit_mapping_takes_a_field_away_from_an_auto_detected_column() {
        MockMultipartFile file = csv("two-emails.csv",
                "Email,Work Email\nhome@example.com,work@example.com\n".getBytes(StandardCharsets.UTF_8));

        ExcelPreviewResponse res = importService.previewUpload(file, Map.of("Work Email", "email"));

        assertThat(res.detectedColumns()).containsEntry("Work Email", "email").doesNotContainKey("Email");
        assertThat(res.unmappedColumns()).containsExactly("Email");
        assertThat(res.rows().get(0).email()).isEqualTo("work@example.com");
    }

    @Test
    void conflicting_or_unsupported_mappings_are_rejected() {
        MockMultipartFile file = csv("c.csv", "A,B\nx@example.com,y@example.com\n".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> importService.previewUpload(file, Map.of("A", "email", "B", "email")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("both mapped to Email");
        assertThatThrownBy(() -> importService.previewUpload(file, Map.of("A", "custom:1bad")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unsupported column mapping");
        // A custom field may not shadow a standard field.
        assertThatThrownBy(() -> importService.previewUpload(file, Map.of("A", "custom:email")))
                .isInstanceOf(ValidationException.class);
    }

    // ── File formats and malformed input ───────────────────────────────────────

    @Test
    void preview_reads_legacy_xls_workbooks() throws IOException {
        MockMultipartFile file = workbookFile(new HSSFWorkbook(), "legacy.xls",
                "application/vnd.ms-excel",
                new String[] {"Email", "Company"},
                new String[][] {{"ada@example.com", "Acme"}});

        ExcelPreviewResponse res = importService.previewUpload(file, null);

        assertThat(res.validRows()).isEqualTo(1);
        assertThat(res.rows().get(0).company()).isEqualTo("Acme");
    }

    @Test
    void preview_reads_semicolon_csv_with_bom_and_windows_1252_csv() {
        byte[] semicolonWithBom = "\uFEFFEmail;Name;Company\nada@example.com;Ada;Acme\n"
                .getBytes(StandardCharsets.UTF_8);
        ExcelPreviewResponse res = importService.previewUpload(csv("eu.csv", semicolonWithBom), null);
        assertThat(res.columns()).containsExactly("Email", "Name", "Company");
        assertThat(res.rows().get(0).fields())
                .containsEntry("name", "Ada").containsEntry("company", "Acme");

        byte[] cp1252 = "Email,Name\nzoe@example.com,Zo\u00eb\n".getBytes(Charset.forName("windows-1252"));
        ExcelPreviewResponse legacy = importService.previewUpload(csv("legacy.csv", cp1252), null);
        assertThat(legacy.rows().get(0).name()).isEqualTo("Zo\u00eb");
    }

    @Test
    void malformed_or_unsupported_files_are_rejected_with_a_clear_message() {
        MockMultipartFile notASpreadsheet = new MockMultipartFile("file", "contacts.xlsx",
                "application/octet-stream", "this is not a spreadsheet".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> importService.previewUpload(notASpreadsheet, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("couldn't be read");

        byte[] corruptZip = {'P', 'K', 3, 4, 20, 0, 0, 0, 8, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        MockMultipartFile corrupt = new MockMultipartFile("file", "corrupt.xlsx",
                "application/octet-stream", corruptZip);
        assertThatThrownBy(() -> importService.previewUpload(corrupt, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("couldn't be read");

        MockMultipartFile unclosedQuote = csv("bad.csv",
                "Email,Name\n\"ada@example.com,Ada\n".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> importService.previewUpload(unclosedQuote, null))
                .isInstanceOf(ValidationException.class);

        MockMultipartFile text = new MockMultipartFile("file", "contacts.txt", "text/plain",
                "email\nada@example.com".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> importService.previewUpload(text, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Unsupported file type");

        MockMultipartFile empty = new MockMultipartFile("file", "empty.csv", "text/csv", new byte[0]);
        assertThatThrownBy(() -> importService.previewUpload(empty, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("empty");

        verifyNoInteractions(recipientRepository);
    }

    @Test
    void files_over_the_row_limit_are_rejected_not_truncated() {
        StringBuilder sb = new StringBuilder("Email\n");
        for (int i = 0; i <= RecipientImportService.MAX_ROWS; i++) {   // MAX_ROWS + 1 rows
            sb.append("user").append(i).append("@example.com\n");
        }
        MockMultipartFile big = csv("big.csv", sb.toString().getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> importService.previewUpload(big, null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("more than 10,000");
        verifyNoInteractions(recipientRepository);
    }

    @Test
    void exactly_10_000_rows_are_accepted() {
        StringBuilder sb = new StringBuilder("Email\n");
        for (int i = 0; i < RecipientImportService.MAX_ROWS; i++) {
            sb.append("user").append(i).append("@example.com\n");
        }

        ExcelPreviewResponse preview = importService.previewUpload(
                csv("limit.csv", sb.toString().getBytes(StandardCharsets.UTF_8)), null);

        assertThat(preview.totalRows()).isEqualTo(10_000);
        assertThat(preview.validRows()).isEqualTo(10_000);
    }

    @Test
    void files_over_5_mb_are_rejected_before_parsing_and_5_mb_is_accepted() {
        // One row whose unused Notes cell pads the file to exactly 5 MB.
        byte[] atLimit = new byte[(int) RecipientImportService.MAX_PREVIEW_BYTES];
        java.util.Arrays.fill(atLimit, (byte) 'x');
        byte[] start = "Email,Notes\nada@example.com,".getBytes(StandardCharsets.UTF_8);
        System.arraycopy(start, 0, atLimit, 0, start.length);

        ExcelPreviewResponse preview = importService.previewUpload(csv("five-mb.csv", atLimit), null);
        assertThat(preview.totalRows()).isEqualTo(1);
        assertThat(preview.validRows()).isEqualTo(1);

        byte[] overLimit = java.util.Arrays.copyOf(atLimit, atLimit.length + 1);
        overLimit[overLimit.length - 1] = 'x';
        clearInvocations(recipientRepository);
        assertThatThrownBy(() -> importService.previewUpload(csv("too-big.csv", overLimit), null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("larger than 5 MB");
        verifyNoInteractions(recipientRepository);
    }

    @Test
    void the_multipart_limit_lets_files_over_5_mb_reach_the_400_check() {
        // Spring rejects a request above its multipart limits before the controller runs
        // (413 FILE_TOO_LARGE). The limits must stay above 5 MB, or the preview's clear
        // 400 message for a 5-25 MB file would never be returned.
        org.springframework.beans.factory.config.YamlPropertiesFactoryBean yaml =
                new org.springframework.beans.factory.config.YamlPropertiesFactoryBean();
        yaml.setResources(new org.springframework.core.io.ClassPathResource("application.yml"));
        java.util.Properties props = yaml.getObject();
        assertThat(props).isNotNull();

        long maxFile = org.springframework.util.unit.DataSize
                .parse(props.getProperty("spring.servlet.multipart.max-file-size")).toBytes();
        long maxRequest = org.springframework.util.unit.DataSize
                .parse(props.getProperty("spring.servlet.multipart.max-request-size")).toBytes();
        assertThat(maxFile).isEqualTo(25L * 1024 * 1024).isGreaterThan(RecipientImportService.MAX_PREVIEW_BYTES);
        assertThat(maxRequest).isGreaterThanOrEqualTo(maxFile);

        // Above the multipart limit the API answers 413 with the standard error body.
        jakarta.servlet.http.HttpServletRequest request =
                new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/recipients/import/excel/preview");
        var response = new com.mailflow.common.exception.GlobalExceptionHandler().handleMaxUploadSize(
                new org.springframework.web.multipart.MaxUploadSizeExceededException(maxFile), request);
        assertThat(response.getStatusCode().value()).isEqualTo(413);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("FILE_TOO_LARGE");
    }

    // ── Bulk create / reuse ─────────────────────────────────────────────────────

    @Test
    void bulk_creates_new_and_reuses_existing_by_email() {
        Recipient existing = Recipient.builder()
                .id(42L).email("grace@example.com").name("Grace").build();
        when(recipientRepository.findByEmailIn(anyList())).thenReturn(List.of(existing));
        // saveAll returns the created recipients with assigned IDs
        when(recipientRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Recipient> arg = inv.getArgument(0);
            List<Recipient> saved = new ArrayList<>();
            long id = 100;
            for (Recipient r : arg) { r.setId(id++); saved.add(r); }
            return saved;
        });

        BulkRecipientRequest req = new BulkRecipientRequest(List.of(
                new BulkRecipientRequest.Row("ada@example.com", "Ada", "Ada", "Lovelace", "Acme",
                        java.util.Map.of("role", "Engineer")),
                new BulkRecipientRequest.Row("grace@example.com", "Grace", null, null, null, null),
                new BulkRecipientRequest.Row("not-an-email", "Bad", null, null, null, null)
        ), null);

        BulkRecipientResponse res = importService.bulkCreateOrReuse(req);

        assertThat(res.created()).isEqualTo(1);          // ada
        assertThat(res.reused()).isEqualTo(1);           // grace
        assertThat(res.skippedInvalid()).isEqualTo(1);   // not-an-email
        assertThat(res.skippedDeleted()).isZero();
        assertThat(res.recipientIds()).contains(42L);    // reused id
        assertThat(res.recipientIds()).hasSize(2);
    }

    @Test
    void bulk_deduplicates_within_request() {
        when(recipientRepository.findByEmailIn(anyList())).thenReturn(List.of());
        when(recipientRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Recipient> arg = inv.getArgument(0);
            long id = 1;
            for (Recipient r : arg) r.setId(id++);
            return arg;
        });

        BulkRecipientRequest req = new BulkRecipientRequest(List.of(
                new BulkRecipientRequest.Row("ADA@example.com", "Ada", null, null, null, null),
                new BulkRecipientRequest.Row("ada@example.com", "Ada again", null, null, null, null)
        ), null);

        BulkRecipientResponse res = importService.bulkCreateOrReuse(req);

        // Same email (case-insensitive) collapses to one created recipient
        assertThat(res.created()).isEqualTo(1);
        assertThat(res.recipientIds()).hasSize(1);
    }

    @Test
    void bulk_skips_deleted_contacts_instead_of_failing_or_reactivating_them() {
        when(recipientRepository.findByEmailIn(anyList())).thenReturn(List.of());
        when(recipientRepository.findDeletedEmails(anyList())).thenReturn(List.of("old@example.com"));
        when(recipientRepository.saveAll(anyList())).thenAnswer(inv -> {
            List<Recipient> arg = inv.getArgument(0);
            long id = 500;
            for (Recipient r : arg) r.setId(id++);
            return arg;
        });

        BulkRecipientResponse res = importService.bulkCreateOrReuse(new BulkRecipientRequest(List.of(
                new BulkRecipientRequest.Row("old@example.com", null, null, null, null, null),
                new BulkRecipientRequest.Row("new@example.com", null, null, null, null, null)
        ), "MANUAL"));

        assertThat(res.created()).isEqualTo(1);
        assertThat(res.skippedDeleted()).isEqualTo(1);
        assertThat(res.recipientIds()).containsExactly(500L);
        // Only the new address was inserted, tagged as entered manually in a campaign.
        verify(recipientRepository).saveAll(argThat((List<Recipient> list) ->
                list.size() == 1
                        && list.get(0).getEmail().equals("new@example.com")
                        && "CAMPAIGN_MANUAL".equals(list.get(0).getImportSource())));
    }

    @Test
    void bulk_normalises_invisible_characters_and_reuses_the_saved_contact() {
        Recipient grace = Recipient.builder().id(42L).email("grace@example.com").build();
        when(recipientRepository.findByEmailIn(List.of("grace@example.com"))).thenReturn(List.of(grace));
        when(recipientRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        BulkRecipientResponse res = importService.bulkCreateOrReuse(new BulkRecipientRequest(List.of(
                new BulkRecipientRequest.Row("\u00A0Grace@Example.com\u200B", null, null, null, null, null),
                new BulkRecipientRequest.Row("\uFEFFgrace@example.com ", null, null, null, null, null)
        ), "UPLOAD"));

        assertThat(res.created()).isZero();
        assertThat(res.reused()).isEqualTo(1);
        assertThat(res.skippedInvalid()).isZero();
        assertThat(res.recipientIds()).containsExactly(42L);
        verify(recipientRepository).saveAll(argThat((List<Recipient> list) -> list.isEmpty()));
    }

    // ── Legacy import (Recipients page) ─────────────────────────────────────────

    @Test
    void legacy_csv_import_skips_repeats_within_the_file_instead_of_failing() throws Exception {
        // Two spellings of one address: inserting both would break the unique email column.
        when(recipientRepository.findExistingEmails(List.of("ada@example.com", "grace@example.com")))
                .thenReturn(List.of("grace@example.com"));

        ImportResult result = importService.importCsv(csv("contacts.csv",
                ("email,name\nada@example.com,Ada\n ADA@Example.com\u00A0,Ada again\n"
                        + "grace@example.com,Grace\n").getBytes(StandardCharsets.UTF_8)));

        assertThat(result.totalRows()).isEqualTo(3);
        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.skippedDuplicates()).isEqualTo(2);
        assertThat(result.invalidRows()).isZero();
        verify(recipientRepository).saveAll(argThat((List<Recipient> list) ->
                list.size() == 1 && list.get(0).getEmail().equals("ada@example.com")));
    }

    // ── Template generation ──────────────────────────────────────────────────────

    @Test
    void template_contains_canonical_headers_and_examples() throws IOException {
        // Exactly the columns of the campaign form's "Add emails manually" table:
        // TABLE_COLUMNS in frontend/src/lib/manualTable.ts (manualTable.test.ts asserts the same list).
        assertThat(RecipientImportService.TEMPLATE_HEADERS).containsExactly("Email", "Name", "Company");

        byte[] bytes = importService.buildExcelTemplate();
        assertThat(bytes).isNotEmpty();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            header.forEach(c -> headers.add(c.getStringCellValue()));

            assertThat(headers).containsExactly("Email", "Name", "Company");
            // 3 example rows, one value per column, all at example.com
            assertThat(sheet.getLastRowNum()).isEqualTo(3);
            for (int r = 1; r <= 3; r++) {
                Row row = sheet.getRow(r);
                assertThat(row.getLastCellNum()).isEqualTo((short) 3);
                assertThat(row.getCell(0).getStringCellValue()).endsWith("@example.com");
                assertThat(row.getCell(1).getStringCellValue()).isNotBlank();
                assertThat(row.getCell(2).getStringCellValue()).isNotBlank();
            }
        }
    }

    @Test
    void the_template_columns_are_the_frontend_table_columns() throws IOException {
        // The other side of the same check: the table in the campaign form lists the template's columns.
        Path source = Path.of("frontend", "src", "lib", "manualTable.ts");
        assumeTrue(Files.exists(source), "frontend sources are not checked out next to the backend");
        String ts = Files.readString(source, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("export const TABLE_COLUMNS = \\[([^\\]]*)\\] as const;").matcher(ts);
        assertThat(m.find()).as("TABLE_COLUMNS in %s", source).isTrue();
        List<String> columns = new ArrayList<>();
        Matcher item = Pattern.compile("'([^']*)'").matcher(m.group(1));
        while (item.find()) columns.add(item.group(1));
        assertThat(columns).containsExactlyElementsOf(RecipientImportService.TEMPLATE_HEADERS);
    }
}
