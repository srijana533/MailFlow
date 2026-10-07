import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Plus, Play, Pause, X, Eye, Search, Send } from 'lucide-react';
import toast from 'react-hot-toast';
import { campaignsApi } from '../api/campaigns';
import type { Campaign } from '../types';
import { PageHeader, Card, StatusBadge, EmptyState, Button, LoadingState } from '../components/ui';
import { CampaignFormModal } from '../components/CampaignFormModal';

export default function CampaignsPage() {
  const qc = useQueryClient();
  const [search, setSearch] = useState('');
  const [createOpen, setCreateOpen] = useState(false);

  const { data, isLoading } = useQuery({
    queryKey: ['campaigns', search],
    queryFn: () => campaignsApi.list({ search: search || undefined, size: 50 }),
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ['campaigns'] });
  const errMsg = (e: unknown) =>
    (e as { response?: { data?: { message?: string } } }).response?.data?.message ?? 'Action failed';

  const launchMutation = useMutation({
    mutationFn: (id: number) => campaignsApi.launch(id),
    onSuccess: () => { toast.success('Campaign launched'); invalidate(); },
    onError: (e) => toast.error(errMsg(e)),
  });
  const pauseMutation = useMutation({
    mutationFn: (id: number) => campaignsApi.pause(id),
    onSuccess: () => { toast.success('Campaign paused'); invalidate(); },
    onError: (e) => toast.error(errMsg(e)),
  });
  const cancelMutation = useMutation({
    mutationFn: (id: number) => campaignsApi.cancel(id),
    onSuccess: () => { toast.success('Campaign cancelled'); invalidate(); },
    onError: (e) => toast.error(errMsg(e)),
  });

  const campaigns = data?.content ?? [];
  const busy = launchMutation.isPending || pauseMutation.isPending || cancelMutation.isPending;

  return (
    <div className="page">
      <PageHeader
        title="Campaigns"
        description={`${data?.totalElements ?? 0} campaign${(data?.totalElements ?? 0) === 1 ? '' : 's'} total`}
        actions={
          <Button onClick={() => setCreateOpen(true)}>
            <Plus className="h-4 w-4" /> New Campaign
          </Button>
        }
      />

      {/* Search */}
      <div className="relative mb-5 max-w-sm">
        <Search className="pointer-events-none absolute left-3.5 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
        <input
          className="input pl-10"
          placeholder="Search campaigns…"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          aria-label="Search campaigns"
        />
      </div>

      <Card padded={false}>
        {isLoading ? (
          <LoadingState label="Loading campaigns…" />
        ) : campaigns.length === 0 ? (
          <EmptyState
            icon={Send}
            title={search ? 'No campaigns match your search' : 'No campaigns yet'}
            description={
              search
                ? 'Try a different search term.'
                : 'Create your first email campaign to start reaching your audience.'
            }
            action={!search && <Button onClick={() => setCreateOpen(true)}><Plus className="h-4 w-4" /> New Campaign</Button>}
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-slate-200 text-left">
                  <th className="px-6 py-3.5 text-xs font-semibold uppercase tracking-wider text-slate-500">Campaign</th>
                  <th className="px-4 py-3.5 text-xs font-semibold uppercase tracking-wider text-slate-500">Status</th>
                  <th className="px-4 py-3.5 text-right text-xs font-semibold uppercase tracking-wider text-slate-500">Recipients</th>
                  <th className="px-4 py-3.5 text-right text-xs font-semibold uppercase tracking-wider text-slate-500">Sent</th>
                  <th className="px-4 py-3.5 text-right text-xs font-semibold uppercase tracking-wider text-slate-500">Success</th>
                  <th className="px-6 py-3.5 text-right text-xs font-semibold uppercase tracking-wider text-slate-500">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {campaigns.map((c: Campaign) => (
                  <tr key={c.id} className="group transition hover:bg-slate-50">
                    <td className="px-6 py-4">
                      <Link to={`/campaigns/${c.id}`} className="block">
                        <p className="font-semibold text-slate-900 group-hover:text-brand-700">{c.name}</p>
                        <p className="max-w-xs truncate text-xs text-slate-500">{c.subject}</p>
                      </Link>
                    </td>
                    <td className="px-4 py-4"><StatusBadge status={c.status} /></td>
                    <td className="px-4 py-4 text-right tabular-nums text-slate-700">
                      {c.totalRecipients.toLocaleString()}
                    </td>
                    <td className="px-4 py-4 text-right tabular-nums text-slate-700">
                      {c.sentCount.toLocaleString()}
                    </td>
                    <td className="px-4 py-4 text-right">
                      <span
                        className={
                          'font-semibold tabular-nums ' +
                          (c.successRate >= 90 ? 'text-emerald-600' : c.successRate > 0 ? 'text-amber-600' : 'text-slate-400')
                        }
                      >
                        {c.successRate.toFixed(1)}%
                      </span>
                    </td>
                    <td className="px-6 py-4">
                      <div className="flex items-center justify-end gap-1.5">
                        <Link
                          to={`/campaigns/${c.id}`}
                          className="btn-icon text-slate-500 hover:bg-slate-100 hover:text-slate-700"
                          aria-label="View campaign"
                          title="View"
                        >
                          <Eye className="h-4 w-4" />
                        </Link>
                        {(c.status === 'DRAFT' || c.status === 'PAUSED') && (
                          <button
                            className="btn-icon text-emerald-600 hover:bg-emerald-50 disabled:opacity-50"
                            onClick={() => launchMutation.mutate(c.id)}
                            disabled={busy}
                            aria-label="Launch campaign"
                            title="Launch"
                          >
                            <Play className="h-4 w-4" />
                          </button>
                        )}
                        {c.status === 'RUNNING' && (
                          <button
                            className="btn-icon text-amber-600 hover:bg-amber-50 disabled:opacity-50"
                            onClick={() => pauseMutation.mutate(c.id)}
                            disabled={busy}
                            aria-label="Pause campaign"
                            title="Pause"
                          >
                            <Pause className="h-4 w-4" />
                          </button>
                        )}
                        {['DRAFT', 'SCHEDULED', 'QUEUED', 'RUNNING', 'PAUSED'].includes(c.status) && (
                          <button
                            className="btn-icon text-red-600 hover:bg-red-50 disabled:opacity-50"
                            onClick={() => cancelMutation.mutate(c.id)}
                            disabled={busy}
                            aria-label="Cancel campaign"
                            title="Cancel"
                          >
                            <X className="h-4 w-4" />
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Create campaign modal */}
      <CampaignFormModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => qc.invalidateQueries({ queryKey: ['campaigns'] })}
      />
    </div>
  );
}
