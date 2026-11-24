import type { Recipient, RecipientStatus, TargetAddress } from '../types';
import type { ColumnMapping, ExcelPreviewResponse, ExcelRowPreview } from '../api/recipients';

/**
 * Shared model for choosing campaign recipients from three sources:
 * saved contacts, an uploaded file, and manually typed addresses. When a draft is
 * edited, its stored campaign-only addresses are a fourth source.
 * The sources are combined into one list, de-duplicated by email address.
 */

// ── Email helpers (same rule as the backend EmailValidator) ────────────────────

/** Identical to the backend's EmailValidator.PATTERN_SOURCE (a backend test compares them). */
const EMAIL_RE = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/;

/**
 * Characters removed from both ends of an address, the same set as the backend's
 * EmailValidator.isTrimmable: C0 controls and space (U+0000-U+0020), U+00A0, U+1680,
 * U+2000-U+200D, U+2028, U+2029, U+202F, U+205F, U+2060, U+3000 and U+FEFF. That is
 * everything JavaScript's and Java's trim() remove plus the invisible characters
 * they miss (zero-width space U+200B, U+200C, U+200D, word joiner U+2060, byte-order
 * mark U+FEFF, non-breaking spaces).
 */
function isEdgeChar(code: number): boolean {
  return (
    code <= 0x20 ||
    code === 0xa0 ||
    code === 0x1680 ||
    (code >= 0x2000 && code <= 0x200d) ||
    code === 0x2028 ||
    code === 0x2029 ||
    code === 0x202f ||
    code === 0x205f ||
    code === 0x2060 ||
    code === 0x3000 ||
    code === 0xfeff
  );
}

/** Removes spaces and invisible characters from both ends (never from inside). */
export function trimEmail(value: string): string {
  let start = 0;
  let end = value.length;
  while (start < end && isEdgeChar(value.charCodeAt(start))) start++;
  while (end > start && isEdgeChar(value.charCodeAt(end - 1))) end--;
  return value.slice(start, end);
}

/**
 * The one normalisation rule for addresses, shared with the backend: trim (see
 * trimEmail), then lower-case. Used before validating, comparing and looking up.
 */
export function normalizeEmail(value: string): string {
  return trimEmail(value).toLowerCase();
}

export function isValidEmail(value: string): boolean {
  return EMAIL_RE.test(normalizeEmail(value));
}

/**
 * Why a saved contact (or an address on the unsubscribe list) is not emailed, in
 * plain words. Null when it can be emailed. Never "duplicate" or "used before": a
 * contact can be in any number of campaigns.
 */
export function skipReason(status: RecipientStatus | undefined, onUnsubscribeList = false): string | null {
  if (onUnsubscribeList || status === 'UNSUBSCRIBED') return 'Unsubscribed';
  if (status === 'BOUNCED') return 'Bounced';
  if (status === 'INVALID') return 'Marked invalid';
  return null;
}

// ── Selection state ─────────────────────────────────────────────────────────────

export type RecipientSource = 'saved' | 'upload' | 'manual' | 'draft';

export const SOURCE_LABEL: Record<RecipientSource, string> = {
  saved: 'Saved contact',
  upload: 'From file',
  manual: 'Added manually',
  draft: 'Saved with draft',
};

/** Maximum campaign-only addresses per campaign (enforced by the backend too). */
export const CAMPAIGN_ONLY_LIMIT = 10_000;

/**
 * A row of the "Add emails manually" table (rows also come from a pasted list). The
 * cells hold what the user typed; the email cell is normalised when it loses focus.
 * Only rows with a valid address go into the final list (see usableManualRows).
 */
export interface ManualEntry {
  /** Stable row id, for React keys and for moving the focus between cells. */
  id: string;
  email: string;
  name: string;
  company: string;
  /** Saved-contact check (read-only lookup) of the address the row had at that time. */
  check?: ManualCheck;
}

/** Result of looking up one table address among the saved contacts. */
export interface ManualCheck {
  /** The normalised address that was checked. */
  email: string;
  /** Saved contact that already uses this address; it is reused instead of creating one. */
  contact?: Recipient;
  /** The address belongs to a deleted contact and can't be used. */
  deleted?: boolean;
  /** The address is on the unsubscribe list. */
  unsubscribed?: boolean;
}

/** The row's saved-contact check, if it was made for the address the row has now. */
export function currentCheck(row: ManualEntry): ManualCheck | undefined {
  return row.check && row.check.email === normalizeEmail(row.email) ? row.check : undefined;
}

