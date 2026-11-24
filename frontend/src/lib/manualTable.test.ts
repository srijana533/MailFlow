import { describe, expect, it } from 'vitest';
import type { Recipient } from '../types';
import {
  appendPosition,
  blankRows,
  INITIAL_ROWS,
  isBlankRow,
  manualRowStatuses,
  manualTableSummary,
  newRow,
  normalizeRow,
  parsePastedRows,
  placeRows,
  TABLE_COLUMNS,
} from './manualTable';
import {
  combineRecipients,
  EMPTY_SELECTION,
  otherSourcesOfTableRows,
  removeAddress,
  usableManualRows,
  type ManualEntry,
} from './recipientSelection';

function contact(id: number, email: string, status: Recipient['status'] = 'ACTIVE'): Recipient {
  return { id, email, name: `Contact ${id}`, company: 'Acme', customFields: {}, status, createdAt: '', updatedAt: '' };
}

function row(email: string, extra: Partial<ManualEntry> = {}): ManualEntry {
  return { ...newRow({ email }), ...extra };
}

describe('TABLE_COLUMNS', () => {
  it('are exactly the columns of the recipient template', () => {
    // Keep equal to RecipientImportService.TEMPLATE_HEADERS (backend); RecipientImportPreviewTest asserts the same list.
    expect([...TABLE_COLUMNS]).toEqual(['Email', 'Name', 'Company']);
    expect(INITIAL_ROWS).toBe(3);
  });
});

describe('rows', () => {
  it('start empty with unique ids', () => {
    const rows = blankRows(3);
    expect(rows).toHaveLength(3);
    expect(new Set(rows.map((r) => r.id)).size).toBe(3);
    expect(rows.every(isBlankRow)).toBe(true);
  });

  it('normalise the email when it loses focus, like the paste box', () => {
    expect(normalizeRow(row('  Ada@Test.Local\u200b ')).email).toBe('ada@test.local');
    const named = normalizeRow(row('Ada Lovelace <ADA@test.local>'));
    expect(named.email).toBe('ada@test.local');
    expect(named.name).toBe('Ada Lovelace');
    // A name the user typed is kept.
    expect(normalizeRow(row('Ada Lovelace <ada@test.local>', { name: 'Countess' })).name).toBe('Countess');
    expect(normalizeRow(row('mailto:grace@test.local?subject=Hi')).email).toBe('grace@test.local');
    // Invalid values are only trimmed, so they can be corrected.
    expect(normalizeRow(row('  not an email ')).email).toBe('not an email');
    const unchanged = row('ada@test.local');
    expect(normalizeRow(unchanged)).toBe(unchanged);
  });
});

describe('manualRowStatuses', () => {
  it('flags empty, invalid and repeated rows, and keeps them out of the final list', () => {
    const rows = [
      row(''),
      row('', { name: 'No email' }),
      row('not-an-email'),
      row('ada@test.local', { check: { email: 'ada@test.local' } }),
      row('ADA@test.local'),
      row('a@test.local b@test.local'),
    ];
    const statuses = manualRowStatuses(rows);
    expect(statuses.map((s) => s.state)).toEqual(['empty', 'missing', 'invalid', 'new', 'repeated', 'invalid']);
    expect(statuses.map((s) => s.label)).toEqual(['', 'Email is required', 'Invalid email', 'New', 'Repeated', 'Invalid email']);
    expect(statuses[4].detail).toContain('row 4');
    expect(statuses[5].detail).toContain('one address per row');
    expect(usableManualRows(rows).map((r) => r.email)).toEqual(['ada@test.local']);
  });

  it('marks saved contacts, skipped (unsubscribed) addresses and deleted contacts', () => {
    const saved = contact(1, 'saved@test.local');
    const unsubscribedContact = contact(2, 'gone@test.local', 'UNSUBSCRIBED');
    const rows = [
      row('saved@test.local', { check: { email: 'saved@test.local', contact: saved } }),
      row('gone@test.local', { check: { email: 'gone@test.local', contact: unsubscribedContact } }),
      row('listed@test.local', { check: { email: 'listed@test.local', unsubscribed: true } }),
      row('deleted@test.local', { check: { email: 'deleted@test.local', deleted: true } }),
      row('bounced@test.local', { check: { email: 'bounced@test.local', contact: contact(3, 'bounced@test.local', 'BOUNCED') } }),
      row('fresh@test.local'),
    ];
    const statuses = manualRowStatuses(rows);
    expect(statuses.map((s) => s.label)).toEqual([
      'Saved',
      'Unsubscribed: will be skipped',
      'Unsubscribed: will be skipped',
      "Can't be used: deleted contact",
      'Bounced: will be skipped',
      'Checking…',
    ]);
    expect(statuses[0].contact).toBe(saved);

    // Saved contacts are reused whatever their status (skipped when sending); an unsubscribed
    // address that isn't a contact and a deleted contact's address are left out.
    const usable = usableManualRows(rows);
    expect(usable.map((r) => r.email)).toEqual([
      'saved@test.local', 'gone@test.local', 'bounced@test.local', 'fresh@test.local',
    ]);
    expect(usable[0].contact).toBe(saved);
    expect(manualTableSummary(statuses)).toEqual({ ready: 2, saved: 1, fresh: 0, skipped: 3, unused: 1 });
  });

  it('ignores a check made for an address the row no longer has', () => {
    const r = row('new.address@test.local', { check: { email: 'old@test.local', contact: contact(9, 'old@test.local') } });
    expect(manualRowStatuses([r])[0].state).toBe('checking');
    expect(usableManualRows([r])[0].contact).toBeUndefined();
  });
});

