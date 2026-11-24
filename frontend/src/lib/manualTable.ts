import type { Recipient } from '../types';
import {
  currentCheck,
  isValidEmail,
  MANUAL_LIMIT,
  normalizeEmail,
  parseAddressList,
  skipReason,
  trimEmail,
  type ManualEntry,
} from './recipientSelection';

/**
 * The "Add emails manually" table of the campaign form: rows of Email (required), Name
 * and Company. Pure helpers for the rows; the component is ManualRecipientEntry.
 */

/**
 * Columns of the table, in order. They are the columns of the downloadable recipient
 * template: keep this list equal to TEMPLATE_HEADERS in RecipientImportService (backend).
 * manualTable.test.ts and RecipientImportPreviewTest each assert the exact list.
 */
export const TABLE_COLUMNS = ['Email', 'Name', 'Company'] as const;

/** Number of empty rows the table starts with. */
export const INITIAL_ROWS = 3;

let nextRowId = 1;

/** A new table row with a unique id. */
export function newRow(values?: { email?: string; name?: string; company?: string }): ManualEntry {
  return {
    id: `row-${nextRowId++}`,
    email: values?.email ?? '',
    name: values?.name ?? '',
    company: values?.company ?? '',
  };
}

export function blankRows(count: number): ManualEntry[] {
  return Array.from({ length: count }, () => newRow());
}

export function isBlankRow(r: ManualEntry): boolean {
  return !r.email.trim() && !r.name.trim() && !r.company.trim();
}

/**
 * The row once its email cell loses focus, with the same rules as the paste box: a
 * `Name <address>` or `mailto:` value gives the address (the name fills an empty Name
 * cell), and a valid address is trimmed, including invisible characters, and
 * lower-cased. Anything else is only trimmed so the user can correct it.
 */
export function normalizeRow(r: ManualEntry): ManualEntry {
  const raw = trimEmail(r.email);
  if (!raw) return r.email === '' ? r : { ...r, email: '' };
  const parsed = parseAddressList(raw);
  if (parsed.entries.length === 1 && parsed.invalid.length === 0) {
    const { email, name } = parsed.entries[0];
    const nextName = r.name.trim() ? r.name : name ?? r.name;
    return email === r.email && nextName === r.name ? r : { ...r, email, name: nextName };
  }
  return raw === r.email ? r : { ...r, email: raw };
}

export type ManualRowState =
  | 'empty'     // nothing typed
  | 'missing'   // a name or company but no email
  | 'invalid'   // not a valid address
  | 'repeated'  // same address as an earlier row
  | 'checking'  // valid; the saved-contact check hasn't answered yet
  | 'deleted'   // the address of a deleted contact
  | 'skipped'   // unsubscribed (or a bounced / invalid saved contact): never emailed
  | 'saved'     // a saved contact, reused
  | 'new';      // not a saved contact: campaign-only unless saved on the Review step

export interface ManualRowStatus {
  state: ManualRowState;
  /** Text of the Status column. */
  label: string;
  /** Longer explanation (tooltip and second line). */
  detail?: string;
  /** The saved contact using the address, when there is one. */
  contact?: Recipient;
}

/** Statuses that keep a row out of the final list. */
export const UNUSED_STATES: ReadonlySet<ManualRowState> = new Set(['missing', 'invalid', 'repeated', 'deleted']);

/**
 * The status of every row, in order. Repeats are found with the normalised address, so
 * `Ada@Example.com` repeats `ada@example.com`; the first row with an address is the one
 * used. Rows whose check isn't current are 'checking'.
 */
export function manualRowStatuses(rows: ManualEntry[]): ManualRowStatus[] {
  const firstRow = new Map<string, number>();
  return rows.map((r, index): ManualRowStatus => {
    if (!trimEmail(r.email)) {
      return r.name.trim() || r.company.trim()
        ? { state: 'missing', label: 'Email is required' }
        : { state: 'empty', label: '' };
    }
    const email = normalizeEmail(r.email);
    if (!isValidEmail(email)) {
      return {
        state: 'invalid',
        label: 'Invalid email',
        detail:
          parseAddressList(r.email).entries.length > 1
            ? 'Put one address per row. To add several at once, use "Paste a list".'
            : 'Check the address, for example name@company.com.',
      };
    }
    const first = firstRow.get(email);
    if (first !== undefined) {
      return { state: 'repeated', label: 'Repeated', detail: `Same address as row ${first}. It is emailed once.` };
    }
    firstRow.set(email, index + 1);

    const check = currentCheck(r);
    if (!check) return { state: 'checking', label: 'Checking…' };
    if (check.deleted) {
      return {
        state: 'deleted',
        label: "Can't be used: deleted contact",
        detail: 'This address belongs to a deleted contact, so it is not emailed.',
      };
    }
    const reason = check.contact
      ? skipReason(check.contact.status, check.unsubscribed)
      : check.unsubscribed
      ? 'Unsubscribed'
      : null;
    if (reason) {
      return {
        state: 'skipped',
        label: `${reason}: will be skipped`,
        detail: `${reason}: this address is not emailed. Nothing else changes.`,
        contact: check.contact,
      };
    }
    if (check.contact) {
      return {
        state: 'saved',
        label: 'Saved',
        detail: 'A saved contact: it is reused with its saved details.',
        contact: check.contact,
      };
    }
    return {
      state: 'new',
      label: 'New',
      detail:
        'Not a saved contact: used for this campaign only, unless you choose on the Review step to save new addresses as contacts.',
    };
  });
}

