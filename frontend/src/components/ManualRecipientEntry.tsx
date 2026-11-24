import { useEffect, useMemo, useRef, useState, type ClipboardEvent, type KeyboardEvent } from 'react';
import clsx from 'clsx';
import {
  AlertTriangle, CheckCircle2, ClipboardList, Copy, Info, Loader2, MailX, Plus, RotateCcw, UserCheck, UserPlus, X,
} from 'lucide-react';
import { recipientsApi } from '../api/recipients';
import {
  isValidEmail,
  MANUAL_LIMIT,
  normalizeEmail,
  parseAddressList,
  SOURCE_LABEL,
  type ManualCheck,
  type ManualEntry,
  type RecipientSource,
} from '../lib/recipientSelection';
import {
  appendPosition,
  blankRows,
  INITIAL_ROWS,
  manualRowStatuses,
  manualTableSummary,
  newRow,
  normalizeRow,
  parsePastedRows,
  placeRows,
  TABLE_COLUMNS,
  type ManualRowStatus,
  type PastedRow,
} from '../lib/manualTable';
import { apiErrorMessage } from '../lib/errors';
import { Button } from './ui';

type Column = 'email' | 'name' | 'company';
const COLUMN_KEYS: Column[] = ['email', 'name', 'company'];

interface ManualRecipientEntryProps {
  rows: ManualEntry[];
  /** Updates the rows from their latest value (saved-contact checks answer later). */
  onChange: (update: (rows: ManualEntry[]) => ManualEntry[]) => void;
  /** Other sources that also supply an address of the table; it is still emailed once. */
  otherSources?: Map<string, RecipientSource[]>;
}

interface PasteReport {
  added: number;
  invalid: string[];
  alreadyInTable: number;
  repeats: number;
  overLimit: number;
}

function statusBadge(s: ManualRowStatus, checking: boolean) {
  switch (s.state) {
    case 'saved':
      return <span className="badge bg-brand-50 text-brand-700"><UserCheck className="h-3 w-3" /> {s.label}</span>;
    case 'new':
      return <span className="badge bg-emerald-50 text-emerald-700"><UserPlus className="h-3 w-3" /> {s.label}</span>;
    case 'skipped':
      return <span className="badge bg-amber-50 text-amber-700"><MailX className="h-3 w-3" /> {s.label}</span>;
    case 'repeated':
      return <span className="badge bg-amber-50 text-amber-700"><Copy className="h-3 w-3" /> {s.label}</span>;
    case 'invalid':
    case 'missing':
    case 'deleted':
      return <span className="badge bg-red-50 text-red-700"><AlertTriangle className="h-3 w-3" /> {s.label}</span>;
    case 'checking':
      return (
        <span className="badge bg-slate-100 text-slate-500">
          {checking ? <Loader2 className="h-3 w-3 animate-spin" /> : null} {checking ? 'Checking…' : 'Not checked yet'}
        </span>
      );
    default:
      return null;
  }
}

/**
 * The "Add emails manually" table: Email (required), Name and Company, one recipient
 * per row. Each email is normalised and validated when its cell loses focus (the same
 * rules as the paste box and the backend) and looked up among the saved contacts
 * (read-only): saved contacts are reused with their status respected, other addresses are
 * campaign-only unless the user saves them as contacts on the Review step. Empty, invalid
 * and repeated rows show why on the row and stay out of the final list.
 *
 * Tab moves to the next cell; Enter moves to the next row (adding one at the end). Rows
 * copied from Excel or Google Sheets can be pasted into any cell; "Paste a list" takes
 * addresses separated by commas, semicolons, spaces or new lines.
 */
