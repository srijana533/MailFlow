import { useState } from 'react';
import { useParams, Link, useNavigate } from 'react-router-dom';
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import {
  ArrowLeft, Users, CheckCircle2, XCircle, SkipForward, Gauge, Clock, RefreshCw, Mail,
  Send, AlertTriangle, Loader2, Pencil, Trash2,
} from 'lucide-react';
import { RadialBarChart, RadialBar, ResponsiveContainer, PolarAngleAxis } from 'recharts';
import { campaignsApi } from '../api/campaigns';
import type { Campaign, CampaignAnalytics, CampaignStatus } from '../types';
import { Card, StatusBadge, LoadingState, Button, Modal } from '../components/ui';
import { CampaignFormModal } from '../components/CampaignFormModal';
import { DeleteCampaignModal } from '../components/DeleteCampaignModal';
import { useAuthStore } from '../store/authStore';
import {
  campaignOnlyCount, deleteBlockers, joinBlockers, launchBlockers, selectedRecipientCount,
} from '../lib/campaignChecks';
import { apiErrorMessage, apiErrorStatus, isForbidden } from '../lib/errors';
import { canManage, noPermissionMessage } from '../lib/roles';

/** Retry failed loads, except a 404: a deleted or unknown campaign won't come back. */
const retryUnlessNotFound = (failureCount: number, error: unknown) =>
  apiErrorStatus(error) !== 404 && failureCount < 3;

/** Statuses during which the backend is actively processing — poll for updates. */
const ACTIVE_STATUSES: CampaignStatus[] = ['QUEUED', 'RUNNING'];
/** Statuses from which a campaign can be launched. */
const LAUNCHABLE: CampaignStatus[] = ['DRAFT', 'SCHEDULED'];

/**
 * Formats emails-per-minute throughput for display.
 * Below 1/min we show two decimals (e.g. 0.66) so slow sends aren't rounded up
 * to a misleading "1"; at/above 1/min we show a whole number.
 */
function formatThroughput(value: number): string {
  if (value <= 0) return '0';
  if (value < 1) return value.toFixed(2);
  if (value < 10) return value.toFixed(1);
  return Math.round(value).toString();
}

function MetricTile({
  icon: Icon, label, value, tone,
}: {
  icon: React.ElementType;
  label: string;
  value: string | number;
  tone: string;
}) {
  return (
    <Card>
      <div className="flex items-center gap-3">
        <div className={`flex h-10 w-10 items-center justify-center rounded-xl ${tone}`}>
          <Icon className="h-5 w-5" />
        </div>
        <div>
          <p className="text-xs font-medium text-slate-500">{label}</p>
          <p className="text-xl font-bold tabular-nums text-slate-900">{value}</p>
        </div>
      </div>
    </Card>
  );
}

