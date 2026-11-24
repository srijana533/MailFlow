import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { Plus, Play, Pause, X, Eye, Search, Send, AlertTriangle, Lock, Trash2 } from 'lucide-react';
import toast from 'react-hot-toast';
import { campaignsApi } from '../api/campaigns';
import type { Campaign } from '../types';
import { PageHeader, Card, StatusBadge, EmptyState, Button, LoadingState, Modal } from '../components/ui';
import { CampaignFormModal } from '../components/CampaignFormModal';
import { DeleteCampaignModal } from '../components/DeleteCampaignModal';
import { useAuthStore } from '../store/authStore';
import {
  campaignOnlyCount, deleteBlockers, joinBlockers, launchBlockers, selectedRecipientCount,
} from '../lib/campaignChecks';
import { apiErrorMessage, isForbidden } from '../lib/errors';
import { canManage, noPermissionMessage } from '../lib/roles';

/** Error text for a campaign action; a 403 gets a permission message instead of the raw error. */
function actionError(e: unknown, action: string, fallback: string): string {
  return isForbidden(e) ? noPermissionMessage(action) : apiErrorMessage(e, fallback);
}

export default function CampaignsPage() {
  const qc = useQueryClient();
  // VIEWER is read-only (the backend allows it GET requests only): no create or send actions.
  const canWrite = useAuthStore((s) => canManage(s.roles));
  const [search, setSearch] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [confirmTarget, setConfirmTarget] = useState<Campaign | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<Campaign | null>(null);

  const { data, isLoading } = useQuery({
    queryKey: ['campaigns', search],
    queryFn: () => campaignsApi.list({ search: search || undefined, size: 50 }),
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ['campaigns'] });

  // Toast ids make a repeated error replace the previous toast instead of stacking.
  const launchMutation = useMutation({
    mutationFn: (id: number) => campaignsApi.launch(id),
    onSuccess: (_c, id) => {
      toast.success('Campaign launched. Sending has started.', { id: `campaign-action-${id}` });
      setConfirmTarget(null);
      invalidate();
    },
    onError: (e, id) =>
      toast.error(actionError(e, 'launch campaigns', 'Could not launch the campaign'), { id: `campaign-action-${id}` }),
  });
  const resumeMutation = useMutation({
    mutationFn: (id: number) => campaignsApi.resume(id),
    onSuccess: (_c, id) => { toast.success('Campaign resumed', { id: `campaign-action-${id}` }); invalidate(); },
    onError: (e, id) =>
      toast.error(actionError(e, 'resume campaigns', 'Could not resume the campaign'), { id: `campaign-action-${id}` }),
  });
  const pauseMutation = useMutation({
    mutationFn: (id: number) => campaignsApi.pause(id),
    onSuccess: (_c, id) => { toast.success('Campaign paused', { id: `campaign-action-${id}` }); invalidate(); },
    onError: (e, id) =>
      toast.error(actionError(e, 'pause campaigns', 'Could not pause the campaign'), { id: `campaign-action-${id}` }),
  });
  const cancelMutation = useMutation({
    mutationFn: (id: number) => campaignsApi.cancel(id),
    onSuccess: (_c, id) => { toast.success('Campaign cancelled', { id: `campaign-action-${id}` }); invalidate(); },
    onError: (e, id) =>
      toast.error(actionError(e, 'cancel campaigns', 'Could not cancel the campaign'), { id: `campaign-action-${id}` }),
  });

  // Pre-launch validation mirrors the backend (email content, subject, sender, recipients).
  // A blocked launch shows ONE actionable message and never calls the API.
  const handleLaunch = (c: Campaign) => {
    if (!canWrite) return;
    if (launchMutation.isPending) return; // guard against double-launch
    const blockers = launchBlockers(c);
    if (blockers.length > 0) {
      toast.error(`Before launching, ${joinBlockers(blockers)}. Open the campaign and choose "Edit draft".`, {
        id: `campaign-action-${c.id}`,
      });
      return;
    }
    setConfirmTarget(c);
  };

  const campaigns = data?.content ?? [];
  const busy =
    launchMutation.isPending || resumeMutation.isPending || pauseMutation.isPending || cancelMutation.isPending;

  return (
    <div className="page">
      <PageHeader
        title="Campaigns"
        description={`${data?.totalElements ?? 0} campaign${(data?.totalElements ?? 0) === 1 ? '' : 's'} total`}
        actions={
          canWrite ? (
            <Button onClick={() => setCreateOpen(true)}>
              <Plus className="h-4 w-4" /> New Campaign
            </Button>
          ) : undefined
        }
      />

      {!canWrite && (
        <p className="mb-5 flex items-start gap-2 rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
          <Lock className="mt-0.5 h-4 w-4 shrink-0 text-slate-400" />
          You have read-only access. Admins and campaign managers can create, launch, pause, resume, cancel and
          delete campaigns.
        </p>
      )}

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
                : canWrite
                ? 'Create your first email campaign to start reaching your audience.'
                : 'Admins and campaign managers can create campaigns.'
            }
            action={!search && canWrite && <Button onClick={() => setCreateOpen(true)}><Plus className="h-4 w-4" /> New Campaign</Button>}
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
                {campaigns.map((c: Campaign) => {
                  const blockers = c.status === 'DRAFT' ? launchBlockers(c) : [];
                  const deleteReasons = deleteBlockers(c);
                  // Cancelling a draft or scheduled campaign means it won't be sent; otherwise it stops sending.
                  const cancelLabel = c.status === 'DRAFT' || c.status === 'SCHEDULED' ? 'Cancel campaign' : 'Stop sending';
                  return (
                    <tr key={c.id} className="group transition hover:bg-slate-50">
                      <td className="px-6 py-4">
                        <Link to={`/campaigns/${c.id}`} className="block">
                          <p className="font-semibold text-slate-900 group-hover:text-brand-700">{c.name}</p>
                          <p className="max-w-xs truncate text-xs text-slate-500">{c.subject}</p>
                        </Link>
                      </td>
                      <td className="px-4 py-4"><StatusBadge status={c.status} /></td>
                      <td className="px-4 py-4 text-right tabular-nums text-slate-700">
                        {c.status === 'DRAFT' ? (
                          <span
                            title={
                              'Selected for this draft' +
                              (campaignOnlyCount(c) > 0 ? `, including ${campaignOnlyCount(c).toLocaleString()} campaign-only` : '') +
                              '. The final count is set at launch.'
                            }
                          >
                            {selectedRecipientCount(c).toLocaleString()}
                            <span className="ml-1 text-xs text-slate-400">selected</span>
                          </span>
                        ) : (
                          c.totalRecipients.toLocaleString()
                        )}
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
                          {canWrite && c.status === 'DRAFT' && (
                            <button
                              className="btn-icon text-emerald-600 hover:bg-emerald-50 disabled:cursor-not-allowed disabled:opacity-40"
                              onClick={() => handleLaunch(c)}
                              disabled={busy || blockers.length > 0}
                              aria-label="Launch campaign"
                              title={blockers.length > 0 ? `Before launching, ${joinBlockers(blockers)}` : 'Launch'}
                            >
                              <Play className="h-4 w-4" />
                            </button>
                          )}
                          {canWrite && c.status === 'PAUSED' && (
                            <button
                              className="btn-icon text-emerald-600 hover:bg-emerald-50 disabled:opacity-50"
                              onClick={() => resumeMutation.mutate(c.id)}
                              disabled={busy}
                              aria-label="Resume campaign"
                              title="Resume"
                            >
                              <Play className="h-4 w-4" />
                            </button>
                          )}
                          {canWrite && c.status === 'RUNNING' && (
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
                          {canWrite && ['DRAFT', 'SCHEDULED', 'QUEUED', 'RUNNING', 'PAUSED'].includes(c.status) && (
                            <button
                              className="btn-icon text-red-600 hover:bg-red-50 disabled:opacity-50"
                              onClick={() => cancelMutation.mutate(c.id)}
                              disabled={busy}
                              aria-label={cancelLabel}
                              title={
                                cancelLabel === 'Stop sending'
                                  ? 'Stop sending: cancels the campaign; it stays in this list'
                                  : "Cancel campaign: it won't be sent; it stays in this list"
                              }
                            >
                              <X className="h-4 w-4" />
                            </button>
                          )}
                          {canWrite && (
                            <button
                              className="btn-icon text-slate-500 hover:bg-red-50 hover:text-red-600 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-slate-500"
                              onClick={() => setDeleteTarget(c)}
                              disabled={busy || deleteReasons.length > 0}
                              aria-label={
                                deleteReasons.length > 0 ? `Delete campaign (not possible now: ${deleteReasons[0]})` : 'Delete campaign'
                              }
                              title={deleteReasons[0] ?? 'Delete: removes the campaign from this list'}
                            >
                              <Trash2 className="h-4 w-4" />
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </Card>

      {/* Launch confirmation (same safeguard as the campaign detail page) */}
      <Modal
        open={canWrite && !!confirmTarget}
        onClose={() => !launchMutation.isPending && setConfirmTarget(null)}
        title="Launch this campaign?"
        description="Emails start sending to the selected recipients. This cannot be undone."
        footer={
          <>
            <Button variant="secondary" onClick={() => setConfirmTarget(null)} disabled={launchMutation.isPending}>
              Cancel
            </Button>
            <Button
              onClick={() => confirmTarget && launchMutation.mutate(confirmTarget.id)}
              loading={launchMutation.isPending}
            >
              {launchMutation.isPending ? 'Launching…' : 'Yes, send now'}
            </Button>
          </>
        }
      >
        {confirmTarget && (
          <div className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800">
            <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-500" />
            <p>
              <b>{confirmTarget.name}</b> will be sent to {selectedRecipientCount(confirmTarget).toLocaleString()} selected
              recipient{selectedRecipientCount(confirmTarget) === 1 ? '' : 's'}
              {campaignOnlyCount(confirmTarget) > 0
                ? ` (including ${campaignOnlyCount(confirmTarget).toLocaleString()} campaign-only address${campaignOnlyCount(confirmTarget) === 1 ? '' : 'es'})`
                : ''}
              {(confirmTarget.recipientGroupIds?.length ?? 0) > 0 ? ' plus the members of its recipient groups' : ''}.
              Unsubscribed or inactive contacts and unsubscribed addresses are skipped.
            </p>
          </div>
        )}
      </Modal>

      {/* Delete confirmation; on success the lists, dashboard and analytics are refreshed */}
      <DeleteCampaignModal campaign={canWrite ? deleteTarget : null} onClose={() => setDeleteTarget(null)} />

      {/* Create campaign modal */}
      <CampaignFormModal
        open={canWrite && createOpen}
        onClose={() => setCreateOpen(false)}
        onSaved={() => qc.invalidateQueries({ queryKey: ['campaigns'] })}
      />
    </div>
  );
}