/** A table row that goes into the final list. */
export interface UsableManualRow {
  email: string;
  name: string;
  company: string;
  contact?: Recipient;
}

/**
 * Table rows that go into the final list: a valid address, the first row with that
 * address (later rows are repeats), not the address of a deleted contact, and not an
 * unsubscribed address that isn't a saved contact (it would never be emailed). A saved
 * contact is used whatever its status; the lists say when it will be skipped. Blank and
 * invalid rows stay out.
 */
export function usableManualRows(rows: ManualEntry[]): UsableManualRow[] {
  const seen = new Set<string>();
  const out: UsableManualRow[] = [];
  for (const r of rows) {
    const email = normalizeEmail(r.email);
    if (!email || !isValidEmail(email) || seen.has(email)) continue;
    seen.add(email);
    const check = currentCheck(r);
    if (check?.deleted) continue;
    if (check?.unsubscribed && !check.contact) continue;
    out.push({ email, name: r.name.trim(), company: r.company.trim(), contact: check?.contact });
  }
  return out;
}

/** The current upload: the file (kept so the column mapping can be changed) and its preview. */
export interface UploadSelection {
  file: File;
  preview: ExcelPreviewResponse;
  /** Column mapping overrides sent with the latest preview. */
  mapping: ColumnMapping;
  /** Row numbers the user removed from the campaign. */
  removedRows: number[];
}

export interface RecipientSelection {
  /** Selected saved contacts, by ID. */
  saved: Record<number, Recipient>;
  upload: UploadSelection | null;
  manual: ManualEntry[];
  /**
   * Campaign-only addresses already stored with the draft being edited, with all
   * their fields, so saving the draft again keeps them unchanged.
   */
  draft: TargetAddress[];
}

export const EMPTY_SELECTION: RecipientSelection = { saved: {}, upload: null, manual: [], draft: [] };

/**
 * Upload rows that will be used: new and saved-contact rows the user didn't remove.
 * SKIPPED (unsubscribed or not emailable), DUPLICATE and INVALID rows are never used.
 */
export function keptUploadRows(upload: UploadSelection | null): ExcelRowPreview[] {
  if (!upload) return [];
  const removed = new Set(upload.removedRows);
  return upload.preview.rows.filter(
    (r) => (r.status === 'VALID' || r.status === 'EXISTING') && !removed.has(r.rowNumber),
  );
}

// ── Combined list ───────────────────────────────────────────────────────────────

/** One address in the final, de-duplicated recipient list. */
export interface CombinedRecipient {
  email: string;
  /** Saved contact used for this address, when known. */
  recipientId?: number;
  /** Saved contact's status, when known (only ACTIVE contacts are emailed). */
  status?: RecipientStatus;
  /** The saved contact, when its full record is known (used for previews). */
  contact?: Recipient;
  name?: string;
  firstName?: string;
  lastName?: string;
  company?: string;
  /**
   * Custom fields of an address that is not a saved contact (the file's mapped
   * columns, or a stored campaign-only address), used to personalise its email.
   */
  fields?: Record<string, string>;
  /** Every source that supplied this address. */
  sources: RecipientSource[];
  /** Source whose values are used for an address that is not a saved contact. */
  origin: RecipientSource;
}

const STANDARD_KEYS = new Set(['email', 'name', 'firstName', 'lastName', 'company']);

function fromContact(r: Recipient, source: RecipientSource): CombinedRecipient {
  return {
    email: normalizeEmail(r.email),
    recipientId: r.id,
    status: r.status,
    contact: r,
    name: r.name ?? undefined,
    firstName: r.firstName ?? undefined,
    lastName: r.lastName ?? undefined,
    company: r.company ?? undefined,
    sources: [source],
    origin: source,
  };
}

function fromUploadRow(row: ExcelRowPreview): CombinedRecipient {
  const f = row.fields ?? {};
  const extra: Record<string, string> = {};
  for (const [k, v] of Object.entries(f)) {
    if (!STANDARD_KEYS.has(k) && v) extra[k] = v;
  }
  return {
    email: normalizeEmail(f.email ?? row.email ?? ''),
    recipientId: row.status === 'EXISTING' && row.recipientId != null ? row.recipientId : undefined,
    name: f.name || row.name || undefined,
    firstName: f.firstName || undefined,
    lastName: f.lastName || undefined,
    company: f.company || row.company || undefined,
    fields: extra,
    sources: ['upload'],
    origin: 'upload',
  };
}

