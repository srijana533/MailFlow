package com.mailflow.recipient;

import com.mailflow.recipient.dto.BulkRecipientRequest;
import com.mailflow.recipient.dto.BulkRecipientResponse;
import com.mailflow.recipient.dto.ExcelPreviewResponse;
import com.mailflow.recipient.dto.ExcelRowPreview;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.repository.RecipientRepository;
import com.mailflow.recipient.service.RecipientImportService;
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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
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
    @InjectMocks private RecipientImportService importService;

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Builds an in-memory .xlsx with the given header row and data rows. */
    private MockMultipartFile excel(String name, String[] headers, String[][] rows) throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
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
            return new MockMultipartFile(
                    "file", name,
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    out.toByteArray());
        }
    }

    // ── Preview: valid / invalid / duplicate detection ─────────────────────────

    @Test
    void preview_flags_valid_invalid_and_in_file_duplicates() throws IOException {
        when(recipientRepository.findExistingEmails(anyList())).thenReturn(List.of());

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
        assertThat(res.invalidRows()).isEqualTo(1);
        assertThat(res.duplicateRows()).isEqualTo(1);
        assertThat(res.hasEmailColumn()).isTrue();
        assertThat(res.rows()).hasSize(4);

        assertThat(res.rows().get(1).status()).isEqualTo(ExcelRowPreview.INVALID);
        assertThat(res.rows().get(2).status()).isEqualTo(ExcelRowPreview.DUPLICATE);
    }

    @Test
    void preview_marks_db_duplicates() throws IOException {
        // grace already exists in the DB
        when(recipientRepository.findExistingEmails(anyList()))
                .thenReturn(List.of("grace@example.com"));

        MockMultipartFile file = excel(
                "contacts.xlsx",
                new String[] {"Email", "Name"},
                new String[][] {
                        {"ada@example.com",   "Ada"},
                        {"grace@example.com", "Grace"},
                });

        ExcelPreviewResponse res = importService.previewExcel(file);

        assertThat(res.validRows()).isEqualTo(1);
        assertThat(res.duplicateRows()).isEqualTo(1);
        ExcelRowPreview graceRow = res.rows().stream()
                .filter(r -> "grace@example.com".equals(r.email()))
                .findFirst().orElseThrow();
        assertThat(graceRow.status()).isEqualTo(ExcelRowPreview.DUPLICATE);
        assertThat(graceRow.reason()).containsIgnoringCase("exists");
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
    }

    @Test
    void preview_maps_case_insensitive_and_aliased_headers() throws IOException {
        when(recipientRepository.findExistingEmails(anyList())).thenReturn(List.of());

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
        ));

        BulkRecipientResponse res = importService.bulkCreateOrReuse(req);

        assertThat(res.created()).isEqualTo(1);          // ada
        assertThat(res.reused()).isEqualTo(1);           // grace
        assertThat(res.skippedInvalid()).isEqualTo(1);   // not-an-email
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
        ));

        BulkRecipientResponse res = importService.bulkCreateOrReuse(req);

        // Same email (case-insensitive) collapses to one created recipient
        assertThat(res.created()).isEqualTo(1);
        assertThat(res.recipientIds()).hasSize(1);
    }

    // ── Template generation ──────────────────────────────────────────────────────

    @Test
    void template_contains_canonical_headers_and_examples() throws IOException {
        byte[] bytes = importService.buildExcelTemplate();
        assertThat(bytes).isNotEmpty();

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            List<String> headers = new ArrayList<>();
            header.forEach(c -> headers.add(c.getStringCellValue()));

            assertThat(headers).containsExactlyElementsOf(RecipientImportService.TEMPLATE_HEADERS);
            // 3 example rows present
            assertThat(sheet.getLastRowNum()).isEqualTo(3);
            assertThat(sheet.getRow(1).getCell(0).getStringCellValue()).contains("@");
        }
    }
}
