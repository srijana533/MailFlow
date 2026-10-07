import { useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Upload, Search, Users, Loader2 } from 'lucide-react';
import { apiClient } from '../api/client';
import type { Recipient, PageResponse } from '../types';
import toast from 'react-hot-toast';
import { PageHeader, Card, StatusBadge, EmptyState, LoadingState, Button } from '../components/ui';

export default function RecipientsPage() {
  const qc = useQueryClient();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const [importing, setImporting] = useState(false);

  const { data, isLoading } = useQuery<PageResponse<Recipient>>({
    queryKey: ['recipients', search, page],
    queryFn: () =>
      apiClient
        .get('/api/recipients', { params: { search: search || undefined, page, size: 25 } })
        .then((r) => r.data),
  });

  const handleCsvImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    const fd = new FormData();
    fd.append('file', file);
    setImporting(true);

    try {
      const res = await apiClient.post('/api/recipients/import/csv', fd, {
        headers: { 'Content-Type': 'multipart/form-data' },
      });
      const result = res.data;
      toast.success(
        `Import complete: ${result.imported} imported, ${result.skippedDuplicates} duplicates, ${result.invalidRows} invalid`
      );
      await qc.invalidateQueries({ queryKey: ['recipients'] });
    } catch {
      toast.error('CSV import failed. Check the file format.');
    } finally {
      setImporting(false);
      e.target.value = '';
    }
  };

  const recipients = data?.content ?? [];

  return (
    <div className="page">
      <PageHeader
        title="Recipients"
        description={`${data?.totalElements?.toLocaleString() ?? 0} total recipient${(data?.totalElements ?? 0) === 1 ? '' : 's'}`}
        actions={
          <label
            className={
              'btn-secondary cursor-pointer ' +
              (importing ? 'pointer-events-none opacity-60' : '')
            }
          >
            {importing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Upload className="h-4 w-4" />}
            {importing ? 'Importing…' : 'Import CSV'}
            <input
              type="file"
              accept=".csv"
              className="hidden"
              disabled={importing}
              onChange={handleCsvImport}
            />
          </label>
        }
      />

      {/* Search */}
      <div className="relative mb-5 max-w-sm">
        <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
        <input
          className="input pl-10"
          placeholder="Search by email or name…"
          value={search}
          onChange={(e) => {
            setSearch(e.target.value);
            setPage(0);
          }}
          aria-label="Search recipients"
        />
      </div>

      <Card padded={false}>
        {isLoading ? (
          <LoadingState label="Loading recipients…" />
        ) : recipients.length === 0 ? (
          <EmptyState
            icon={Users}
            title={search ? 'No recipients match your search' : 'No recipients yet'}
            description={
              search
                ? 'Try a different search term.'
                : 'Import a CSV file to add recipients to your audience.'
            }
            action={
              !search && (
                <label className="btn-primary cursor-pointer">
                  <Upload className="h-4 w-4" /> Import CSV
                  <input type="file" accept=".csv" className="hidden" onChange={handleCsvImport} />
                </label>
              )
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-200 text-left">
                  <th className="px-6 py-3.5 text-xs font-semibold uppercase tracking-wider text-slate-500">Email</th>
                  <th className="px-4 py-3.5 text-xs font-semibold uppercase tracking-wider text-slate-500">Name</th>
                  <th className="px-4 py-3.5 text-xs font-semibold uppercase tracking-wider text-slate-500">Course</th>
                  <th className="px-4 py-3.5 text-xs font-semibold uppercase tracking-wider text-slate-500">Company</th>
                  <th className="px-6 py-3.5 text-xs font-semibold uppercase tracking-wider text-slate-500">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {recipients.map((r: Recipient) => (
                  <tr key={r.id} className="transition hover:bg-slate-50">
                    <td className="px-6 py-3.5 font-medium text-slate-900">{r.email}</td>
                    <td className="px-4 py-3.5 text-slate-600">{r.name ?? '—'}</td>
                    <td className="px-4 py-3.5 text-slate-500">{r.course ?? '—'}</td>
                    <td className="px-4 py-3.5 text-slate-500">{r.company ?? '—'}</td>
                    <td className="px-6 py-3.5"><StatusBadge status={r.status} /></td>
                  </tr>
                ))}
              </tbody>
            </table>

            {data && data.totalPages > 1 && (
              <div className="flex items-center justify-between border-t border-slate-200 px-6 py-3.5">
                <p className="text-xs text-slate-500">
                  Page <span className="font-semibold text-slate-700">{data.page + 1}</span> of {data.totalPages}
                </p>
                <div className="flex gap-2">
                  <Button variant="secondary" size="sm" disabled={data.first} onClick={() => setPage((p) => p - 1)}>
                    Previous
                  </Button>
                  <Button variant="secondary" size="sm" disabled={data.last} onClick={() => setPage((p) => p + 1)}>
                    Next
                  </Button>
                </div>
              </div>
            )}
          </div>
        )}
      </Card>
    </div>
  );
}