function fromManual(m: UsableManualRow): CombinedRecipient {
  if (m.contact) return fromContact(m.contact, 'manual');
  return {
    email: m.email,
    name: m.name || undefined,
    company: m.company || undefined,
    sources: ['manual'],
    origin: 'manual',
  };
}

function fromDraft(a: TargetAddress): CombinedRecipient {
  return {
    email: normalizeEmail(a.email),
    name: a.name ?? undefined,
    firstName: a.firstName ?? undefined,
    lastName: a.lastName ?? undefined,
    company: a.company ?? undefined,
    fields: { ...(a.fields ?? {}) },
    sources: ['draft'],
    origin: 'draft',
  };
}

/** The campaign-only form of an address that is not a saved contact (sent as `targetAddresses`). */
export function toTargetAddress(e: CombinedRecipient): TargetAddress {
  return {
    email: e.email,
    name: e.name ?? null,
    firstName: e.firstName ?? null,
    lastName: e.lastName ?? null,
    company: e.company ?? null,
    fields: e.fields ?? {},
  };
}

/** Merges `next` into `current`. A saved contact's stored data always wins. */
function merge(current: CombinedRecipient, next: CombinedRecipient) {
  for (const s of next.sources) {
    if (!current.sources.includes(s)) current.sources.push(s);
  }
  if (current.recipientId == null && next.recipientId != null) {
    current.recipientId = next.recipientId;
  }
  if (!current.contact && next.contact) {
    Object.assign(current, {
      contact: next.contact,
      status: next.status,
      name: next.name,
      firstName: next.firstName,
      lastName: next.lastName,
      company: next.company,
    });
  }
}

/**
 * Combines the sources into one list keyed by lower-case email.
 * Order: saved contacts, then the draft's stored campaign-only addresses, then file
 * rows, then typed addresses. For an address that is not a saved contact, the first
 * source's values are kept, so a stored address keeps all its fields.
 * `mergedDuplicates` counts addresses that appeared in more than one place.
 */
export function combineRecipients(sel: RecipientSelection): {
  list: CombinedRecipient[];
  mergedDuplicates: number;
} {
  const byEmail = new Map<string, CombinedRecipient>();
  let supplied = 0;
  const add = (entry: CombinedRecipient) => {
    if (!entry.email) return;
    supplied++;
    const existing = byEmail.get(entry.email);
    if (existing) merge(existing, entry);
    else byEmail.set(entry.email, entry);
  };

  Object.values(sel.saved).forEach((r) => add(fromContact(r, 'saved')));
  sel.draft.forEach((a) => add(fromDraft(a)));
  keptUploadRows(sel.upload).forEach((row) => add(fromUploadRow(row)));
  usableManualRows(sel.manual).forEach((m) => add(fromManual(m)));

  return { list: Array.from(byEmail.values()), mergedDuplicates: supplied - byEmail.size };
}

/**
 * For each address of the manual-entry table that another source (saved contacts, the
 * uploaded file, the draft's stored addresses) also supplies: those other sources. The
 * address is still emailed once.
 */
export function otherSourcesOfTableRows(sel: RecipientSelection): Map<string, RecipientSource[]> {
  const out = new Map<string, RecipientSource[]>();
  for (const e of combineRecipients(sel).list) {
    if (!e.sources.includes('manual')) continue;
    const others = e.sources.filter((s) => s !== 'manual');
    if (others.length > 0) out.set(e.email, others);
  }
  return out;
}

/** Removes one address from every source it came from. */
export function removeAddress(sel: RecipientSelection, email: string): RecipientSelection {
  const key = normalizeEmail(email);
  const saved = Object.fromEntries(
    Object.entries(sel.saved).filter(([, r]) => normalizeEmail(r.email) !== key),
  ) as Record<number, Recipient>;
  const upload = sel.upload
    ? {
        ...sel.upload,
        removedRows: Array.from(
          new Set([
            ...sel.upload.removedRows,
            ...sel.upload.preview.rows
              .filter((r) => normalizeEmail(r.fields?.email ?? r.email ?? '') === key)
              .map((r) => r.rowNumber),
          ]),
        ),
      }
    : null;
  const manual = sel.manual.filter((m) => normalizeEmail(m.email) !== key);
  const draft = sel.draft.filter((a) => normalizeEmail(a.email) !== key);
  return { saved, upload, manual, draft };
}

// ── Manual entry parsing ────────────────────────────────────────────────────────

export interface ParsedAddresses {
  entries: { email: string; name?: string }[];
  invalid: string[];
  /** Repeats within the pasted text. */
  duplicates: number;
}