export default function CampaignDetailPage() {
  const { id } = useParams<{ id: string }>();
  const campaignId = Number(id);
  const queryClient = useQueryClient();
  // Only admins and campaign managers may edit or launch (roles also survive a page reload).
  const canSend = useAuthStore((s) => canManage(s.roles));
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [editOpen, setEditOpen] = useState(false);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const navigate = useNavigate();

  const { data: campaign, isLoading, error: loadError } = useQuery<Campaign>({
    queryKey: ['campaign', campaignId],
    queryFn: () => campaignsApi.getById(campaignId),
    retry: retryUnlessNotFound,
    // Poll while the campaign is being processed so progress updates live.
    refetchInterval: (query) => {
      const c = query.state.data as Campaign | undefined;
      return c && ACTIVE_STATUSES.includes(c.status) ? 3000 : false;
    },
  });

  const isActive = campaign ? ACTIVE_STATUSES.includes(campaign.status) : false;

  const { data: analytics } = useQuery<CampaignAnalytics>({
    queryKey: ['campaign-analytics', campaignId],
    queryFn: () => campaignsApi.getAnalytics(campaignId),
    retry: retryUnlessNotFound,
    // Keep analytics in step with the campaign poll.
    refetchInterval: isActive ? 3000 : false,
  });

  const launchMutation = useMutation({
    mutationFn: () => campaignsApi.launch(campaignId),
    onSuccess: () => {
      // Same toast id for success and failure: repeated clicks never stack notifications.
      toast.success('Campaign launched. Sending in progress.', { id: `campaign-action-${campaignId}` });
      setConfirmOpen(false);
      queryClient.invalidateQueries({ queryKey: ['campaign', campaignId] });
      queryClient.invalidateQueries({ queryKey: ['campaign-analytics', campaignId] });
      queryClient.invalidateQueries({ queryKey: ['campaigns'] });
    },
    onError: (e: unknown) => {
      toast.error(
        isForbidden(e) ? noPermissionMessage('launch campaigns') : apiErrorMessage(e, 'Failed to launch campaign'),
        { id: `campaign-action-${campaignId}` },
      );
    },
  });

  if (isLoading) {
    return (
      <div className="page">
        <LoadingState label="Loading campaign…" />
      </div>
    );
  }

  if (!campaign) {
    // A deleted (or unknown) campaign answers 404.
    const notFound = apiErrorStatus(loadError) === 404;
    return (
      <div className="page">
        <Link
          to="/campaigns"
          className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-slate-500 transition hover:text-slate-700"
        >
          <ArrowLeft className="h-4 w-4" /> Back to campaigns
        </Link>
        <Card>
          <p className="font-semibold text-slate-900">{notFound ? 'Campaign not found' : 'Could not load the campaign'}</p>
          <p className="mt-1 text-sm text-slate-500">
            {notFound
              ? "It doesn't exist or it was deleted."
              : apiErrorMessage(loadError, 'Please try again in a moment.')}
          </p>
        </Card>
      </div>
    );
  }

  const processed = campaign.sentCount + campaign.failedCount + campaign.skippedCount;
  const progress = campaign.totalRecipients > 0 ? (processed / campaign.totalRecipients) * 100 : 0;
  const successRate = analytics?.successRate ?? campaign.successRate ?? 0;
  const radialData = [{ name: 'Success', value: Math.round(successRate), fill: '#2563eb' }];
  const isDraft = campaign.status === 'DRAFT';
  const blockers = launchBlockers(campaign);
  const deleteReasons = deleteBlockers(campaign);
  const selectedCount = selectedRecipientCount(campaign);
  const onlyCount = campaignOnlyCount(campaign);
  const groupCount = campaign.recipientGroupIds?.length ?? 0;
  // The campaign's own body wins over its template when it has one.
  const contentLabel = campaign.customBody?.trim()
    ? 'Written for this campaign'
    : campaign.templateName
    ? `Template: ${campaign.templateName}`
    : 'None yet';

  return (
    <div className="page">
      {/* Back link */}
      <Link
        to="/campaigns"
        className="mb-4 inline-flex items-center gap-1.5 text-sm font-medium text-slate-500 transition hover:text-slate-700"
      >
        <ArrowLeft className="h-4 w-4" /> Back to campaigns
      </Link>

      {/* Header */}
      <div className="mb-6 flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <div className="flex items-center gap-3">
            <h1 className="truncate text-2xl font-bold tracking-tight text-slate-900">{campaign.name}</h1>
            <StatusBadge status={campaign.status} />
            {isActive && (
              <span className="inline-flex items-center gap-1.5 text-xs font-medium text-brand-600">
                <Loader2 className="h-3.5 w-3.5 animate-spin" /> Live
              </span>
            )}
          </div>
          <p className="mt-1 text-sm text-slate-500">{campaign.subject}</p>
        </div>

        {/* Actions. Email content, subject, sender email and recipients are required to launch;
            the button is disabled with one inline, actionable hint instead of letting
            the backend reject the launch with an error toast. Delete is offered in every
            status and disabled, with the reason, while the campaign is sending. */}
        {canSend && (
          <div className="flex flex-col items-end gap-1">
            <div className="flex items-center gap-2">
              {isDraft && (
                <Button variant="secondary" onClick={() => setEditOpen(true)} disabled={launchMutation.isPending}>
                  <Pencil className="h-4 w-4" />
                  Edit draft
                </Button>
              )}
              {LAUNCHABLE.includes(campaign.status) && (
                <Button
                  onClick={() => setConfirmOpen(true)}
                  disabled={launchMutation.isPending || blockers.length > 0}
                >
                  <Send className="h-4 w-4" />
                  Launch campaign
                </Button>
              )}
              <Button
                variant="secondary"
                className="text-red-600 hover:text-red-700"
                onClick={() => setDeleteOpen(true)}
                disabled={launchMutation.isPending || deleteReasons.length > 0}
                title={deleteReasons[0]}
              >
                <Trash2 className="h-4 w-4" />
                Delete
              </Button>
            </div>
            {LAUNCHABLE.includes(campaign.status) && blockers.length > 0 && (
              <p className="flex items-center gap-1 text-xs text-amber-600">
                <AlertTriangle className="h-3.5 w-3.5" />
                Before launching, {joinBlockers(blockers)}{isDraft ? ' (Edit draft)' : ''}.
              </p>
            )}
            {deleteReasons.length > 0 && (
              <p className="max-w-sm text-right text-xs text-slate-500">{deleteReasons[0]}</p>
            )}
          </div>
        )}
      </div>

      {/* What the draft will send: shown before launch, when the counters are still 0. */}
      {isDraft && (
        <Card className="mb-6">
          <dl className="grid grid-cols-2 gap-4 text-sm sm:grid-cols-4">
            <div>
              <dt className="text-xs text-slate-500">Content</dt>
              <dd className="font-medium text-slate-800">{contentLabel}</dd>
            </div>
            <div>
              <dt className="text-xs text-slate-500">Recipients selected</dt>
              <dd className="font-medium tabular-nums text-slate-800">
                {selectedCount.toLocaleString()}
                {groupCount > 0 && <span className="text-xs font-normal text-slate-500"> + {groupCount} group{groupCount === 1 ? '' : 's'}</span>}
                {onlyCount > 0 && (
                  <span className="block text-xs font-normal text-slate-500" title="Stored with this campaign, not saved as contacts">
                    incl. {onlyCount.toLocaleString()} campaign-only
                  </span>
                )}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-slate-500">Sender</dt>
              <dd className="truncate font-medium text-slate-800">
                {campaign.senderEmail ? `${campaign.senderName ? `${campaign.senderName} ` : ''}<${campaign.senderEmail}>` : 'Not set'}
              </dd>
            </div>
            <div>
              <dt className="text-xs text-slate-500">Reply-to</dt>
              <dd className="truncate font-medium text-slate-800">{campaign.replyTo || 'Not set'}</dd>
            </div>
          </dl>
        </Card>
      )}

      {/* Edit a draft (template, recipients, details) */}
      {isDraft && (
        <CampaignFormModal
          open={editOpen}
          campaign={campaign}
          onClose={() => setEditOpen(false)}
          onSaved={(saved) => {
            queryClient.setQueryData(['campaign', campaignId], saved);
            queryClient.invalidateQueries({ queryKey: ['campaign', campaignId] });
            queryClient.invalidateQueries({ queryKey: ['campaigns'] });
          }}
        />
      )}

      {/* Delete confirmation; afterwards the campaign is gone, so go back to the list */}
      <DeleteCampaignModal
        campaign={canSend && deleteOpen ? campaign : null}
        onClose={() => setDeleteOpen(false)}
        onDeleted={() => navigate('/campaigns', { replace: true })}
      />

      {/* Pre-send confirmation */}
      <Modal
        open={confirmOpen}
        onClose={() => !launchMutation.isPending && setConfirmOpen(false)}
        title="Launch this campaign?"
        description="This starts sending emails to every attached recipient. This cannot be undone."
        footer={
          <>
            <Button
              variant="secondary"
              onClick={() => setConfirmOpen(false)}
              disabled={launchMutation.isPending}
            >
              Cancel
            </Button>
            <Button onClick={() => launchMutation.mutate()} loading={launchMutation.isPending}>
              {launchMutation.isPending ? 'Launching…' : 'Yes, send now'}
            </Button>
          </>
        }
      >
        <div className="space-y-4">
          <div className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3">
            <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-500" />
            <p className="text-sm text-amber-800">
              You are about to send <b>{campaign.name}</b> to its recipients. Make sure the
              subject, content and recipient list are correct.
            </p>
          </div>
          <dl className="grid grid-cols-2 gap-3 text-sm">
            <div className="rounded-lg bg-slate-50 px-3 py-2">
              <dt className="text-xs text-slate-500">Recipients selected</dt>
              <dd className="text-lg font-bold tabular-nums text-slate-900">
                {selectedCount.toLocaleString()}
                {groupCount > 0 && <span className="text-xs font-normal text-slate-500"> + {groupCount} group{groupCount === 1 ? '' : 's'}</span>}
                {onlyCount > 0 && (
                  <span className="block text-xs font-normal text-slate-500">incl. {onlyCount.toLocaleString()} campaign-only</span>
                )}
              </dd>
            </div>
            <div className="rounded-lg bg-slate-50 px-3 py-2">
              <dt className="text-xs text-slate-500">Subject</dt>
              <dd className="truncate text-sm font-medium text-slate-700">{campaign.subject}</dd>
            </div>
            <div className="col-span-2 rounded-lg bg-slate-50 px-3 py-2">
              <dt className="text-xs text-slate-500">Content</dt>
              <dd className="truncate text-sm font-medium text-slate-700">{contentLabel}</dd>
            </div>
          </dl>
          <p className="text-xs text-slate-500">
            Unsubscribed, bounced or deleted contacts are skipped, and so are unsubscribed addresses. A campaign-only
            address that has since become a saved contact is sent to that contact. The final number of emails is shown
            once the campaign starts.
          </p>
        </div>
      </Modal>

      {/* Progress */}
      {campaign.totalRecipients > 0 && (
        <Card className="mb-6">
          <div className="mb-2 flex items-center justify-between text-sm">
            <span className="font-medium text-slate-700">Processing progress</span>
            <span className="font-semibold tabular-nums text-slate-900">{progress.toFixed(1)}%</span>
          </div>
          <div className="h-2.5 overflow-hidden rounded-full bg-slate-100">
            <div
              className="h-full rounded-full bg-gradient-to-r from-brand-500 to-accent-500 transition-all duration-700"
              style={{ width: `${Math.min(progress, 100)}%` }}
            />
          </div>
          <p className="mt-2 text-xs text-slate-400">
            {processed.toLocaleString()} of {campaign.totalRecipients.toLocaleString()} recipients processed
          </p>
        </Card>
      )}

      {/* Metric tiles */}
      <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">
        <MetricTile icon={Users} label="Total Recipients" value={campaign.totalRecipients.toLocaleString()} tone="bg-brand-50 text-brand-600" />
        <MetricTile icon={CheckCircle2} label="Sent" value={campaign.sentCount.toLocaleString()} tone="bg-emerald-50 text-emerald-600" />
        <MetricTile icon={XCircle} label="Failed" value={campaign.failedCount.toLocaleString()} tone="bg-red-50 text-red-600" />
        <MetricTile icon={SkipForward} label="Skipped" value={campaign.skippedCount.toLocaleString()} tone="bg-amber-50 text-amber-600" />
      </div>

      {/* Analytics */}
      <div className="mt-6 grid grid-cols-1 gap-5 lg:grid-cols-3">
        {/* Success rate radial */}
        <Card className="flex flex-col items-center">
          <h3 className="section-title mb-2 self-start">Success Rate</h3>
          <div className="relative">
            <ResponsiveContainer width={180} height={180}>
              <RadialBarChart
                innerRadius="72%"
                outerRadius="100%"
                data={radialData}
                startAngle={90}
                endAngle={-270}
              >
                <PolarAngleAxis type="number" domain={[0, 100]} tick={false} />
                <RadialBar dataKey="value" cornerRadius={10} background={{ fill: '#eef2f7' }} />
              </RadialBarChart>
            </ResponsiveContainer>
            <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
              <span className="text-3xl font-bold text-slate-900">{successRate.toFixed(0)}%</span>
              <span className="text-xs text-slate-400">sent</span>
            </div>
          </div>
        </Card>

        {/* Throughput */}
        <Card>
          <h3 className="section-title mb-4">Throughput</h3>
          <div className="flex items-center gap-3">
            <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-accent-50 text-accent-600">
              <Gauge className="h-5 w-5" />
            </div>
            <div>
              <p className="text-3xl font-bold tabular-nums text-slate-900">
                {formatThroughput(analytics?.throughputPerMinute ?? 0)}
              </p>
              <p className="text-sm text-slate-500">emails / minute</p>
            </div>
          </div>
          {analytics?.durationSeconds != null && (
            <div className="mt-4 flex items-center gap-2 border-t border-slate-100 pt-4 text-sm text-slate-500">
              <Clock className="h-4 w-4 text-slate-400" />
              Duration: {Math.floor(analytics.durationSeconds / 60)}m {analytics.durationSeconds % 60}s
            </div>
          )}
        </Card>

        {/* Breakdown */}
        <Card>
          <h3 className="section-title mb-4">Breakdown</h3>
          <div className="space-y-3">
            {[
              { icon: RefreshCw, label: 'Retried', value: analytics?.retried ?? campaign.retryCount, color: 'text-amber-500' },
              { icon: Mail, label: 'Bounced', value: analytics?.bounced ?? campaign.bouncedCount, color: 'text-orange-500' },
              { icon: SkipForward, label: 'Skipped', value: analytics?.skipped ?? campaign.skippedCount, color: 'text-slate-400' },
            ].map(({ icon: Icon, label, value, color }) => (
              <div key={label} className="flex items-center justify-between text-sm">
                <span className="flex items-center gap-2 text-slate-600">
                  <Icon className={`h-4 w-4 ${color}`} />
                  {label}
                </span>
                <span className="font-semibold tabular-nums text-slate-900">{value.toLocaleString()}</span>
              </div>
            ))}
          </div>
        </Card>
      </div>
    </div>
  );
}