/** Rows pasted from a spreadsheet, in TABLE_COLUMNS order. */
export interface PastedRow {
  email: string;
  name: string;
  company: string;
}

function cleanCell(value: string): string {
  let s = value.trim();
  // A spreadsheet quotes a cell that contains a tab, a line break or a quote.
  if (s.length >= 2 && s.startsWith('"') && s.endsWith('"')) s = s.slice(1, -1).replace(/""/g, '"').trim();
  return s;
}

function isHeaderRow(cells: string[]): boolean {
  if ((cells[0] ?? '').toLowerCase() !== TABLE_COLUMNS[0].toLowerCase()) return false;
  return cells.every((c, i) => c === '' || c.toLowerCase() === (TABLE_COLUMNS[i] ?? '').toLowerCase());
}

/**
 * Rows copied from Excel or Google Sheets and pasted into a cell: tab-separated cells,
 * one row per line, in TABLE_COLUMNS order (extra cells are ignored). A first row equal
 * to the column names (the template's headers) is skipped, and so are empty lines.
 * Returns null for a single value without tabs, which is pasted into the cell as usual.
 */
export function parsePastedRows(text: string): PastedRow[] | null {
  const normalized = text.replace(/\r\n?/g, '\n');
  const lines = normalized.split('\n');
  while (lines.length > 0 && lines[lines.length - 1].trim() === '') lines.pop();
  if (lines.length <= 1 && !normalized.includes('\t')) return null;
  const rows = lines.map((line) => line.split('\t').map(cleanCell));
  if (rows.length > 0 && isHeaderRow(rows[0])) rows.shift();
  return rows
    .filter((cells) => cells.some((c) => c !== ''))
    .map(([email = '', name = '', company = '']) => ({ email, name, company }));
}

/**
 * Puts rows into the table starting at row `at`. A blank row there is filled, otherwise
 * a new row is inserted, so rows the user typed are never overwritten. When `at` is a
 * row with content, the rows go after it. The table holds at most `limit` rows.
 */
export function placeRows(
  rows: ManualEntry[],
  at: number,
  incoming: PastedRow[],
  limit: number = MANUAL_LIMIT,
): { rows: ManualEntry[]; placed: ManualEntry[]; overLimit: number } {
  const out = rows.slice();
  const placed: ManualEntry[] = [];
  let i = Math.max(0, Math.min(at, out.length));
  if (i < out.length && !isBlankRow(out[i])) i++;
  let overLimit = 0;
  for (const values of incoming) {
    if (i < out.length && isBlankRow(out[i])) {
      out[i] = { ...newRow(values), id: out[i].id };
    } else if (out.length < limit) {
      out.splice(i, 0, newRow(values));
    } else {
      overLimit++;
      continue;
    }
    placed.push(out[i]);
    i++;
  }
  return { rows: out, placed, overLimit };
}

/** Index after the last row with content: where added rows go. */
export function appendPosition(rows: ManualEntry[]): number {
  for (let i = rows.length - 1; i >= 0; i--) {
    if (!isBlankRow(rows[i])) return i + 1;
  }
  return 0;
}

/** Counts for the table's footer. */
export function manualTableSummary(statuses: ManualRowStatus[]): {
  ready: number;
  saved: number;
  fresh: number;
  skipped: number;
  unused: number;
} {
  let saved = 0;
  let fresh = 0;
  let skipped = 0;
  let unused = 0;
  let checking = 0;
  for (const s of statuses) {
    if (s.state === 'saved') saved++;
    else if (s.state === 'new') fresh++;
    else if (s.state === 'skipped') skipped++;
    else if (s.state === 'checking') checking++;
    else if (UNUSED_STATES.has(s.state)) unused++;
  }
  // Rows still being checked are counted as ready: the Review step checks them again.
  return { ready: saved + fresh + checking, saved, fresh, skipped, unused };
}