/**
 * Splits pasted text into addresses. Separators: comma, semicolon, spaces/tabs
 * and new lines. Also accepts the `Display Name <address>` form copied from
 * email clients (the display name becomes the contact name) and `mailto:` links
 * (a `?subject=...` part is dropped). Addresses are normalised with normalizeEmail.
 */
export function parseAddressList(text: string): ParsedAddresses {
  const entries: { email: string; name?: string }[] = [];
  const invalid: string[] = [];
  const seen = new Set<string>();
  let duplicates = 0;

  const push = (raw: string, name?: string) => {
    let cleaned = trimEmail(raw)
      .replace(/^["'<([]+/, '')
      .replace(/[>"')\]]+$/, '');
    if (/^mailto:/i.test(cleaned)) cleaned = cleaned.slice('mailto:'.length).split('?')[0];
    cleaned = trimEmail(cleaned);
    if (!cleaned) return;
    const email = normalizeEmail(cleaned);
    if (!isValidEmail(email)) {
      invalid.push(trimEmail(raw));
      return;
    }
    if (seen.has(email)) {
      duplicates++;
      return;
    }
    seen.add(email);
    entries.push({ email, name: name?.trim() || undefined });
  };

  // Either `Name <address>` (name without @ or separators) or a single token.
  const token = /(?:"?([^"<>,;\n@]*?)"?\s*<\s*([^<>\s,;]+)\s*>)|([^\s,;]+)/g;
  let m: RegExpExecArray | null;
  while ((m = token.exec(text)) !== null) {
    if (m[2] !== undefined) push(m[2], m[1]);
    else if (m[3] !== undefined) push(m[3]);
  }
  return { entries, invalid, duplicates };
}

/** Maximum number of rows in the manual-entry table (larger lists should be uploaded as a file). */
export const MANUAL_LIMIT = 1000;

// ── Placeholders provided by mapped columns ─────────────────────────────────────

const STANDARD_PLACEHOLDERS: Record<string, string[]> = {
  email: ['email', 'Email'],
  name: ['name', 'Name'],
  firstName: ['firstName', 'FirstName'],
  lastName: ['lastName', 'LastName'],
  company: ['company', 'Company'],
};

/** Custom fields that the send-time model also exposes with a capitalised alias. */
const ALIASED_CUSTOM_FIELDS = new Set(['role', 'phone', 'location', 'interest', 'department', 'course']);

/** Known custom fields offered in the column-mapping menu. */
export const KNOWN_CUSTOM_FIELDS = ['role', 'phone', 'location', 'interest', 'department', 'course', 'code'];

/** Placeholders a mapping target fills, e.g. "custom:role" -> {{role}}, {{Role}}. */
export function placeholdersForTarget(target: string): string[] {
  if (target.startsWith('custom:')) {
    const key = target.slice('custom:'.length);
    return ALIASED_CUSTOM_FIELDS.has(key) ? [key, key.charAt(0).toUpperCase() + key.slice(1)] : [key];
  }
  return STANDARD_PLACEHOLDERS[target] ?? [];
}

/** Placeholder key suggested for an unrecognised column, e.g. "Course Code" -> "CourseCode". */
export function suggestCustomKey(header: string): string {
  let key = header.replace(/[^A-Za-z0-9]/g, '');
  if (!key) key = 'Field';
  if (/^[0-9]/.test(key)) key = `F${key}`;
  if (['email', 'name', 'firstname', 'lastname', 'company'].includes(key.toLowerCase())) {
    key = `${key}Value`;
  }
  return key.slice(0, 40);
}

/** Display label for a mapping target. */
export function targetLabel(target: string): string {
  switch (target) {
    case 'email': return 'Email';
    case 'name': return 'Name';
    case 'firstName': return 'First name';
    case 'lastName': return 'Last name';
    case 'company': return 'Company';
    case 'ignore': return "Don't use";
    default:
      if (target.startsWith('custom:')) {
        const key = target.slice('custom:'.length);
        return KNOWN_CUSTOM_FIELDS.includes(key)
          ? key.charAt(0).toUpperCase() + key.slice(1)
          : `Custom field "${key}"`;
      }
      return target;
  }
}

/** Placeholders like {{Name}} used in a text. */
export function extractPlaceholders(text: string | null | undefined): string[] {
  if (!text) return [];
  const out = new Set<string>();
  for (const m of text.matchAll(/\{\{(\w+)\}\}/g)) out.add(m[1]);
  return Array.from(out);
}
