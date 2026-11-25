package com.mailflow.recipient.service;

import com.mailflow.common.exception.MailFlowException;
import com.mailflow.common.exception.ValidationException;
import com.mailflow.common.util.EmailValidator;
import com.mailflow.email.repository.UnsubscribeRepository;
import com.mailflow.recipient.dto.BulkRecipientRequest;
import com.mailflow.recipient.dto.BulkRecipientResponse;
import com.mailflow.recipient.dto.ExcelPreviewResponse;
import com.mailflow.recipient.dto.ExcelRowPreview;
import com.mailflow.recipient.dto.ImportResult;
import com.mailflow.recipient.entity.Recipient;
import com.mailflow.recipient.entity.RecipientStatus;
import com.mailflow.recipient.repository.RecipientRepository;
import com.opencsv.CSVParser;
import com.opencsv.CSVParserBuilder;
import com.opencsv.CSVReader;
import com.opencsv.CSVReaderBuilder;
import com.opencsv.exceptions.CsvException;
import com.opencsv.exceptions.CsvMalformedLineException;
import com.opencsv.exceptions.CsvValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
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
 *
 * <p>Every address is normalised with {@link EmailValidator#normalize} (trimmed,
 * including invisible characters, and lower-cased) before it is validated, compared,
 * looked up or stored. Whether an address was used by an earlier campaign is never
 * consulted: a saved contact can be in any number of campaigns.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecipientImportService {

    private static final int BATCH_SIZE = 500;

    private final RecipientRepository recipientRepository;
    private final UnsubscribeRepository unsubscribeRepository;

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

        // Deduplicate within the file and against the DB
        List<Recipient> newRecipients = notYetSaved(toSave);
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

        List<Recipient> newRecipients = notYetSaved(toSave);
        int skippedDuplicates = toSave.size() - newRecipients.size();

        int saved = batchSave(newRecipients);
        return new ImportResult(totalRows, saved, skippedDuplicates, invalidRows, errors);
    }

    /**
     * The parsed contacts to insert: the first row of each (normalised) address, minus
     * addresses that already belong to a saved contact. Without the in-file step, two
     * rows of one address would both be inserted and break the unique email column,
     * failing the whole import.
     */
    private List<Recipient> notYetSaved(List<Recipient> parsed) {
        Map<String, Recipient> firstPerEmail = new LinkedHashMap<>();
        for (Recipient r : parsed) firstPerEmail.putIfAbsent(r.getEmail(), r);
        if (firstPerEmail.isEmpty()) return List.of();

        Set<String> existingSet = new HashSet<>();
        for (String e : recipientRepository.findExistingEmails(new ArrayList<>(firstPerEmail.keySet()))) {
            existingSet.add(EmailValidator.normalize(e));
        }
        return firstPerEmail.values().stream()
                .filter(r -> !existingSet.contains(r.getEmail()))
                .toList();
    }

    // ── Parsing helpers ──────────────────────────────────────────────────────

    private Recipient parseCsvLine(String[] line, Map<String, Integer> idx, int rowNum)
            throws InvalidRowException {
        String raw = getField(line, idx, "email", "");
        String email = EmailValidator.normalize(raw);
        if (!EmailValidator.isValid(email)) {
            throw new InvalidRowException("Invalid or missing email: '" + raw + "'");
        }

        Map<String, String> customFields = new HashMap<>();
        String code = getField(line, idx, "code", null);
        if (code != null) customFields.put("code", code);

        return Recipient.builder()
                .email(email)
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
        String raw = getExcelCell(row, idx, "email", "");
        String email = EmailValidator.normalize(raw);
        if (!EmailValidator.isValid(email)) {
            throw new InvalidRowException("Invalid or missing email: '" + raw + "'");
        }

        Map<String, String> customFields = new HashMap<>();
        String code = getExcelCell(row, idx, "code", null);
        if (code != null) customFields.put("code", code);

        return Recipient.builder()
                .email(email)
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

    /** Max data rows accepted in a single upload. Larger files are rejected, never truncated. */
    public static final int MAX_ROWS = 10_000;

    /**
     * Max upload size for the preview. Spreadsheets are parsed in memory, so this
     * bounds memory use; 10,000 recipient rows fit comfortably below it.
     */
    public static final long MAX_PREVIEW_BYTES = 5L * 1024 * 1024;

    /** Mapping value meaning "do not use this column". */
    public static final String MAPPING_IGNORE = "ignore";

    private static final String CUSTOM_PREFIX = "custom:";

    /** Custom field keys must be usable as {{placeholders}}: a letter, then letters/digits/_. */
    private static final java.util.regex.Pattern CUSTOM_KEY =
            java.util.regex.Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,39}$");

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

    /** File formats accepted by the preview. */
    private enum UploadFormat { WORKBOOK, CSV }

    /**
     * Validates an uploaded Excel/CSV file WITHOUT persisting anything.
     *
     * <p>Accepts .xlsx, .xls and .csv files up to {@link #MAX_PREVIEW_BYTES} with at
     * most {@link #MAX_ROWS} data rows. Unsupported, empty, oversized, malformed or
     * password-protected files are rejected with a {@link ValidationException}
     * (HTTP 400) and a readable message, never an internal error.
     *
     * @param mapping optional header -> target overrides; see {@link #resolveColumnMapping}.
     */
    @Transactional(readOnly = true)
    public ExcelPreviewResponse previewUpload(MultipartFile file, Map<String, String> mapping) {
        return preview(file, mapping, detectFormat(file));
    }

    /** Previews a workbook (.xlsx or .xls) using automatic column detection. */
    @Transactional(readOnly = true)
    public ExcelPreviewResponse previewExcel(MultipartFile file) {
        return preview(file, null, UploadFormat.WORKBOOK);
    }

    /** Previews a CSV file using automatic column detection. */
    @Transactional(readOnly = true)
    public ExcelPreviewResponse previewCsv(MultipartFile file) {
        return preview(file, null, UploadFormat.CSV);
    }

    private UploadFormat detectFormat(MultipartFile file) {
        String name = (file == null || file.getOriginalFilename() == null)
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) return UploadFormat.CSV;
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) return UploadFormat.WORKBOOK;
        throw new ValidationException("Unsupported file type. Upload an .xlsx, .xls or .csv file.");
    }

    private ExcelPreviewResponse preview(MultipartFile file, Map<String, String> mapping,
                                         UploadFormat format) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("The uploaded file is empty.");
        }
        if (file.getSize() > MAX_PREVIEW_BYTES) {
            throw new ValidationException("The file is larger than 5 MB. Upload a smaller file (at most "
                    + String.format(Locale.US, "%,d", MAX_ROWS) + " rows).");
        }

        ParsedUpload parsed;
        try {
            parsed = format == UploadFormat.CSV ? parseCsv(file) : parseWorkbook(file);
        } catch (MailFlowException e) {
            throw e; // our own messages, e.g. the row limit
        } catch (EncryptedDocumentException e) {
            throw new ValidationException(
                    "This file is password-protected. Remove the password and upload it again.");
        } catch (CsvMalformedLineException e) {
            throw new ValidationException("Line " + e.getLineNumber()
                    + " of this CSV file is malformed (for example an unclosed quote). Fix it and upload again.");
        } catch (CsvException e) {
            throw new ValidationException("Line " + e.getLineNumber()
                    + " of this CSV file couldn't be read. Fix it and upload again.");
        } catch (IOException | RuntimeException e) {
            // Untrusted input: report a safe message, keep the detail in the log.
            log.warn("Rejected unreadable upload '{}': {}", file.getOriginalFilename(), e.toString());
            throw new ValidationException(
                    "This file couldn't be read. Make sure it is a valid .xlsx, .xls or .csv file.");
        }
        return buildPreview(file.getOriginalFilename(), parsed.headers(), parsed.rows(), mapping);
    }

    /** Reads the first sheet of an .xlsx or .xls workbook (format detected from content). */
    private ParsedUpload parseWorkbook(MultipartFile file) throws IOException {
        try (InputStream in = file.getInputStream();
             Workbook workbook = WorkbookFactory.create(in)) {
            if (workbook.getNumberOfSheets() == 0) return ParsedUpload.EMPTY;
            Sheet sheet = workbook.getSheetAt(0);
            int headerIdx = Math.max(0, sheet.getFirstRowNum());
            Row headerRow = sheet.getRow(headerIdx);
            if (headerRow == null) return ParsedUpload.EMPTY;

            // header name (original, trimmed, made unique) -> column index
            LinkedHashMap<String, Integer> headerCols = new LinkedHashMap<>();
            for (int i = 0; i < headerRow.getLastCellNum(); i++) {
                Cell cell = headerRow.getCell(i);
                if (cell != null) addHeader(headerCols, cellToString(cell), i);
            }
            if (headerCols.isEmpty()) return ParsedUpload.EMPTY;

            List<RawRow> rows = new ArrayList<>();
            for (int i = headerIdx + 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlankRow(row, headerCols)) continue;
                if (rows.size() >= MAX_ROWS) throw tooManyRows();
                Map<String, String> values = new LinkedHashMap<>();
                for (var e : headerCols.entrySet()) {
                    String v = getExcelCellByIndex(row, e.getValue());
                    if (v != null && !v.isBlank()) values.put(e.getKey(), v.trim());
                }
                rows.add(new RawRow(i + 1, values));
            }
            return new ParsedUpload(new ArrayList<>(headerCols.keySet()), rows);
        }
    }

    /**
     * Reads a CSV file. Accepts UTF-8 (with or without BOM) and falls back to
     * Windows-1252 for files that aren't valid UTF-8. The separator (comma,
     * semicolon or tab) is detected from the header line.
     */
    private ParsedUpload parseCsv(MultipartFile file) throws IOException, CsvValidationException {
        String text = decodeText(file.getBytes());
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);

        CSVParser parser = new CSVParserBuilder().withSeparator(detectSeparator(text)).build();
        try (CSVReader reader = new CSVReaderBuilder(new StringReader(text))
                .withCSVParser(parser).build()) {

            String[] header = reader.readNext();
            if (header == null) return ParsedUpload.EMPTY;

            LinkedHashMap<String, Integer> headerCols = new LinkedHashMap<>();
            for (int i = 0; i < header.length; i++) addHeader(headerCols, header[i], i);
            if (headerCols.isEmpty()) return ParsedUpload.EMPTY;

            List<RawRow> rows = new ArrayList<>();
            String[] line;
            int rowNum = 1;
            while ((line = reader.readNext()) != null) {
                rowNum++;
                if (isBlankCsvLine(line)) continue;
                if (rows.size() >= MAX_ROWS) throw tooManyRows();
                Map<String, String> values = new LinkedHashMap<>();
                for (var e : headerCols.entrySet()) {
                    int idx = e.getValue();
                    if (idx < line.length && line[idx] != null && !line[idx].isBlank()) {
                        values.put(e.getKey(), line[idx].trim());
                    }
                }
                rows.add(new RawRow(rowNum, values));
            }
            return new ParsedUpload(new ArrayList<>(headerCols.keySet()), rows);
        }
    }

    private static ValidationException tooManyRows() {
        String max = String.format(Locale.US, "%,d", MAX_ROWS);
        return new ValidationException("This file has more than " + max
                + " recipient rows. Upload a file with at most " + max + " rows.");
    }

    /** Adds a header, skipping blanks and making repeated names unique ("Email (2)"). */
    private static void addHeader(LinkedHashMap<String, Integer> headerCols, String raw, int index) {
        String name = raw == null ? "" : raw.replace("\uFEFF", "").trim();
        if (name.isEmpty()) return;
        String unique = name;
        for (int n = 2; headerCols.containsKey(unique); n++) {
            unique = name + " (" + n + ")";
        }
        headerCols.put(unique, index);
    }

    private static String decodeText(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }

    private static char detectSeparator(String text) {
        int end = text.indexOf('\n');
        String first = end >= 0 ? text.substring(0, end) : text;
        long commas = first.chars().filter(c -> c == ',').count();
        long semicolons = first.chars().filter(c -> c == ';').count();
        long tabs = first.chars().filter(c -> c == '\t').count();
        if (semicolons > commas && semicolons >= tabs) return ';';
        if (tabs > commas && tabs > semicolons) return '\t';
        return ',';
    }

    private ExcelPreviewResponse buildPreview(String fileName, List<String> headers,
                                              List<RawRow> rows, Map<String, String> overrides) {

        // header -> target field (standard field or custom:<key>)
        Map<String, String> detected = resolveColumnMapping(headers, overrides);
        List<String> unmapped = headers.stream().filter(h -> !detected.containsKey(h)).toList();
        String emailHeader = detected.entrySet().stream()
                .filter(e -> F_EMAIL.equals(e.getValue()))
                .map(Map.Entry::getKey)
                .findFirst().orElse(null);
        boolean hasEmail = emailHeader != null;

        // The address of each row: "Name <addr>" and "mailto:addr" cells give addr;
        // valid ones are normalised, invalid ones are null.
        Map<Integer, String> addressOfRow = new HashMap<>();
        Set<String> distinct = new LinkedHashSet<>();
        if (hasEmail) {
            for (RawRow r : rows) {
                String address = EmailValidator.normalize(EmailValidator.extractAddress(r.values().get(emailHeader)));
                if (EmailValidator.isValid(address)) {
                    addressOfRow.put(r.rowNumber(), address);
                    distinct.add(address);
                }
            }
        }

        // Saved contacts, deleted contacts and unsubscribed addresses among them (one
        // read-only query each). Earlier campaigns are not consulted: a saved contact can
        // be in any number of campaigns, so being emailed before never makes a row a duplicate.
        List<String> candidates = new ArrayList<>(distinct);
        Map<String, Recipient> saved = new HashMap<>();
        Set<String> deleted = new HashSet<>();
        Set<String> unsubscribed = new HashSet<>();
        if (!candidates.isEmpty()) {
            for (Recipient rec : recipientRepository.findByEmailIn(com.mailflow.tenant.TenantContext.requireOrganizationId(), candidates)) {
                saved.put(EmailValidator.normalize(rec.getEmail()), rec);
            }
            for (String e : recipientRepository.findDeletedEmails(com.mailflow.tenant.TenantContext.requireOrganizationId(), candidates)) {
                deleted.add(EmailValidator.normalize(e));
            }
            for (String e : unsubscribeRepository.findEmailsIn(com.mailflow.tenant.TenantContext.requireOrganizationId(), candidates)) {
                unsubscribed.add(EmailValidator.normalize(e));
            }
        }

        Map<String, Integer> firstSeenOnRow = new HashMap<>();
        List<ExcelRowPreview> previews = new ArrayList<>(rows.size());
        int newRows = 0, existing = 0, invalid = 0, duplicate = 0, skipped = 0;

        for (RawRow r : rows) {
            Map<String, String> mapped = mapRowFields(r.values(), detected);
            String address = addressOfRow.get(r.rowNumber());
            String email = address != null ? address : mapped.get(F_EMAIL);
            if (address != null) mapped.put(F_EMAIL, address);
            String name = mapped.getOrDefault(F_NAME, "");
            String company = mapped.getOrDefault(F_COMPANY, "");

            String status;
            String reason = null;
            Long recipientId = null;
            if (!hasEmail) {
                status = ExcelRowPreview.INVALID;
                reason = "No email column detected in file";
                invalid++;
            } else if (address == null) {
                status = ExcelRowPreview.INVALID;
                reason = email == null || email.isBlank() ? "No email address in this row" : "Not a valid email address";
                invalid++;
            } else {
                Integer firstRow = firstSeenOnRow.putIfAbsent(address, r.rowNumber());
                Recipient rec = saved.get(address);
                if (firstRow != null) {
                    status = ExcelRowPreview.DUPLICATE;
                    reason = "Duplicate email within file (first on row " + firstRow + ")";
                    duplicate++;
                } else if (deleted.contains(address)) {
                    status = ExcelRowPreview.INVALID;
                    reason = "Belongs to a deleted contact, so it can't be added";
                    invalid++;
                } else if (rec != null && rec.getStatus() == RecipientStatus.ACTIVE && !unsubscribed.contains(address)) {
                    status = ExcelRowPreview.EXISTING;
                    recipientId = rec.getId();
                    reason = "Saved contact: their saved details are used";
                    existing++;
                } else if (rec != null || unsubscribed.contains(address)) {
                    status = ExcelRowPreview.SKIPPED;
                    recipientId = rec != null ? rec.getId() : null;
                    reason = skipReason(rec, unsubscribed.contains(address));
                    skipped++;
                } else {
                    status = ExcelRowPreview.VALID;
                    newRows++;
                }
            }

            previews.add(new ExcelRowPreview(
                    r.rowNumber(), email, name, company, status, reason, mapped, recipientId));
        }

        return new ExcelPreviewResponse(
                fileName,
                rows.size(),
                newRows + existing,
                newRows,
                existing,
                invalid,
                duplicate,
                skipped,
                headers,
                detected,
                unmapped,
                hasEmail,
                previews);
    }

    /**
     * Why a valid address won't be emailed, for a saved contact that isn't ACTIVE or an
     * address on the unsubscribe list. It names the compliance reason; being used by
     * another campaign is never one.
     */
    private static String skipReason(Recipient contact, boolean onUnsubscribeList) {
        RecipientStatus status = contact != null ? contact.getStatus() : null;
        if (onUnsubscribeList || status == RecipientStatus.UNSUBSCRIBED) {
            return contact != null
                    ? "Unsubscribed: this saved contact won't be emailed"
                    : "Unsubscribed: this address won't be emailed";
        }
        if (status == RecipientStatus.BOUNCED) {
            return "Bounced: this saved contact's address bounced before and won't be emailed";
        }
        return "Marked invalid: this saved contact won't be emailed";
    }

    /**
     * Decides which column feeds which field.
     *
     * <p>Explicit overrides win: a header mapped to a field takes it, and a header
     * mapped to {@code ignore} is not used. Other headers are auto-detected by alias;
     * when several columns would feed the same field, the first one wins. Overrides
     * for headers that aren't in the file are ignored, so a mapping chosen for one
     * file can be re-sent safely. Two overrides claiming the same field are rejected.
     *
     * <p>Accepted override values: {@code email}, {@code name}, {@code firstName},
     * {@code lastName}, {@code company}, {@code custom:<key>} (key: a letter followed
     * by letters, digits or underscores; usable as {@code {{key}}}) and {@code ignore}.
     */
    private Map<String, String> resolveColumnMapping(List<String> headers, Map<String, String> overrides) {
        Map<String, String> explicit = new LinkedHashMap<>();
        if (overrides != null) {
            for (var e : overrides.entrySet()) {
                if (e.getKey() == null || !headers.contains(e.getKey())) continue;
                explicit.put(e.getKey(), normalizeMappingTarget(e.getValue()));
            }
        }

        Map<String, String> claimedBy = new HashMap<>();
        for (var e : explicit.entrySet()) {
            if (MAPPING_IGNORE.equals(e.getValue())) continue;
            String previous = claimedBy.putIfAbsent(e.getValue(), e.getKey());
            if (previous != null) {
                throw new ValidationException("Columns '" + previous + "' and '" + e.getKey()
                        + "' are both mapped to " + describeTarget(e.getValue())
                        + ". Map each field to one column only.");
            }
        }

        Map<String, String> resolved = new LinkedHashMap<>();
        for (String h : headers) {
            boolean isExplicit = explicit.containsKey(h);
            String target = isExplicit ? explicit.get(h) : autoDetectTarget(h);
            if (target == null || MAPPING_IGNORE.equals(target)) continue;
            if (!isExplicit) {
                if (claimedBy.containsKey(target)) continue; // taken by an override or an earlier column
                claimedBy.put(target, h);
            }
            resolved.put(h, target);
        }
        return resolved;
    }

    /** Alias-based detection: a standard field, a known custom field, or null. */
    private static String autoDetectTarget(String header) {
        String normalized = normalizeHeader(header);
        String standard = HEADER_ALIASES.get(normalized);
        if (standard != null) return standard;
        if (KNOWN_CUSTOM_FIELDS.contains(normalized)) return CUSTOM_PREFIX + normalized;
        return null;
    }

    private static String normalizeMappingTarget(String value) {
        if (value == null || value.isBlank() || MAPPING_IGNORE.equalsIgnoreCase(value.trim())) {
            return MAPPING_IGNORE;
        }
        String v = value.trim();
        for (String standard : List.of(F_EMAIL, F_NAME, F_FIRST_NAME, F_LAST_NAME, F_COMPANY)) {
            if (standard.equalsIgnoreCase(v)) return standard;
        }
        if (v.regionMatches(true, 0, CUSTOM_PREFIX, 0, CUSTOM_PREFIX.length())) {
            String key = v.substring(CUSTOM_PREFIX.length()).trim();
            if (CUSTOM_KEY.matcher(key).matches() && !isStandardFieldName(key)) {
                String lower = key.toLowerCase(Locale.ROOT);
                // Known custom fields are stored lower-case so {{role}} and {{Role}} both resolve.
                return CUSTOM_PREFIX + (KNOWN_CUSTOM_FIELDS.contains(lower) ? lower : key);
            }
        }
        String shown = v.length() > 40 ? v.substring(0, 40) + "…" : v;
        throw new ValidationException("Unsupported column mapping '" + shown
                + "'. Use email, name, firstName, lastName, company, custom:<fieldName> or ignore.");
    }

    private static boolean isStandardFieldName(String key) {
        return Set.of("email", "name", "firstname", "lastname", "company")
                .contains(key.toLowerCase(Locale.ROOT));
    }

    private static String describeTarget(String target) {
        return switch (target) {
            case F_EMAIL      -> "Email";
            case F_NAME       -> "Name";
            case F_FIRST_NAME -> "First name";
            case F_LAST_NAME  -> "Last name";
            case F_COMPANY    -> "Company";
            default -> "custom field '" + target.substring(CUSTOM_PREFIX.length()) + "'";
        };
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
            if (target.startsWith(CUSTOM_PREFIX)) {
                out.put(target.substring(CUSTOM_PREFIX.length()), value);
            } else {
                out.put(target, value);
            }
        }
        return out;
    }

    // ── Bulk create-or-reuse (persist) ─────────────────────────────────────────

    /**
     * Creates new recipients and reuses existing ones (matched by normalised email),
     * returning the full set of recipient IDs to associate with a campaign.
     * Reusing an address never creates a second contact. Invalid-email rows are
     * skipped. Runs in a single transaction.
     */
    @Transactional
    public BulkRecipientResponse bulkCreateOrReuse(BulkRecipientRequest request) {
        // Normalise + de-duplicate incoming rows by email
        LinkedHashMap<String, BulkRecipientRequest.Row> byEmail = new LinkedHashMap<>();
        int skippedInvalid = 0;
        for (BulkRecipientRequest.Row row : request.rows()) {
            String email = EmailValidator.normalize(row.email());
            if (!EmailValidator.isValid(email)) {
                skippedInvalid++;
                continue;
            }
            byEmail.putIfAbsent(email, row); // first wins on in-request dupes
        }

        if (byEmail.isEmpty()) {
            return new BulkRecipientResponse(0, 0, skippedInvalid, 0, List.of());
        }

        List<String> emails = new ArrayList<>(byEmail.keySet());
        Map<String, Recipient> existing = new HashMap<>();
        for (Recipient r : recipientRepository.findByEmailIn(com.mailflow.tenant.TenantContext.requireOrganizationId(), emails)) {
            existing.put(EmailValidator.normalize(r.getEmail()), r);
        }
        // The email column is unique across live AND soft-deleted rows. Deleted
        // contacts are neither reactivated nor re-created (an insert would fail).
        Set<String> deleted = new HashSet<>();
        for (String e : recipientRepository.findDeletedEmails(com.mailflow.tenant.TenantContext.requireOrganizationId(), emails)) {
            deleted.add(EmailValidator.normalize(e));
        }

        String importSource = "MANUAL".equalsIgnoreCase(request.source())
                ? "CAMPAIGN_MANUAL" : "EXCEL_BULK";

        List<Recipient> toCreate = new ArrayList<>();
        List<Long> ids = new ArrayList<>();
        int reused = 0;
        int skippedDeleted = 0;

        for (var e : byEmail.entrySet()) {
            String email = e.getKey();
            Recipient found = existing.get(email);
            if (found != null) {
                ids.add(found.getId());
                reused++;
            } else if (deleted.contains(email)) {
                skippedDeleted++;
            } else {
                toCreate.add(toRecipient(email, e.getValue(), importSource));
            }
        }

        List<Recipient> saved = recipientRepository.saveAll(toCreate);
        for (Recipient r : saved) ids.add(r.getId());

        log.info("Bulk recipients: created={}, reused={}, skippedInvalid={}, skippedDeleted={}, source={}",
                saved.size(), reused, skippedInvalid, skippedDeleted, importSource);
        return new BulkRecipientResponse(saved.size(), reused, skippedInvalid, skippedDeleted, ids);
    }

    private Recipient toRecipient(String email, BulkRecipientRequest.Row row, String importSource) {
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
                .importSource(importSource)
                .build();
    }

    // ── Template generation ─────────────────────────────────────────────────────

    /**
     * Columns of the downloadable recipient template, in order. They are the same as
     * the columns of the "Add emails manually" table in the campaign form: keep this
     * list and {@code TABLE_COLUMNS} in {@code frontend/src/lib/manualTable.ts} equal
     * (a test on each side asserts the exact list). Uploads still accept and map
     * other columns (first/last name, role, custom fields).
     */
    public static final List<String> TEMPLATE_HEADERS = List.of("Email", "Name", "Company");

    /**
     * Builds a downloadable .xlsx template with the canonical headers and a few
     * example rows at example.com.
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
                {"ada@example.com",   "Ada Lovelace", "Analytical Engines"},
                {"alan@example.com",  "Alan Turing",  "Bletchley Park"},
                {"grace@example.com", "Grace Hopper", "US Navy"},
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

    /**
     * Converts a cell to text. Formula cells use their cached result. Any cell that
     * can't be read (for example a formula error) becomes an empty string instead
     * of failing the whole upload.
     */
    private String cellToString(Cell cell) {
        try {
            CellType type = cell.getCellType() == CellType.FORMULA
                    ? cell.getCachedFormulaResultType()
                    : cell.getCellType();
            return switch (type) {
                case STRING  -> cell.getStringCellValue().trim();
                case NUMERIC -> DateUtil.isCellDateFormatted(cell)
                        ? cell.getDateCellValue().toString()
                        : stripTrailingZero(cell.getNumericCellValue());
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> "";
            };
        } catch (RuntimeException e) {
            return "";
        }
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
    private record RawRow(int rowNumber, Map<String, String> values) {}

    private record ParsedUpload(List<String> headers, List<RawRow> rows) {
        static final ParsedUpload EMPTY = new ParsedUpload(List.of(), List.of());
    }

    // ── Inner exception ──────────────────────────────────────────────────────
    private static class InvalidRowException extends Exception {
        InvalidRowException(String msg) { super(msg); }
    }
}
