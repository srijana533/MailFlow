package com.mailflow.recipient.service;

import com.mailflow.common.util.EmailValidator;
import com.mailflow.recipient.dto.BulkRecipientRequest;
import com.mailflow.recipient.dto.BulkRecipientResponse;
import com.mailflow.recipient.dto.ExcelPreviewResponse;
import com.mailflow.recipient.dto.ExcelRowPreview;
import com.mailflow.recipient.dto.ImportResult;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.repository.RecipientRepository;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Parses and bulk-imports recipients from CSV or Excel files.
 *
 * <p>CSV expected header row:
 * {@code email,name,firstName,lastName,company,course,code,...}
 *
 * <p>Duplicate emails are skipped. Invalid email formats are reported.
 * All valid records are batch-saved.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipientImportService {

    private static final int BATCH_SIZE = 500;

    private final RecipientRepository recipientRepository;

    @Transactional
    public ImportResult importCsv(MultipartFile file) throws IOException, CsvValidationException {
        List<String> errors = new ArrayList<>();
        List<Recipient> toSave = new ArrayList<>();
        int totalRows = 0;
        int skippedDuplicates = 0;
        int invalidRows = 0;

        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String[] header = reader.readNext(); // skip header
            if (header == null) {
                return new ImportResult(0, 0, 0, 0, List.of("File is empty"));
            }

            Map<String, Integer> colIndex = buildColumnIndex(header);
            String[] line;

            while ((line = reader.readNext()) != null) {
                totalRows++;
                try {
                    Recipient r = parseCsvLine(line, colIndex, totalRows);
                    if (r != null) toSave.add(r);
                } catch (InvalidRowException e) {
                    invalidRows++;
                    errors.add("Row " + totalRows + ": " + e.getMessage());
                }
            }
        }

        // Deduplicate against DB
        List<String> existingEmails = recipientRepository.findExistingEmails(
            toSave.stream().map(Recipient::getEmail).toList()
        );
        Set<String> existingSet = new HashSet<>(existingEmails);

        List<Recipient> newRecipients = toSave.stream()
                .filter(r -> !existingSet.contains(r.getEmail().toLowerCase()))
                .toList();
        skippedDuplicates = toSave.size() - newRecipients.size();

        // Batch insert in chunks
        int saved = batchSave(newRecipients);
        log.info("CSV import: total={}, saved={}, duplicates={}, invalid={}",
                 totalRows, saved, skippedDuplicates, invalidRows);

        return new ImportResult(totalRows, saved, skippedDuplicates, invalidRows, errors);
    }

    @Transactional
    public ImportResult importExcel(MultipartFile file) throws IOException {
        List<String> errors = new ArrayList<>();
        List<Recipient> toSave = new ArrayList<>();
        int totalRows = 0;
        int invalidRows = 0;

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                return new ImportResult(0, 0, 0, 0, List.of("Sheet is empty"));
            }

            Map<String, Integer> colIndex = buildExcelColumnIndex(headerRow);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                totalRows++;
                try {
                    Recipient r = parseExcelRow(row, colIndex, i + 1);
                    if (r != null) toSave.add(r);
                } catch (InvalidRowException e) {
                    invalidRows++;
                    errors.add("Row " + (i + 1) + ": " + e.getMessage());
                }
            }
        }

        List<String> existingEmails = recipientRepository.findExistingEmails(
            toSave.stream().map(Recipient::getEmail).toList()
        );
        Set<String> existingSet = new HashSet<>(existingEmails);
        List<Recipient> newRecipients = toSave.stream()
                .filter(r -> !existingSet.contains(r.getEmail().toLowerCase()))
                .toList();
        int skippedDuplicates = toSave.size() - newRecipients.size();

        int saved = batchSave(newRecipients);
        return new ImportResult(totalRows, saved, skippedDuplicates, invalidRows, errors);
    }

    // ── Parsing helpers ──────────────────────────────────────────────────────

    private Recipient parseCsvLine(String[] line, Map<String, Integer> idx, int rowNum)
            throws InvalidRowException {
        String email = getField(line, idx, "email", "");
        if (!EmailValidator.isValid(email)) {
            throw new InvalidRowException("Invalid or missing email: '" + email + "'");
        }

        Map<String, String> customFields = new HashMap<>();
        String code = getField(line, idx, "code", null);
        if (code != null) customFields.put("code", code);

        return Recipient.builder()
                .email(email.trim().toLowerCase())
                .name(getField(line, idx, "name", null))
                .firstName(getField(line, idx, "firstName", null))
                .lastName(getField(line, idx, "lastName", null))
                .company(getField(line, idx, "company", null))
                .course(getField(line, idx, "course", null))
                .customFields(customFields)
                .status(RecipientStatus.ACTIVE)
                .importSource("CSV_UPLOAD")
                .build();
    }

    private Recipient parseExcelRow(Row row, Map<String, Integer> idx, int rowNum)
            throws InvalidRowException {
        String email = getExcelCell(row, idx, "email", "");
        if (!EmailValidator.isValid(email)) {
            throw new InvalidRowException("Invalid or missing email: '" + email + "'");
        }

        Map<String, String> customFields = new HashMap<>();
        String code = getExcelCell(row, idx, "code", null);
        if (code != null) customFields.put("code", code);

        return Recipient.builder()
                .email(email.trim().toLowerCase())
                .name(getExcelCell(row, idx, "name", null))
                .firstName(getExcelCell(row, idx, "firstName", null))
                .lastName(getExcelCell(row, idx, "lastName", null))
                .company(getExcelCell(row, idx, "company", null))
                .course(getExcelCell(row, idx, "course", null))
                .customFields(customFields)
                .status(RecipientStatus.ACTIVE)
                .importSource("EXCEL_UPLOAD")
                .build();
    }

    private Map<String, Integer> buildColumnIndex(String[] header) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < header.length; i++) {
            idx.put(header[i].trim().toLowerCase(), i);
        }
        return idx;
    }

    private Map<String, Integer> buildExcelColumnIndex(Row header) {
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < header.getLastCellNum(); i++) {
            Cell cell = header.getCell(i);
            if (cell != null) {
                idx.put(cell.getStringCellValue().trim().toLowerCase(), i);
            }
        }
        return idx;
    }

    private String getField(String[] line, Map<String, Integer> idx, String col, String defaultVal) {
        // Try exact then case-insensitive
        Integer i = idx.get(col.toLowerCase());
        if (i == null || i >= line.length) return defaultVal;
        String val = line[i].trim();
        return val.isEmpty() ? defaultVal : val;
    }

    private String getExcelCell(Row row, Map<String, Integer> idx, String col, String defaultVal) {
        Integer i = idx.get(col.toLowerCase());
        if (i == null) return defaultVal;
        Cell cell = row.getCell(i);
        if (cell == null) return defaultVal;
        String val = switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default      -> "";
        };
        return val.isEmpty() ? defaultVal : val;
    }

    private int batchSave(List<Recipient> recipients) {
        int count = 0;
        for (int i = 0; i < recipients.size(); i += BATCH_SIZE) {
            List<Recipient> chunk = recipients.subList(i, Math.min(i + BATCH_SIZE, recipients.size()));
            recipientRepository.saveAll(chunk);
            count += chunk.size();
        }
        return count;
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Bulk-from-Excel feature: header aliasing, validate-only preview,
    //  create-or-reuse, and template generation. These do NOT alter the
    //  persist-immediately importCsv/importExcel paths above.
    // ══════════════════════════════════════════════════════════════════════════

    /** Max rows accepted in a single upload (safety limit). */
    private static final int MAX_ROWS = 10_000;

    /** Standard target fields (stored as columns on Recipient). */
    private static final String F_EMAIL      = "email";
    private static final String F_NAME       = "name";
    private static final String F_FIRST_NAME = "firstName";
    private static final String F_LAST_NAME  = "lastName";
    private static final String F_COMPANY    = "company";

    /**
     * Case-insensitive, punctuation-tolerant header aliases.
     * Keys are normalised (lowercased, non-alphanumerics stripped).
     * Value is the canonical target field; anything not here but present
     * is kept as a custom field under its original (trimmed) header name.
     */
    private static final Map<String, String> HEADER_ALIASES = buildHeaderAliases();

    /** Extra columns we explicitly recognise and store in customFields. */
    private static final Set<String> KNOWN_CUSTOM_FIELDS =
            Set.of("role", "phone", "location", "interest", "course", "code", "department");

    private static Map<String, String> buildHeaderAliases() {
        Map<String, String> m = new HashMap<>();
        // email
        m.put("email", F_EMAIL);
        m.put("emailaddress", F_EMAIL);
        m.put("mail", F_EMAIL);
        m.put("e", F_EMAIL);
        // name
        m.put("name", F_NAME);
        m.put("fullname", F_NAME);
        // firstName
        m.put("firstname", F_FIRST_NAME);
        m.put("fname", F_FIRST_NAME);
        m.put("givenname", F_FIRST_NAME);
        // lastName
        m.put("lastname", F_LAST_NAME);
        m.put("lname", F_LAST_NAME);
        m.put("surname", F_LAST_NAME);
        m.put("familyname", F_LAST_NAME);
        // company
        m.put("company", F_COMPANY);
        m.put("organization", F_COMPANY);
        m.put("organisation", F_COMPANY);
        m.put("employer", F_COMPANY);
        return m;
    }

    /** Normalise a header for alias matching: lowercase, strip non-alphanumerics. */
    private static String normalizeHeader(String raw) {
        if (raw == null) return "";
        return raw.trim().toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    // ── Preview (validate only, no persist) ────────────────────────────────────

    /**
     * Parses an Excel file and validates each row WITHOUT persisting anything.
     * Detects invalid emails, in-file duplicates, and duplicates already in the DB.
     */
    @Transactional(readOnly = true)
    public ExcelPreviewResponse previewExcel(MultipartFile file) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);
            Row headerRow = sheet == null ? null : sheet.getRow(0);
            if (headerRow == null) {
                return emptyPreview(file.getOriginalFilename());
            }

            // header name (original, trimmed) -> column index
            LinkedHashMap<String, Integer> headerCols = new LinkedHashMap<>();
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                Cell cell = headerRow.getCell(i);
                if (cell == null) continue;
                String name = cellToString(cell).trim();
                if (!name.isEmpty()) headerCols.put(name, i);
            }

            RawRows raw = new RawRows();
            for (int i = 1; i <= sheet.getLastRowNum() && raw.rows.size() < MAX_ROWS; i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row, headerCols)) continue;
                Map<String, String> values = new LinkedHashMap<>();
                for (var e : headerCols.entrySet()) {
                    String v = getExcelCellByIndex(row, e.getValue());
                    if (v != null && !v.isBlank()) values.put(e.getKey(), v.trim());
                }
                raw.rows.add(new RawRow(i + 1, values));
            }

            return buildPreview(file.getOriginalFilename(), headerCols.keySet(), raw);
        }
    }

    /** CSV equivalent of {@link #previewExcel}. */
    @Transactional(readOnly = true)
    public ExcelPreviewResponse previewCsv(MultipartFile file)
            throws IOException, CsvValidationException {
        try (CSVReader reader = new CSVReader(
                new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {

            String[] header = reader.readNext();
            if (header == null) {
                return emptyPreview(file.getOriginalFilename());
            }

            LinkedHashMap<String, Integer> headerCols = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) {
                String name = header[i] == null ? "" : header[i].trim();
                if (!name.isEmpty()) headerCols.put(name, i);
            }

            RawRows raw = new RawRows();
            String[] line;
            int rowNum = 1;
            while ((line = reader.readNext()) != null && raw.rows.size() < MAX_ROWS) {
                rowNum++;
                if (isBlankCsvLine(line)) continue;
                Map<String, String> values = new LinkedHashMap<>();
                for (var e : headerCols.entrySet()) {
                    int idx = e.getValue();
                    if (idx < line.length && line[idx] != null && !line[idx].isBlank()) {
                        values.put(e.getKey(), line[idx].trim());
                    }
                }
                raw.rows.add(new RawRow(rowNum, values));
            }

            return buildPreview(file.getOriginalFilename(), headerCols.keySet(), raw);
        }
    }

    private ExcelPreviewResponse buildPreview(
            String fileName, Set<String> headers, RawRows raw) {

        // Map headers -> target fields
        Map<String, String> detected = new LinkedHashMap<>();   // header -> target
        List<String> unmapped = new ArrayList<>();
        boolean hasEmail = false;
        for (String h : headers) {
            String target = HEADER_ALIASES.get(normalizeHeader(h));
            if (target != null) {
                detected.put(h, target);
                if (F_EMAIL.equals(target)) hasEmail = true;
            } else if (KNOWN_CUSTOM_FIELDS.contains(normalizeHeader(h))) {
                detected.put(h, "custom:" + normalizeHeader(h));
            } else {
                unmapped.add(h);
            }
        }

        // Find the header that maps to email (if any)
        String emailHeader = detected.entrySet().stream()
                .filter(e -> F_EMAIL.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);

        // Collect candidate emails to check DB duplicates in one query
        List<String> candidateEmails = new ArrayList<>();
        if (emailHeader != null) {
            for (RawRow r : raw.rows) {
                String em = r.values.get(emailHeader);
                if (em != null && EmailValidator.isValid(em)) {
                    candidateEmails.add(em.trim().toLowerCase());
                }
            }
        }
        Set<String> dbEmails = candidateEmails.isEmpty()
                ? Set.of()
                : new HashSet<>(recipientRepository.findExistingEmails(candidateEmails));

        Set<String> seenInFile = new HashSet<>();
        List<ExcelRowPreview> previews = new ArrayList<>(raw.rows.size());
        int valid = 0, invalid = 0, duplicate = 0;

        for (RawRow r : raw.rows) {
            Map<String, String> mapped = mapRowFields(r.values, detected);
            String email = mapped.get(F_EMAIL);
            String name = mapped.getOrDefault(F_NAME, "");
            String company = mapped.getOrDefault(F_COMPANY, "");

            String status;
            String reason = null;
            if (!hasEmail) {
                status = ExcelRowPreview.INVALID;
                reason = "No email column detected in file";
                invalid++;
            } else if (email == null || !EmailValidator.isValid(email)) {
                status = ExcelRowPreview.INVALID;
                reason = "Invalid or missing email";
                invalid++;
            } else {
                String lc = email.trim().toLowerCase();
                if (!seenInFile.add(lc)) {
                    status = ExcelRowPreview.DUPLICATE;
                    reason = "Duplicate email within file";
                    duplicate++;
                } else if (dbEmails.contains(lc)) {
                    status = ExcelRowPreview.DUPLICATE;
                    reason = "Email already exists";
                    duplicate++;
                } else {
                    status = ExcelRowPreview.VALID;
                    valid++;
                }
            }

            previews.add(new ExcelRowPreview(
                    r.rowNumber, email, name, company, status, reason, mapped));
        }

        return new ExcelPreviewResponse(
                fileName,
                raw.rows.size(),
                valid,
                invalid,
                duplicate,
                detected,
                unmapped,
                hasEmail,
                previews);
    }

    /**
     * Converts raw header->value map into canonical field keys plus a merged
     * set that also contains the custom fields (role, phone, location, …).
     * The returned map is what the frontend echoes back in the bulk request.
     */
    private Map<String, String> mapRowFields(
            Map<String, String> rawValues, Map<String, String> detected) {
        Map<String, String> out = new LinkedHashMap<>();
        for (var e : rawValues.entrySet()) {
            String target = detected.get(e.getKey());
            String value = e.getValue();
            if (target == null) {
                continue; // unmapped column — ignored for sending
            }
            if (target.startsWith("custom:")) {
                out.put(target.substring("custom:".length()), value);
            } else {
                out.put(target, value);
            }
        }
        return out;
    }

    private ExcelPreviewResponse emptyPreview(String fileName) {
        return new ExcelPreviewResponse(
                fileName, 0, 0, 0, 0, Map.of(), List.of(), false, List.of());
    }

    // ── Bulk create-or-reuse (persist) ─────────────────────────────────────────

    /**
     * Creates new recipients and reuses existing ones (matched by email),
     * returning the full set of recipient IDs to associate with a campaign.
     * Invalid-email rows are skipped. Runs in a single transaction.
     */
    @Transactional
    public BulkRecipientResponse bulkCreateOrReuse(BulkRecipientRequest request) {
        // Normalise + de-duplicate incoming rows by email
        LinkedHashMap<String, BulkRecipientRequest.Row> byEmail = new LinkedHashMap<>();
        int skippedInvalid = 0;
        for (BulkRecipientRequest.Row row : request.rows()) {
            String email = row.email() == null ? "" : row.email().trim().toLowerCase();
            if (!EmailValidator.isValid(email)) {
                skippedInvalid++;
                continue;
            }
            byEmail.putIfAbsent(email, row); // first wins on in-request dupes
        }

        if (byEmail.isEmpty()) {
            return new BulkRecipientResponse(0, 0, skippedInvalid, List.of());
        }

        List<String> emails = new ArrayList<>(byEmail.keySet());
        Map<String, Recipient> existing = new HashMap<>();
        for (Recipient r : recipientRepository.findByEmailIn(emails)) {
            existing.put(r.getEmail().toLowerCase(), r);
        }

        List<Recipient> toCreate = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        int reused = 0;

        for (var e : byEmail.entrySet()) {
            String email = e.getKey();
            Recipient found = existing.get(email);
            if (found != null) {
                ids.add(found.getId());
                reused++;
            } else {
                toCreate.add(toRecipient(email, e.getValue()));
            }
        }

        List<Recipient> saved = recipientRepository.saveAll(toCreate);
        for (Recipient r : saved) ids.add(r.getId());

        log.info("Bulk recipients: created={}, reused={}, skippedInvalid={}",
                saved.size(), reused, skippedInvalid);
        return new BulkRecipientResponse(saved.size(), reused, skippedInvalid, ids);
    }

    private Recipient toRecipient(String email, BulkRecipientRequest.Row row) {
        Map<String, String> custom = new HashMap<>();
        if (row.fields() != null) {
            for (var e : row.fields().entrySet()) {
                if (e.getValue() != null && !e.getValue().isBlank()) {
                    custom.put(e.getKey(), e.getValue().trim());
                }
            }
        }
        return Recipient.builder()
                .email(email)
                .name(blankToNull(row.name()))
                .firstName(blankToNull(row.firstName()))
                .lastName(blankToNull(row.lastName()))
                .company(blankToNull(row.company()))
                .customFields(custom)
                .status(RecipientStatus.ACTIVE)
                .importSource("EXCEL_BULK")
                .build();
    }

    // ── Template generation ─────────────────────────────────────────────────────

    /** Canonical template headers, in order. */
    public static final List<String> TEMPLATE_HEADERS = List.of(
            "Email", "Name", "FirstName", "LastName",
            "Company", "Role", "Phone", "Location", "Interest");

    /**
     * Builds a downloadable .xlsx template with the canonical headers and a
     * few example rows demonstrating personalization fields.
     */
    public byte[] buildExcelTemplate() throws IOException {
        try (Workbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Sheet sheet = wb.createSheet("Recipients");

            // Header style: bold
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(bold);

            Row header = sheet.createRow(0);
            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                Cell c = header.createCell(i);
                c.setCellValue(TEMPLATE_HEADERS.get(i));
                c.setCellStyle(headerStyle);
            }

            String[][] examples = {
                {"ada@example.com",   "Ada Lovelace", "Ada",   "Lovelace",
                 "Analytical Engines", "Engineer",   "+1-555-0100", "London",  "Mathematics"},
                {"alan@example.com",  "Alan Turing",  "Alan",  "Turing",
                 "Bletchley Park",     "Researcher", "+1-555-0101", "Manchester", "Computing"},
                {"grace@example.com", "Grace Hopper", "Grace", "Hopper",
                 "US Navy",            "Admiral",    "+1-555-0102", "Arlington",  "Compilers"},
            };
            for (int r = 0; r < examples.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < examples[r].length; c++) {
                    row.createCell(c).setCellValue(examples[r][c]);
                }
            }

            for (int i = 0; i < TEMPLATE_HEADERS.size(); i++) {
                sheet.autoSizeColumn(i);
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    // ── Small helpers ───────────────────────────────────────────────────────────

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    private String getExcelCellByIndex(Row row, int idx) {
        Cell cell = row.getCell(idx);
        return cell == null ? null : cellToString(cell);
    }

    private String cellToString(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                    ? cell.getDateCellValue().toString()
                    : stripTrailingZero(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                try { yield cell.getStringCellValue().trim(); }
                catch (Exception ex) { yield stripTrailingZero(cell.getNumericCellValue()); }
            }
            default -> "";
        };
    }

    private String stripTrailingZero(double d) {
        if (d == Math.floor(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    private boolean isBlankRow(Row row, Map<String, Integer> headerCols) {
        for (int idx : headerCols.values()) {
            Cell c = row.getCell(idx);
            if (c != null && !cellToString(c).isBlank()) return false;
        }
        return true;
    }

    private boolean isBlankCsvLine(String[] line) {
        for (String s : line) {
            if (s != null && !s.isBlank()) return false;
        }
        return true;
    }

    /** Lightweight carriers for pre-validation parsing. */
    private static final class RawRows {
        final List<RawRow> rows = new ArrayList<>();
    }
    private record RawRow(int rowNumber, Map<String, String> values) {}

    // ── Inner exception ──────────────────────────────────────────────────────
    private static class InvalidRowException extends Exception {
        InvalidRowException(String msg) { super(msg); }
    }
}