export function ManualRecipientEntry({ rows, onChange, otherSources }: ManualRecipientEntryProps) {
  const [pending, setPending] = useState<Set<string>>(() => new Set());
  const [checkError, setCheckError] = useState<string | null>(null);
  const [showPaste, setShowPaste] = useState(false);
  const [pasteText, setPasteText] = useState('');
  const [report, setReport] = useState<PasteReport | null>(null);

  // The latest rows, for event handlers that run after an await.
  const latest = useRef(rows);
  latest.current = rows;
  const cells = useRef(new Map<string, HTMLInputElement>());
  const focusTarget = useRef<{ index: number; column: Column } | null>(null);

  // The table always has rows to type into: it starts with INITIAL_ROWS empty rows.
  const empty = rows.length === 0;
  useEffect(() => {
    if (empty) onChange((prev) => (prev.length > 0 ? prev : blankRows(INITIAL_ROWS)));
  }, [empty, onChange]);

  // Move the focus once the row it goes to has been rendered.
  useEffect(() => {
    const target = focusTarget.current;
    if (!target) return;
    const row = rows[target.index];
    if (!row) return;
    focusTarget.current = null;
    cells.current.get(`${row.id}:${target.column}`)?.focus();
  }, [rows]);

  const statuses = useMemo(() => manualRowStatuses(rows), [rows]);
  const summary = useMemo(() => manualTableSummary(statuses), [statuses]);

  /** Looks up addresses among the saved contacts (read-only) and stores the result on every row that has them. */
  const check = async (addresses: string[]) => {
    const emails = Array.from(new Set(addresses.map(normalizeEmail).filter((e) => isValidEmail(e))));
    if (emails.length === 0) return;
    setPending((p) => new Set([...p, ...emails]));
    try {
      const res = await recipientsApi.lookup({ emails });
      const contacts = new Map(res.recipients.map((r) => [normalizeEmail(r.email), r]));
      const deleted = new Set(res.deletedEmails.map(normalizeEmail));
      const unsubscribed = new Set((res.unsubscribedEmails ?? []).map(normalizeEmail));
      const results = new Map<string, ManualCheck>(
        emails.map((e) => [e, {
          email: e,
          contact: contacts.get(e),
          deleted: deleted.has(e),
          unsubscribed: unsubscribed.has(e),
        }]),
      );
      onChange((prev) =>
        prev.map((r) => {
          const result = results.get(normalizeEmail(r.email));
          return result ? { ...r, check: result } : r;
        }),
      );
      setCheckError(null);
    } catch (e) {
      setCheckError(
        apiErrorMessage(e, "Couldn't check whether these addresses are saved contacts.") +
          ' The Review step checks them again before anything is saved.',
      );
    } finally {
      setPending((p) => {
        const next = new Set(p);
        emails.forEach((e) => next.delete(e));
        return next;
      });
    }
  };

  const setCell = (id: string, column: Column, value: string) =>
    onChange((prev) => prev.map((r) => (r.id === id ? { ...r, [column]: value } : r)));

  const onEmailBlur = (id: string) => {
    const row = latest.current.find((r) => r.id === id);
    if (!row) return;
    const normalized = normalizeRow(row);
    if (normalized !== row) onChange((prev) => prev.map((r) => (r.id === id ? normalizeRow(r) : r)));
    const email = normalizeEmail(normalized.email);
    const upToDate = normalized.check && normalized.check.email === email;
    if (isValidEmail(email) && !upToDate) void check([email]);
  };

  const insertRows = (pasted: PastedRow[], at: number): PasteReport => {
    // Pasted cells get the same normalisation as a typed cell that loses focus.
    const incoming = pasted.map((p) => {
      const r = normalizeRow({ id: '', email: p.email, name: p.name, company: p.company });
      return { email: r.email, name: r.name, company: r.company };
    });
    const inTable = new Set(latest.current.map((r) => normalizeEmail(r.email)).filter(Boolean));
    const fresh = incoming.filter((r) => !inTable.has(normalizeEmail(r.email)) || !r.email.trim());
    const preview = placeRows(latest.current, at, fresh);
    onChange((prev) => placeRows(prev, at, fresh).rows);
    void check(fresh.map((r) => r.email));
    return {
      added: preview.placed.length,
      invalid: [],
      alreadyInTable: incoming.length - fresh.length,
      repeats: 0,
      overLimit: preview.overLimit,
    };
  };

  const onCellPaste = (e: ClipboardEvent<HTMLInputElement>, index: number) => {
    const text = e.clipboardData.getData('text/plain');
    const pasted = parsePastedRows(text);
    if (pasted) {
      e.preventDefault();
      // Rows from a spreadsheet: one table row each, starting at this row.
      setReport(insertRows(pasted, index));
      return;
    }
    const list = parseAddressList(text);
    if (list.entries.length > 1) {
      // Several addresses in one line (commas, semicolons, spaces): one row each.
      e.preventDefault();
      const r = insertRows(list.entries.map((x) => ({ email: x.email, name: x.name ?? '', company: '' })), index);
      setReport({ ...r, invalid: list.invalid, repeats: list.duplicates });
    }
  };

  const addRow = () => {
    if (rows.length >= MANUAL_LIMIT) return;
    focusTarget.current = { index: rows.length, column: 'email' };
    onChange((prev) => (prev.length >= MANUAL_LIMIT ? prev : [...prev, newRow()]));
  };

  const onCellKeyDown = (e: KeyboardEvent<HTMLInputElement>, index: number) => {
    if (e.key !== 'Enter' || e.nativeEvent.isComposing) return;
    // Enter: next row, email column (a new row at the end), like finishing a row in a spreadsheet.
    e.preventDefault();
    if (index + 1 < rows.length) {
      cells.current.get(`${rows[index + 1].id}:email`)?.focus();
    } else {
      addRow();
    }
  };

  const removeRow = (id: string) =>
    onChange((prev) => {
      const next = prev.filter((r) => r.id !== id);
      return next.length > 0 ? next : blankRows(1);
    });

  const clearTable = () => {
    if (!window.confirm('Remove every row from this table?')) return;
    onChange(() => blankRows(INITIAL_ROWS));
    setReport(null);
  };

  const addPastedList = () => {
    const parsed = parseAddressList(pasteText);
    const r = insertRows(
      parsed.entries.map((x) => ({ email: x.email, name: x.name ?? '', company: '' })),
      appendPosition(latest.current),
    );
    setReport({ ...r, invalid: parsed.invalid, repeats: parsed.duplicates });
    // Keep only the entries that need fixing in the box.
    setPasteText(parsed.invalid.join('\n'));
  };

  const retryChecks = () => void check(latest.current.map((r) => r.email));

  const placeholders: Record<Column, string> = { email: 'name@company.com', name: 'Optional', company: 'Optional' };

  return (
    <div className="space-y-3">
      <div className="overflow-x-auto rounded-xl border border-slate-200">
        <table className="w-full min-w-[40rem] text-left text-sm">
          <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="w-10 px-2 py-2 text-right font-medium">#</th>
              {TABLE_COLUMNS.map((c) => (
                <th key={c} className="px-2 py-2 font-medium">
                  {c}
                  {c === 'Email' && <span className="text-red-500"> *</span>}
                </th>
              ))}
              <th className="px-2 py-2 font-medium">Status</th>
              <th className="w-10 px-2 py-2"><span className="sr-only">Remove</span></th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {rows.map((r, index) => {
              const s = statuses[index];
              const email = normalizeEmail(r.email);
              const also = otherSources?.get(email);
              const emailError = s.state === 'invalid' || s.state === 'missing';
              return (
                <tr key={r.id} className={clsx(s.state === 'repeated' && 'bg-amber-50/40')}>
                  <td className="px-2 py-1.5 text-right text-xs tabular-nums text-slate-400">{index + 1}</td>
                  {COLUMN_KEYS.map((column, ci) => (
                    <td key={column} className="px-2 py-1.5">
                      <input
                        ref={(el) => {
                          const key = `${r.id}:${column}`;
                          if (el) cells.current.set(key, el);
                          else cells.current.delete(key);
                        }}
                        type="text"
                        inputMode={column === 'email' ? 'email' : undefined}
                        autoComplete="off"
                        spellCheck={false}
                        className={clsx(
                          'input py-1.5 text-xs',
                          column === 'email' && emailError && 'border-red-400 focus:border-red-500 focus:ring-red-500/10',
                        )}
                        value={r[column]}
                        maxLength={column === 'email' ? 320 : 255}
                        placeholder={index === 0 || column === 'email' ? placeholders[column] : undefined}
                        aria-label={`${TABLE_COLUMNS[ci]}, row ${index + 1}`}
                        aria-invalid={column === 'email' && emailError ? true : undefined}
                        onChange={(e) => setCell(r.id, column, e.target.value)}
                        onBlur={column === 'email' ? () => onEmailBlur(r.id) : undefined}
                        onKeyDown={(e) => onCellKeyDown(e, index)}
                        onPaste={(e) => onCellPaste(e, index)}
                      />
                      {column !== 'email' && s.contact && (
                        <p className="mt-0.5 truncate text-[11px] text-slate-400" title="Saved contacts keep their saved details">
                          Saved: {(column === 'name'
                            ? s.contact.name || [s.contact.firstName, s.contact.lastName].filter(Boolean).join(' ')
                            : s.contact.company) || '—'}
                        </p>
                      )}
                    </td>
                  ))}
                  <td className="px-2 py-1.5" title={s.detail}>
                    {statusBadge(s, pending.has(email))}
                    {s.detail && s.state !== 'new' && s.state !== 'saved' && (
                      <p className="mt-0.5 max-w-[14rem] text-[11px] leading-snug text-slate-400">{s.detail}</p>
                    )}
                    {also && s.state !== 'repeated' && (
                      <p className="mt-0.5 text-[11px] text-amber-700" title="It is emailed once">
                        Repeated: also in {also.map((x) => SOURCE_LABEL[x].toLowerCase()).join(', ')}
                      </p>
                    )}
                  </td>
                  <td className="px-2 py-1.5 text-right">
                    <button
                      type="button"
                      onClick={() => removeRow(r.id)}
                      className="text-slate-300 transition hover:text-red-500"
                      aria-label={`Remove row ${index + 1}`}
                      title="Remove this row"
                    >
                      <X className="h-4 w-4" />
                    </button>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 bg-slate-50 px-3 py-2 text-xs text-slate-500">
          <div className="flex flex-wrap items-center gap-2">
            <Button type="button" size="sm" variant="secondary" onClick={addRow} disabled={rows.length >= MANUAL_LIMIT}>
              <Plus className="h-4 w-4" /> Add row
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setShowPaste((v) => !v)} aria-expanded={showPaste}>
              <ClipboardList className="h-4 w-4" /> Paste a list
            </Button>
            {rows.some((r) => r.email.trim() || r.name.trim() || r.company.trim()) && (
              <button type="button" className="font-medium text-red-600 hover:underline" onClick={clearTable}>
                Clear table
              </button>
            )}
          </div>
          <span>
            <b className="text-slate-700">{summary.ready.toLocaleString()}</b> ready ({summary.saved} saved, {summary.fresh} new)
            {summary.skipped > 0 && ` · ${summary.skipped} will be skipped`}
            {summary.unused > 0 && ` · ${summary.unused} not used`}
          </span>
        </div>
      </div>

      {checkError && (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
          <span className="flex items-start gap-2"><AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" /> {checkError}</span>
          <Button type="button" size="sm" variant="secondary" onClick={retryChecks}>
            <RotateCcw className="h-3.5 w-3.5" /> Check again
          </Button>
        </div>
      )}

      {showPaste && (
        <div className="rounded-xl border border-slate-200 p-3">
          <label htmlFor="manual-paste" className="label">Paste a list of addresses</label>
          <textarea
            id="manual-paste"
            className="input min-h-[80px] resize-y font-mono text-xs"
            placeholder={'ada@example.com, alan@example.com; grace@example.com\nAda Lovelace <ada@example.com>'}
            value={pasteText}
            onChange={(e) => setPasteText(e.target.value)}
          />
          <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
            <p className="text-xs text-slate-400">
              Separate addresses with commas, semicolons, spaces or new lines. Each becomes a row in the table.
            </p>
            <Button type="button" size="sm" onClick={addPastedList} disabled={!pasteText.trim()}>
              <Plus className="h-4 w-4" /> Add to table
            </Button>
          </div>
        </div>
      )}

      {report && (
        <div className="space-y-1 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600">
          <p className="flex items-center gap-1.5">
            <CheckCircle2 className="h-3.5 w-3.5 text-emerald-500" /> Added {report.added} row{report.added === 1 ? '' : 's'}.
          </p>
          {report.invalid.length > 0 && (
            <p className="text-red-600">
              Not valid email addresses ({report.invalid.length}), not added: {report.invalid.slice(0, 5).join(', ')}
              {report.invalid.length > 5 ? '…' : ''}
            </p>
          )}
          {report.repeats > 0 && <p>{report.repeats} repeated address{report.repeats === 1 ? ' was' : 'es were'} added once.</p>}
          {report.alreadyInTable > 0 && <p>{report.alreadyInTable} already in the table.</p>}
          {report.overLimit > 0 && (
            <p className="text-amber-700">{report.overLimit} not added: the table holds at most {MANUAL_LIMIT.toLocaleString()} rows. Upload a file for larger lists.</p>
          )}
        </div>
      )}

      <p className="flex items-start gap-2 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs text-slate-500">
        <Info className="mt-0.5 h-4 w-4 shrink-0 text-slate-400" />
        <span>
          Only these rows (and any other source you select) receive the email. Addresses that already belong to saved
          contacts reuse those contacts, and unsubscribed contacts are skipped. Other addresses are used for this
          campaign only: they're stored with the campaign and aren't added to your Recipients list. On the Review step
          you can choose to also save them as contacts. Tip: rows copied from Excel or Google Sheets (Email, Name,
          Company) can be pasted into any cell.
        </span>
      </p>
    </div>
  );
}