describe('the table in the final list', () => {
  it('sends to exactly the table rows plus the other selected sources, each address once', () => {
    const saved = contact(1, 'both@test.local');
    const rows = [
      row('both@test.local'),
      row('typed@test.local', { name: 'Typed Person', company: 'Typed Co' }),
      row('invalid'),
      row(''),
    ];
    const sel = { ...EMPTY_SELECTION, saved: { 1: saved }, manual: rows };
    const { list, mergedDuplicates } = combineRecipients(sel);
    expect(list.map((e) => e.email)).toEqual(['both@test.local', 'typed@test.local']);
    expect(mergedDuplicates).toBe(1);
    expect(list[0].recipientId).toBe(1);
    expect(list[1]).toMatchObject({ name: 'Typed Person', company: 'Typed Co', origin: 'manual' });
    expect(list[1].recipientId).toBeUndefined();
    // The row repeated in another source is flagged on the row.
    expect(otherSourcesOfTableRows(sel)).toEqual(new Map([['both@test.local', ['saved']]]));
    // Removing the address from the final list removes the row too.
    expect(removeAddress(sel, 'BOTH@test.local').manual.map((r) => r.email)).toEqual(['typed@test.local', 'invalid', '']);
  });
});

describe('pasting rows from Excel or Google Sheets', () => {
  it('fills the columns in template order and skips a header row', () => {
    expect(parsePastedRows('Email\tName\tCompany\r\nada@test.local\tAda Lovelace\tAcme\r\nalan@test.local\tAlan\t\r\n')).toEqual([
      { email: 'ada@test.local', name: 'Ada Lovelace', company: 'Acme' },
      { email: 'alan@test.local', name: 'Alan', company: '' },
    ]);
    expect(parsePastedRows('email\nada@test.local\ngrace@test.local')).toEqual([
      { email: 'ada@test.local', name: '', company: '' },
      { email: 'grace@test.local', name: '', company: '' },
    ]);
  });

  it('keeps a first row that is data, drops extra cells and empty lines, and unquotes cells', () => {
    expect(parsePastedRows('ada@test.local\tAda\tAcme\tExtra\n\n"grace@test.local"\t"Hopper, Grace"\t"US ""Navy"""')).toEqual([
      { email: 'ada@test.local', name: 'Ada', company: 'Acme' },
      { email: 'grace@test.local', name: 'Hopper, Grace', company: 'US "Navy"' },
    ]);
  });

  it('leaves a single value to the normal paste', () => {
    expect(parsePastedRows('ada@test.local')).toBeNull();
    expect(parsePastedRows('ada@test.local\n')).toBeNull();
    expect(parsePastedRows('one\ttwo')).toEqual([{ email: 'one', name: 'two', company: '' }]);
  });

  it('fills blank rows first and never overwrites typed rows', () => {
    const typed = row('typed@test.local');
    const rows = [typed, ...blankRows(2)];
    const blankIds = rows.slice(1).map((r) => r.id);
    const incoming = ['a', 'b', 'c'].map((x) => ({ email: `${x}@test.local`, name: '', company: '' }));

    // Pasted into the typed row: the rows go after it.
    const after = placeRows(rows, 0, incoming);
    expect(after.rows.map((r) => r.email)).toEqual(['typed@test.local', 'a@test.local', 'b@test.local', 'c@test.local']);
    expect(after.rows[1].id).toBe(blankIds[0]); // the blank rows were filled in place
    expect(after.rows[2].id).toBe(blankIds[1]);
    expect(after.placed).toHaveLength(3);
    expect(after.overLimit).toBe(0);

    // Pasted into a blank row in the middle: it is filled, then rows are inserted.
    const middle = placeRows([...blankRows(1), typed], 0, incoming.slice(0, 2));
    expect(middle.rows.map((r) => r.email)).toEqual(['a@test.local', 'b@test.local', 'typed@test.local']);
  });

  it('respects the row limit', () => {
    const result = placeRows([row('x@test.local')], 1, [
      { email: 'a@test.local', name: '', company: '' },
      { email: 'b@test.local', name: '', company: '' },
    ], 2);
    expect(result.rows.map((r) => r.email)).toEqual(['x@test.local', 'a@test.local']);
    expect(result.overLimit).toBe(1);
  });

  it('adds rows from the paste box after the last row with content', () => {
    expect(appendPosition(blankRows(3))).toBe(0);
    expect(appendPosition([row('a@test.local'), ...blankRows(2)])).toBe(1);
    expect(appendPosition([...blankRows(1), row('a@test.local'), ...blankRows(1)])).toBe(2);
  });
});
