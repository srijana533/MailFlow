import { useCallback, useMemo, useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import {
  UploadCloud, FileSpreadsheet, Download, X, CheckCircle2,
  AlertTriangle, Copy, Trash2, Loader2,
} from 'lucide-react';
import {
  recipientsApi,
  type ExcelPreviewResponse,
  type ExcelRowPreview,
} from '../api/recipients';
import { Button } from './ui';

interface RecipientExcelUploadProps {
  /**
   * Called whenever the set of kept rows changes. Receives only the rows the
   * user intends to keep (VALID rows, minus any manually removed). The parent
   * decides when to persist them (e.g. after the campaign is created).
   */
  onValidRowsChange: (rows: ExcelRowPreview[]) => void;
}

const MAX_FILE_BYTES = 10 * 1024 * 1024; // 10 MB
const ACCEPT = '.xlsx,.xls,.csv';
const PREVIEW_LIMIT = 10;

function statusPill(status: ExcelRowPreview['status']) {
  switch (status) {
    case 'VALID':
      return (
        <span className="badge bg-emerald-50 text-emerald-700">
          <CheckCircle2 className="h-3 w-3" /> Valid
        </span>
      );
    case 'DUPLICATE':
      return (
        <span className="badge bg-amber-50 text-amber-700">
          <Copy className="h-3 w-3" /> Duplicate
        </span>
      );
    default:
      return (
        <span className="badge bg-red-50 text-red-700">
          <AlertTriangle className="h-3 w-3" /> Invalid
        </span>
      );
  }
}

export function RecipientExcelUpload({ onValidRowsChange }: RecipientExcelUploadProps) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [preview, setPreview] = useState<ExcelPreviewResponse | null>(null);
  const [fileName, setFileName] = useState<string>('');
  // Row numbers the user has explicitly removed from the kept set.
  const [removed, setRemoved] = useState<Set<number>>(new Set());

  const previewMutation = useMutation({
    mutationFn: (file: File) => recipientsApi.previewUpload(file),
    onSuccess: (data) => {
      setPreview(data);
      setRemoved(new Set());
      emitValid(data, new Set());
      if (!data.hasEmailColumn) {
        toast.error('No "Email" column detected. Check your header row.');
      } else if (data.validRows === 0) {
        toast('No new valid recipients found in this file.', { icon: '⚠️' });
      } else {
        toast.success(`${data.validRows} valid recipient${data.validRows === 1 ? '' : 's'} ready`);
      }
    },
    onError: (e: unknown) => {
      const msg =
        (e as { response?: { data?: { message?: string } } }).response?.data?.message ??
        'Could not read that file. Make sure it is a valid .xlsx, .xls or .csv.';
      toast.error(msg);
      setPreview(null);
    },
  });

  const emitValid = useCallback(
    (data: ExcelPreviewResponse | null, removedSet: Set<number>) => {
      if (!data) {
        onValidRowsChange([]);
        return;
      }
      const kept = data.rows.filter(
        (r) => r.status === 'VALID' && !removedSet.has(r.rowNumber)
      );
      onValidRowsChange(kept);
    },
    [onValidRowsChange]
  );

  const handleFile = useCallback(
    (file: File) => {
      const name = file.name.toLowerCase();
      const okExt = ['.xlsx', '.xls', '.csv'].some((ext) => name.endsWith(ext));
      if (!okExt) {
        toast.error('Unsupported file type. Use .xlsx, .xls or .csv.');
        return;
      }
      if (file.size > MAX_FILE_BYTES) {
        toast.error('File is too large (max 10 MB).');
        return;
      }
      setFileName(file.name);
      previewMutation.mutate(file);
    },
    [previewMutation]
  );

  const onDrop = useCallback(
    (e: React.DragEvent) => {
      e.preventDefault();
      setDragging(false);
      const file = e.dataTransfer.files?.[0];
      if (file) handleFile(file);
    },
    [handleFile]
  );

  const removeRow = (rowNumber: number) => {
    setRemoved((prev) => {
      const next = new Set(prev);
      next.add(rowNumber);
      emitValid(preview, next);
      return next;
    });
  };

  const removeAllInvalid = () => {
    if (!preview) return;
    const next = new Set(removed);
    preview.rows
      .filter((r) => r.status !== 'VALID')
      .forEach((r) => next.add(r.rowNumber));
    setRemoved(next);
    emitValid(preview, next);
    toast.success('Removed invalid and duplicate rows');
  };

  const reset = () => {
    setPreview(null);
    setFileName('');
    setRemoved(new Set());
    onValidRowsChange([]);
    if (inputRef.current) inputRef.current.value = '';
  };

  const keptValidCount = useMemo(() => {
    if (!preview) return 0;
    return preview.rows.filter(
      (r) => r.status === 'VALID' && !removed.has(r.rowNumber)
    ).length;
  }, [preview, removed]);

  const visibleRows = useMemo(() => {
    if (!preview) return [];
    return preview.rows.filter((r) => !removed.has(r.rowNumber)).slice(0, PREVIEW_LIMIT);
  }, [preview, removed]);

  return (
    <div className="space-y-4">
      {/* Dropzone */}
      {!preview && (
        <div
          onDragOver={(e) => {
            e.preventDefault();
            setDragging(true);
          }}
          onDragLeave={() => setDragging(false)}
          onDrop={onDrop}
          className={`relative flex flex-col items-center justify-center rounded-xl border-2 border-dashed px-6 py-10 text-center transition ${
            dragging
              ? 'border-brand-400 bg-brand-50/60'
              : 'border-slate-200 bg-slate-50/60 hover:border-slate-300'
          }`}
        >
          {previewMutation.isPending ? (
            <>
              <Loader2 className="mb-3 h-8 w-8 animate-spin text-brand-500" />
              <p className="text-sm font-medium text-slate-600">Reading {fileName}…</p>
            </>
          ) : (
            <>
              <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-xl bg-brand-50 text-brand-600">
                <UploadCloud className="h-6 w-6" />
              </div>
              <p className="text-sm font-medium text-slate-700">
                Drag &amp; drop an Excel or CSV file here
              </p>
              <p className="mt-1 text-xs text-slate-400">
                .xlsx, .xls or .csv — up to 10 MB. Must include an <b>Email</b> column.
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
                    recipientsApi
                      .downloadTemplate()
                      .catch(() => toast.error('Could not download template'))
                  }
                >
                  <Download className="h-4 w-4" /> Download template
                </Button>
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
            }}
          />
        </div>
      )}

      {/* Preview */}
      {preview && (
        <div className="space-y-4">
          {/* File + summary header */}
          <div className="flex flex-wrap items-center justify-between gap-3 rounded-xl border border-slate-200 bg-white px-4 py-3">
            <div className="flex items-center gap-2 min-w-0">
              <FileSpreadsheet className="h-5 w-5 shrink-0 text-brand-500" />
              <span className="truncate text-sm font-medium text-slate-700">{fileName}</span>
            </div>
            <button
              type="button"
              onClick={reset}
              className="inline-flex items-center gap-1 text-xs font-medium text-slate-500 hover:text-slate-700"
            >
              <X className="h-3.5 w-3.5" /> Choose a different file
            </button>
          </div>

          {/* Count chips */}
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <SummaryChip label="Total rows" value={preview.totalRows} tone="text-slate-700 bg-slate-50" />
            <SummaryChip label="Valid" value={keptValidCount} tone="text-emerald-700 bg-emerald-50" />
            <SummaryChip label="Invalid" value={preview.invalidRows} tone="text-red-700 bg-red-50" />
            <SummaryChip label="Duplicate" value={preview.duplicateRows} tone="text-amber-700 bg-amber-50" />
          </div>

          {!preview.hasEmailColumn && (
            <div className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700">
              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>
                No <b>Email</b> column was detected. Rename your email column header to
                “Email” (or email / e-mail) and re-upload.
              </span>
            </div>
          )}

          {/* Actions */}
          {(preview.invalidRows > 0 || preview.duplicateRows > 0) && (
            <div className="flex items-center justify-between rounded-lg bg-slate-50 px-3 py-2">
              <span className="text-xs text-slate-500">
                Invalid and duplicate rows are not sent. You can remove them from the preview.
              </span>
              <Button type="button" size="sm" variant="ghost" onClick={removeAllInvalid}>
                <Trash2 className="h-4 w-4" /> Remove all
              </Button>
            </div>
          )}

          {/* Preview table (first N kept rows) */}
          <div className="overflow-hidden rounded-xl border border-slate-200">
            <table className="w-full text-left text-sm">
              <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
                <tr>
                  <th className="px-3 py-2 font-medium">#</th>
                  <th className="px-3 py-2 font-medium">Email</th>
                  <th className="px-3 py-2 font-medium">Name</th>
                  <th className="px-3 py-2 font-medium">Company</th>
                  <th className="px-3 py-2 font-medium">Status</th>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {visibleRows.map((r) => (
                  <tr key={r.rowNumber} className="hover:bg-slate-50/60">
                    <td className="px-3 py-2 tabular-nums text-slate-400">{r.rowNumber}</td>
                    <td className="px-3 py-2 font-medium text-slate-700">{r.email || '—'}</td>
                    <td className="px-3 py-2 text-slate-600">{r.name || '—'}</td>
                    <td className="px-3 py-2 text-slate-600">{r.company || '—'}</td>
                    <td className="px-3 py-2">
                      {statusPill(r.status)}
                      {r.reason && (
                        <span className="ml-2 text-xs text-slate-400">{r.reason}</span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right">
                      <button
                        type="button"
                        onClick={() => removeRow(r.rowNumber)}
                        className="text-slate-300 transition hover:text-red-500"
                        aria-label={`Remove row ${r.rowNumber}`}
                      >
                        <X className="h-4 w-4" />
                      </button>
                    </td>
                  </tr>
                ))}
                {visibleRows.length === 0 && (
                  <tr>
                    <td colSpan={6} className="px-3 py-6 text-center text-sm text-slate-400">
                      No rows to show.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
            {preview.rows.filter((r) => !removed.has(r.rowNumber)).length > PREVIEW_LIMIT && (
              <div className="border-t border-slate-100 bg-slate-50 px-3 py-2 text-center text-xs text-slate-400">
                Showing first {PREVIEW_LIMIT} of{' '}
                {preview.rows.filter((r) => !removed.has(r.rowNumber)).length} rows
              </div>
            )}
          </div>

          <p className="text-xs text-slate-500">
            <b className="text-slate-700">{keptValidCount}</b> valid recipient
            {keptValidCount === 1 ? '' : 's'} will be added to this campaign.
          </p>
        </div>
      )}
    </div>
  );
}

function SummaryChip({ label, value, tone }: { label: string; value: number; tone: string }) {
  return (
    <div className={`rounded-lg px-3 py-2 ${tone}`}>
      <p className="text-lg font-bold tabular-nums">{value.toLocaleString()}</p>
      <p className="text-xs font-medium opacity-80">{label}</p>
    </div>
  );
}
