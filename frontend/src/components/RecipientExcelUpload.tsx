import { useCallback, useMemo, useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import {
  UploadCloud, FileSpreadsheet, Download, X, CheckCircle2, AlertTriangle, Copy, Loader2,
  UserCheck, RotateCcw, Columns3, Info, MailX, Table2,
} from 'lucide-react';
import {
  recipientsApi,
  UPLOAD_MAX_BYTES,
  UPLOAD_MAX_ROWS,
  type ColumnMapping,
  type ExcelPreviewResponse,
  type ExcelRowPreview,
} from '../api/recipients';
import {
  KNOWN_CUSTOM_FIELDS,
  keptUploadRows,
  placeholdersForTarget,
  suggestCustomKey,
  targetLabel,
  type UploadSelection,
} from '../lib/recipientSelection';
import { apiErrorMessage } from '../lib/errors';
import { Button } from './ui';

interface RecipientExcelUploadProps {
  /** Current upload (file + preview + mapping + removed rows), owned by the parent. */
  value: UploadSelection | null;
  onChange: (next: UploadSelection | null) => void;
  /** Placeholders used by the email content, to show which ones the file fills. */
  templatePlaceholders?: string[];
  /** "Fill in a table": shows the manual-entry table (the template's columns) instead of a file. */
  onFillTable?: () => void;
}

const ACCEPT = '.xlsx,.xls,.csv';
const PAGE = 25;
const MAX_VISIBLE = 500;
const STANDARD_TARGETS = ['email', 'name', 'firstName', 'lastName', 'company'];

type RowFilter = 'all' | 'used' | 'problems' | 'removed';

function statusPill(row: ExcelRowPreview, removed: boolean) {
  if (removed) {
    return <span className="badge bg-slate-100 text-slate-500"><X className="h-3 w-3" /> Removed</span>;
  }
  switch (row.status) {
    case 'VALID':
      return <span className="badge bg-emerald-50 text-emerald-700"><CheckCircle2 className="h-3 w-3" /> New address</span>;
    case 'EXISTING':
      return <span className="badge bg-brand-50 text-brand-700"><UserCheck className="h-3 w-3" /> Saved contact</span>;
    case 'SKIPPED':
      // The reason (e.g. "Unsubscribed: …") is shown under the pill.
      return <span className="badge bg-slate-100 text-slate-600"><MailX className="h-3 w-3" /> Won't be emailed</span>;
    case 'DUPLICATE':
      return <span className="badge bg-amber-50 text-amber-700"><Copy className="h-3 w-3" /> Duplicate</span>;
    default:
      return <span className="badge bg-red-50 text-red-700"><AlertTriangle className="h-3 w-3" /> Invalid</span>;
  }
}

export function RecipientExcelUpload({ value, onChange, templatePlaceholders = [], onFillTable }: RecipientExcelUploadProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [pendingName, setPendingName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<RowFilter>('all');
  const [visible, setVisible] = useState(PAGE);
  const [showColumns, setShowColumns] = useState(false);

  const previewMutation = useMutation({
    mutationFn: (args: { file: File; mapping: ColumnMapping }) =>
      recipientsApi.previewUpload(args.file, args.mapping),
    onSuccess: (preview, args) => {
      setError(null);
      // Row numbers are stable for the same file, so removals survive a mapping change.
      const sameFile = !!value && value.file === args.file;
      onChange({
        file: args.file,
        preview,
        mapping: args.mapping,
        removedRows: sameFile && value ? value.removedRows : [],
      });
      if (!sameFile) {
        setFilter('all');
        setVisible(PAGE);
        // Open the column panel straight away when the email column wasn't found.
        setShowColumns(!preview.hasEmailColumn);
      }
      // validRows counts every address that can be emailed: new ones and saved contacts.
      const usable = preview.validRows;
      if (!preview.hasEmailColumn) {
        toast.error('No email column was found. Choose which column holds the email addresses.', { id: 'upload-preview' });
      } else if (usable === 0) {
        toast('No usable addresses in this file.', { id: 'upload-preview', icon: '⚠️' });
      } else {
        toast.success(
          `${usable.toLocaleString()} valid address${usable === 1 ? '' : 'es'} ready from ${preview.fileName} ` +
            `(${preview.newRows.toLocaleString()} new, ${preview.existingRows.toLocaleString()} saved)`,
          { id: 'upload-preview' },
        );
      }
    },
    onError: (e: unknown) => {
      const msg = apiErrorMessage(e, 'Could not read that file. Make sure it is a valid .xlsx, .xls or .csv file.');
      setError(msg);
      toast.error(msg, { id: 'upload-preview' });
    },
  });

  const handleFile = useCallback(
    (file: File) => {
      const name = file.name.toLowerCase();
      if (!['.xlsx', '.xls', '.csv'].some((ext) => name.endsWith(ext))) {
        setError('Unsupported file type. Use .xlsx, .xls or .csv.');
        return;
      }
      if (file.size === 0) {
        setError('This file is empty.');
        return;
      }
      if (file.size > UPLOAD_MAX_BYTES) {
        setError('This file is larger than 5 MB. Upload a smaller file.');
        return;
      }
      setError(null);
      setPendingName(file.name);
      previewMutation.mutate({ file, mapping: {} });
    },
    [previewMutation],
  );

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragging(false);
    const file = e.dataTransfer.files?.[0];
    if (file) handleFile(file);
  };

  const changeMapping = (header: string, target: string) => {
    if (!value) return;
    const mapping: ColumnMapping = { ...value.mapping, [header]: target };
    // One column per field: release the field from any other explicitly mapped column.
    if (target !== 'ignore') {
      for (const [h, t] of Object.entries(mapping)) {
        if (h !== header && t === target) mapping[h] = 'ignore';
      }
    }
    previewMutation.mutate({ file: value.file, mapping });
  };

  const setRemoved = (rowNumbers: number[], removed: boolean) => {
    if (!value) return;
    const set = new Set(value.removedRows);
    rowNumbers.forEach((n) => (removed ? set.add(n) : set.delete(n)));
    onChange({ ...value, removedRows: Array.from(set) });
  };

  const reset = () => {
    onChange(null);
    setError(null);
    if (inputRef.current) inputRef.current.value = '';
  };

  const preview: ExcelPreviewResponse | undefined = value?.preview;
  const removedSet = useMemo(() => new Set(value?.removedRows ?? []), [value?.removedRows]);
  const kept = useMemo(() => keptUploadRows(value), [value]);
  const keptNew = kept.filter((r) => r.status === 'VALID').length;
  const keptExisting = kept.length - keptNew;

  const filteredRows = useMemo(() => {
    if (!preview) return [];
    return preview.rows.filter((r) => {
      const isRemoved = removedSet.has(r.rowNumber);
      const usable = r.status === 'VALID' || r.status === 'EXISTING';
      switch (filter) {
        case 'used': return usable && !isRemoved;
        case 'problems': return !usable;
        case 'removed': return isRemoved;
        default: return true;
      }
    });
  }, [preview, removedSet, filter]);

  // Placeholder coverage for the selected template.
  const provided = useMemo(() => {
    const out = new Set<string>();
    Object.values(preview?.detectedColumns ?? {}).forEach((t) =>
      placeholdersForTarget(t).forEach((p) => out.add(p)),
    );
    return out;
  }, [preview]);
  const notFilled = templatePlaceholders.filter((p) => !provided.has(p));

  const firstRowValue = (header: string) => {
    // Raw values aren't returned per column, so show the mapped value of the first row when known.
    const target = preview?.detectedColumns[header];
    if (!target || !preview?.rows.length) return '';
    const key = target.startsWith('custom:') ? target.slice(7) : target;
    return preview.rows[0].fields?.[key] ?? '';
  };

  // ── Dropzone ────────────────────────────────────────────────────────────────
  if (!value) {
    return (
      <div className="space-y-3">
        <div
          onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
          className={`relative flex flex-col items-center justify-center rounded-xl border-2 border-dashed px-6 py-10 text-center transition ${
            dragging ? 'border-brand-400 bg-brand-50/60' : 'border-slate-200 bg-slate-50/60 hover:border-slate-300'
          }`}
        >
          {previewMutation.isPending ? (
            <>
              <Loader2 className="mb-3 h-8 w-8 animate-spin text-brand-500" />
              <p className="text-sm font-medium text-slate-600">Reading {pendingName}…</p>
            </>
          ) : (
            <>
              <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-50 text-brand-600">
                <UploadCloud className="h-6 w-6" />
              </div>
              <p className="text-sm font-medium text-slate-700">Drag &amp; drop an Excel or CSV file here</p>
              <p className="mt-1 text-xs text-slate-400">
                .xlsx, .xls or .csv, up to 5 MB and {UPLOAD_MAX_ROWS.toLocaleString()} rows. The first row must contain
                column names. The template has the columns Email, Name and Company; other columns can be mapped too.
              </p>
              <div className="mt-4 flex flex-wrap items-center justify-center gap-2">
                <Button type="button" size="sm" onClick={() => inputRef.current?.click()}>
                  <FileSpreadsheet className="h-4 w-4" /> Browse files
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="ghost"
                  onClick={() =>
                    recipientsApi.downloadTemplate().catch(() =>
                      toast.error('Could not download the template', { id: 'template-download' }),
                    )
                  }
                >
                  <Download className="h-4 w-4" /> Download template
                </Button>
                {onFillTable && (
                  <Button type="button" size="sm" variant="secondary" onClick={onFillTable}>
                    <Table2 className="h-4 w-4" /> Fill in a table
                  </Button>
                )}
              </div>
            </>
          )}
          <input
            ref={inputRef}
            type="file"
            accept={ACCEPT}
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) handleFile(file);
              e.target.value = '';
            }}
          />
        </div>
        {error && (
          <p className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" /> {error}
          </p>
        )}
      </div>
    );
  }

  // ── Preview ─────────────────────────────────────────────────────────────────
  const p = value.preview;
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white px-4 py-3">
        <div className="flex min-w-0 items-center gap-2">
          <FileSpreadsheet className="h-5 w-5 shrink-0 text-brand-500" />
          <span className="truncate text-sm font-medium text-slate-700">{p.fileName}</span>
          {previewMutation.isPending && <Loader2 className="h-4 w-4 animate-spin text-brand-500" />}
        </div>
        <button
          type="button"
          onClick={reset}
          disabled={previewMutation.isPending}
          className="inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-700 disabled:opacity-50"
        >
          <X className="h-3.5 w-3.5" /> Remove file
        </button>
      </div>

      {error && (
        <p className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" /> {error}
        </p>
      )}

      {/* Counts for the whole file. Valid = can be emailed: new addresses plus saved contacts. */}
      <div className={`grid grid-cols-2 gap-3 ${p.skippedRows > 0 ? 'sm:grid-cols-5' : 'sm:grid-cols-4'}`}>
        <SummaryChip label="Total rows" value={p.totalRows} tone="text-slate-700 bg-slate-50" />
        <SummaryChip
          label="Valid"
          value={p.validRows}
          detail={`${p.newRows.toLocaleString()} new · ${p.existingRows.toLocaleString()} saved contact${p.existingRows === 1 ? '' : 's'}`}
          tone="text-emerald-700 bg-emerald-50"
        />
        <SummaryChip label="Invalid" value={p.invalidRows} detail="reason on each row" tone="text-red-700 bg-red-50" />
        <SummaryChip label="Duplicate" value={p.duplicateRows} detail="repeats within this file" tone="text-amber-700 bg-amber-50" />
        {p.skippedRows > 0 && (
          <SummaryChip label="Won't be emailed" value={p.skippedRows} detail="unsubscribed or bounced" tone="text-slate-700 bg-slate-100" />
        )}
      </div>

      {!p.hasEmailColumn && (
        <div className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <span>No email column was detected. In <b>Columns</b> below, set the column that holds the addresses to <b>Email</b>.</span>
        </div>
      )}

      {/* Column mapping */}
      <div className="rounded-xl border border-slate-200">
        <button
          type="button"
          onClick={() => setShowColumns((s) => !s)}
          className="flex w-full items-center justify-between px-4 py-2.5 text-left text-sm font-medium text-slate-700"
        >
          <span className="flex items-center gap-2">
            <Columns3 className="h-4 w-4 text-slate-400" /> Columns ({p.columns.length}) and placeholders
          </span>
          <span className="text-xs text-brand-600">{showColumns ? 'Hide' : 'Review mapping'}</span>
        </button>
        {showColumns && (
          <div className="border-t border-slate-100">
            <table className="w-full text-left text-sm">
              <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-3 py-2 font-medium">Column in file</th>
                  <th className="px-3 py-2 font-medium">Used as</th>
                  <th className="px-3 py-2 font-medium">Fills placeholders</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {p.columns.map((header) => {
                  const current = p.detectedColumns[header] ?? 'ignore';
                  const suggested = `custom:${suggestCustomKey(header)}`;
                  const options = [
                    ...STANDARD_TARGETS,
                    ...KNOWN_CUSTOM_FIELDS.map((k) => `custom:${k}`),
                    ...(KNOWN_CUSTOM_FIELDS.includes(suggested.slice(7).toLowerCase()) ? [] : [suggested]),
                    'ignore',
                  ];
                  if (!options.includes(current)) options.splice(options.length - 1, 0, current);
                  const sample = firstRowValue(header);
                  return (
                    <tr key={header}>
                      <td className="px-3 py-2">
                        <p className="font-medium text-slate-700">{header}</p>
                        {sample && <p className="max-w-[14rem] truncate text-xs text-slate-400">e.g. {sample}</p>}
                      </td>
                      <td className="px-3 py-2">
                        <select
                          className="input py-1.5 text-xs"
                          value={current}
                          disabled={previewMutation.isPending}
                          onChange={(e) => changeMapping(header, e.target.value)}
                          aria-label={`Field for column ${header}`}
                        >
                          {options.map((o) => (
                            <option key={o} value={o}>
                              {o === suggested && !KNOWN_CUSTOM_FIELDS.includes(o.slice(7))
                                ? `Custom field {{${o.slice(7)}}}`
                                : targetLabel(o)}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td className="px-3 py-2">
                        <div className="flex flex-wrap gap-1">
                          {placeholdersForTarget(current).map((ph) => (
                            <code key={ph} className="rounded bg-accent-50 px-1.5 py-0.5 text-xs text-accent-700">{`{{${ph}}}`}</code>
                          ))}
                          {current === 'ignore' && <span className="text-xs text-slate-400">Not used</span>}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {templatePlaceholders.length > 0 && (
              <div className="border-t border-slate-100 px-4 py-2.5 text-xs">
                {notFilled.length === 0 ? (
                  <p className="flex items-center gap-1.5 text-emerald-700">
                    <CheckCircle2 className="h-3.5 w-3.5" /> This file fills every placeholder in the email.
                  </p>
                ) : (
                  <p className="flex items-start gap-1.5 text-amber-700">
                    <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <span>
                      Not filled by this file:{' '}
                      {notFilled.map((ph) => <code key={ph} className="mr-1">{`{{${ph}}}`}</code>)}
                      These render as blank text for new addresses from this file. Saved contacts use their stored details.
                    </span>
                  </p>
                )}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Row filter */}
      <div className="flex flex-wrap items-center gap-1.5 text-xs">
        {([
          ['all', `All rows (${p.totalRows})`],
          ['used', `Used (${kept.length})`],
          ['problems', `Not usable (${p.invalidRows + p.duplicateRows + p.skippedRows})`],
          ['removed', `Removed by you (${value.removedRows.length})`],
        ] as [RowFilter, string][]).map(([key, label]) => (
          <button
            key={key}
            type="button"
            onClick={() => { setFilter(key); setVisible(PAGE); }}
            className={`rounded-full px-3 py-1 font-medium transition ${
              filter === key ? 'bg-brand-600 text-white' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
            }`}
          >
            {label}
          </button>
        ))}
        {value.removedRows.length > 0 && (
          <button
            type="button"
            onClick={() => setRemoved(value.removedRows, false)}
            className="ml-auto inline-flex items-center gap-1 font-medium text-brand-600 hover:underline"
          >
            <RotateCcw className="h-3.5 w-3.5" /> Restore all removed
          </button>
        )}
      </div>

      <div className="overflow-hidden rounded-xl border border-slate-200">
        <table className="w-full text-left text-sm">
          <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-3 py-2 font-medium">Row</th>
              <th className="px-3 py-2 font-medium">Email</th>
              <th className="px-3 py-2 font-medium">Name</th>
              <th className="px-3 py-2 font-medium">Company</th>
              <th className="px-3 py-2 font-medium">Status</th>
              <th className="px-3 py-2" />
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {filteredRows.slice(0, Math.min(visible, MAX_VISIBLE)).map((r) => {
              const isRemoved = removedSet.has(r.rowNumber);
              const usable = r.status === 'VALID' || r.status === 'EXISTING';
              return (
                <tr key={r.rowNumber} className={isRemoved ? 'bg-slate-50/80 text-slate-400' : 'hover:bg-slate-50/60'}>
                  <td className="px-3 py-2 tabular-nums text-slate-400">{r.rowNumber}</td>
                  <td className="px-3 py-2 font-medium text-slate-700">{r.email || '—'}</td>
                  <td className="px-3 py-2 text-slate-600">{r.name || '—'}</td>
                  <td className="px-3 py-2 text-slate-600">{r.company || '—'}</td>
                  <td className="px-3 py-2">
                    {statusPill(r, isRemoved)}
                    {r.reason && !isRemoved && <p className="mt-0.5 text-xs text-slate-400">{r.reason}</p>}
                  </td>
                  <td className="px-3 py-2 text-right">
                    {usable && !isRemoved && (
                      <button
                        type="button"
                        onClick={() => setRemoved([r.rowNumber], true)}
                        className="text-slate-300 transition hover:text-red-500"
                        aria-label={`Remove row ${r.rowNumber}`}
                        title="Remove from this campaign"
                      >
                        <X className="h-4 w-4" />
                      </button>
                    )}
                    {isRemoved && (
                      <button
                        type="button"
                        onClick={() => setRemoved([r.rowNumber], false)}
                        className="text-xs font-medium text-brand-600 hover:underline"
                      >
                        Restore
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
            {filteredRows.length === 0 && (
              <tr>
                <td colSpan={6} className="px-3 py-6 text-center text-sm text-slate-400">No rows to show.</td>
              </tr>
            )}
          </tbody>
        </table>
        {filteredRows.length > Math.min(visible, MAX_VISIBLE) && (
          <div className="flex items-center justify-center gap-3 border-t border-slate-100 bg-slate-50 px-3 py-2 text-xs text-slate-500">
            Showing {Math.min(visible, MAX_VISIBLE).toLocaleString()} of {filteredRows.length.toLocaleString()} rows
            {visible < MAX_VISIBLE && (
              <button type="button" className="font-medium text-brand-600 hover:underline" onClick={() => setVisible((v) => v + PAGE * 4)}>
                Show more
              </button>
            )}
          </div>
        )}
      </div>

      <div className="space-y-1 text-xs text-slate-500">
        <p>
          <b className="text-slate-700">{kept.length.toLocaleString()}</b> address{kept.length === 1 ? '' : 'es'} from this file will be used:{' '}
          {keptNew.toLocaleString()} new, {keptExisting.toLocaleString()} saved contact{keptExisting === 1 ? '' : 's'}.
          Saved contacts can be used in any number of campaigns. Invalid and duplicate rows are never emailed.
        </p>
        {p.skippedRows > 0 && (
          <p>
            {p.skippedRows.toLocaleString()} row{p.skippedRows === 1 ? '' : 's'} won't be emailed because the address is
            unsubscribed or the saved contact bounced or is marked invalid. Each row shows the reason.
          </p>
        )}
        {keptNew > 0 && (
          <p>
            New addresses are used for this campaign only: they're stored with the campaign and aren't added to your
            Recipients list. On the Review step you can choose to also save them as contacts.
          </p>
        )}
        {p.invalidRows > 0 && (
          <p>To include a corrected address, fix it in the file and upload it again, or add it on the <b>Add emails manually</b> tab.</p>
        )}
      </div>
    </div>
  );
}

function SummaryChip({ label, value, detail, tone }: { label: string; value: number; detail?: string; tone: string }) {
  return (
    <div className={`rounded-lg px-3 py-2 ${tone}`}>
      <p className="text-lg font-bold tabular-nums">{value.toLocaleString()}</p>
      <p className="text-xs font-medium opacity-80">{label}</p>
      {detail && <p className="mt-0.5 text-[11px] opacity-70">{detail}</p>}
    </div>
  );
}
