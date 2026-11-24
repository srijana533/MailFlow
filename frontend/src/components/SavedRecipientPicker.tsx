import { useEffect, useMemo, useRef, useState } from 'react';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import { Search, Loader2, Users, AlertTriangle } from 'lucide-react';
import { recipientsApi } from '../api/recipients';
import type { Recipient } from '../types';
import { apiErrorMessage } from '../lib/errors';
import { Button } from './ui';

interface SavedRecipientPickerProps {
  /** Selected saved contacts by ID (owned by the parent). */
  selected: Record<number, Recipient>;
  onChange: (next: Record<number, Recipient>) => void;
}

const PAGE_SIZE = 20;
/** "Select all matching" loads at most this many contacts. */
const SELECT_ALL_LIMIT = 10_000;

/**
 * Lists saved contacts with search (name, email or company), pagination and
 * multi-selection. Only ACTIVE contacts are listed because unsubscribed or
 * bounced contacts are never emailed.
 */
export function SavedRecipientPicker({ selected, onChange }: SavedRecipientPickerProps) {
  const [search, setSearch] = useState('');
  const [debounced, setDebounced] = useState('');
  const [page, setPage] = useState(0);
  const [selectingAll, setSelectingAll] = useState(false);
  const headerBox = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      setDebounced(search.trim());
      setPage(0);
    }, 300);
    return () => clearTimeout(t);
  }, [search]);

  const { data, isLoading, isFetching, isError, error, refetch } = useQuery({
    queryKey: ['recipients', 'picker', debounced, page],
    queryFn: () =>
      recipientsApi.list({ search: debounced || undefined, status: 'ACTIVE', page, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });

  const rows = useMemo(() => data?.content ?? [], [data]);
  const selectedCount = Object.keys(selected).length;
  const pageSelected = rows.filter((r) => selected[r.id]).length;
  const allOnPage = rows.length > 0 && pageSelected === rows.length;

  useEffect(() => {
    if (headerBox.current) headerBox.current.indeterminate = pageSelected > 0 && !allOnPage;
  }, [pageSelected, allOnPage]);

  const toggle = (r: Recipient) => {
    const next = { ...selected };
    if (next[r.id]) delete next[r.id];
    else next[r.id] = r;
    onChange(next);
  };

  const togglePage = () => {
    const next = { ...selected };
    if (allOnPage) rows.forEach((r) => delete next[r.id]);
    else rows.forEach((r) => (next[r.id] = r));
    onChange(next);
  };

  const selectAllMatching = async () => {
    const total = data?.totalElements ?? 0;
    if (total > SELECT_ALL_LIMIT) {
      toast.error(`Narrow the search first: "Select all" is limited to ${SELECT_ALL_LIMIT.toLocaleString()} contacts.`, { id: 'picker-select-all' });
      return;
    }
    setSelectingAll(true);
    try {
      const next = { ...selected };
      const size = 1000;
      for (let p = 0; p * size < total; p++) {
        const res = await recipientsApi.list({ search: debounced || undefined, status: 'ACTIVE', page: p, size });
        res.content.forEach((r) => (next[r.id] = r));
        if (res.last) break;
      }
      onChange(next);
    } catch (e) {
      toast.error(apiErrorMessage(e, 'Could not load all matching contacts'), { id: 'picker-select-all' });
    } finally {
      setSelectingAll(false);
    }
  };

  const total = data?.totalElements ?? 0;
  const matchingSelected = rows.length > 0 && total > rows.length;

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative min-w-[14rem] flex-1">
          <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
          <input
            className="input pl-10"
            placeholder="Search by name, email or company…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            aria-label="Search saved contacts"
          />
          {isFetching && !isLoading && (
            <Loader2 className="absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 animate-spin text-slate-400" />
          )}
        </div>
        <span className="text-sm text-slate-500">
          <b className="text-slate-800">{selectedCount.toLocaleString()}</b> selected
        </span>
        {selectedCount > 0 && (
          <Button type="button" size="sm" variant="ghost" onClick={() => onChange({})}>
            Clear selection
          </Button>
        )}
      </div>

      {isError ? (
        <div className="flex items-center justify-between rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          <span className="flex items-center gap-2">
            <AlertTriangle className="h-4 w-4" /> {apiErrorMessage(error, 'Could not load contacts.')}
          </span>
          <Button type="button" size="sm" variant="secondary" onClick={() => refetch()}>Retry</Button>
        </div>
      ) : isLoading ? (
        <div className="flex items-center justify-center gap-2 py-10 text-sm text-slate-500">
          <Loader2 className="h-4 w-4 animate-spin" /> Loading contacts…
        </div>
      ) : rows.length === 0 ? (
        <div className="flex flex-col items-center gap-2 rounded-xl border border-dashed border-slate-200 py-10 text-center text-sm text-slate-500">
          <Users className="h-6 w-6 text-slate-300" />
          {debounced ? 'No active contacts match your search.' : 'You have no active saved contacts yet.'}
        </div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-slate-200">
          <table className="w-full text-left text-sm">
            <thead className="bg-slate-50 text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="w-10 px-3 py-2">
                  <input
                    ref={headerBox}
                    type="checkbox"
                    className="h-4 w-4 rounded border-slate-300"
                    checked={allOnPage}
                    onChange={togglePage}
                    aria-label="Select all contacts on this page"
                  />
                </th>
                <th className="px-3 py-2 font-medium">Email</th>
                <th className="px-3 py-2 font-medium">Name</th>
                <th className="px-3 py-2 font-medium">Company</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {rows.map((r) => (
                <tr
                  key={r.id}
                  className={`cursor-pointer ${selected[r.id] ? 'bg-brand-50/50' : 'hover:bg-slate-50/60'}`}
                  onClick={() => toggle(r)}
                >
                  <td className="px-3 py-2" onClick={(e) => e.stopPropagation()}>
                    <input
                      type="checkbox"
                      className="h-4 w-4 rounded border-slate-300"
                      checked={!!selected[r.id]}
                      onChange={() => toggle(r)}
                      aria-label={`Select ${r.email}`}
                    />
                  </td>
                  <td className="px-3 py-2 font-medium text-slate-700">{r.email}</td>
                  <td className="px-3 py-2 text-slate-600">{r.name || [r.firstName, r.lastName].filter(Boolean).join(' ') || '—'}</td>
                  <td className="px-3 py-2 text-slate-600">{r.company || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 bg-slate-50 px-3 py-2 text-xs text-slate-500">
            <span>
              {total.toLocaleString()} active contact{total === 1 ? '' : 's'}
              {debounced ? ' match' : ''} · page {page + 1} of {Math.max(1, data?.totalPages ?? 1)}
            </span>
            <div className="flex items-center gap-2">
              {matchingSelected && (
                <Button type="button" size="sm" variant="ghost" onClick={selectAllMatching} loading={selectingAll}>
                  Select all {total.toLocaleString()}
                </Button>
              )}
              <Button type="button" size="sm" variant="secondary" disabled={data?.first ?? true} onClick={() => setPage((p) => p - 1)}>
                Previous
              </Button>
              <Button type="button" size="sm" variant="secondary" disabled={data?.last ?? true} onClick={() => setPage((p) => p + 1)}>
                Next
              </Button>
            </div>
          </div>
        </div>
      )}
      <p className="text-xs text-slate-400">
        Only active contacts are listed. Unsubscribed or bounced contacts are never emailed.
      </p>
    </div>
  );
}
