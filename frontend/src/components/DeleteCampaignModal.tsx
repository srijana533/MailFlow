import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import { AlertTriangle, Trash2 } from 'lucide-react';
import { campaignsApi } from '../api/campaigns';
import type { Campaign } from '../types';
import { Button, Modal } from './ui';
import { campaignOnlyCount } from '../lib/campaignChecks';
import { apiErrorMessage, apiErrorStatus, isForbidden } from '../lib/errors';
import { noPermissionMessage } from '../lib/roles';

/**
 * Queries whose data a deleted campaign changes: the campaign lists (CampaignsPage's
 * ['campaigns', search] and DashboardPage's ['campaigns', 'dashboard-recent']), campaign
 * detail and analytics, and the totals and charts of DashboardPage and AnalyticsPage.
 */
const AFFECTED_QUERY_KEYS = [
  ['campaigns'],
  ['campaign'],
  ['campaign-analytics'],
  ['dashboard-summary'],
  ['daily-volume'],
  ['daily-volume-30'],
];

function deleteErrorMessage(e: unknown): string {
  if (isForbidden(e)) return noPermissionMessage('delete campaigns');
  if (apiErrorStatus(e) === 404) return 'This campaign no longer exists. It may have been deleted already.';
  // A 409 carries the server's reason, for example that the campaign is still sending.
  return apiErrorMessage(e, 'Could not delete the campaign. Please try again.');
}

interface DeleteCampaignModalProps {
  /** The campaign to delete; the dialog is open while this is set. */
  campaign: Campaign | null;
  onClose: () => void;
  /** Called once the server has deleted the campaign (for example to leave its page). */
  onDeleted?: (campaign: Campaign) => void;
}

/**
 * Confirms and performs a campaign delete (a soft delete on the server). While the
 * request runs the dialog can't be closed. On failure the campaign stays on screen and
 * the server's reason is shown in the dialog and in a toast.
 */
export function DeleteCampaignModal({ campaign, onClose, onDeleted }: DeleteCampaignModalProps) {
  const qc = useQueryClient();
  const [error, setError] = useState<string | null>(null);

  const refresh = () => AFFECTED_QUERY_KEYS.forEach((queryKey) => qc.invalidateQueries({ queryKey }));

  const mutation = useMutation({
    mutationFn: (c: Campaign) => campaignsApi.delete(c.id),
    onSuccess: (_res, c) => {
      // Same toast id for success and failure: repeated attempts replace each other.
      toast.success(`Campaign "${c.name}" deleted.`, { id: `campaign-delete-${c.id}` });
      setError(null);
      onDeleted?.(c);
      onClose();
      // Its own queries would now answer 404: drop them instead of refetching.
      qc.removeQueries({ queryKey: ['campaign', c.id] });
      qc.removeQueries({ queryKey: ['campaign-analytics', c.id] });
      refresh();
    },
    onError: (e, c) => {
      const message = deleteErrorMessage(e);
      setError(message);
      toast.error(message, { id: `campaign-delete-${c.id}` });
      // Already gone: refresh so it disappears. Any other failure keeps it on screen.
      if (apiErrorStatus(e) === 404) refresh();
    },
  });

  const pending = mutation.isPending;
  const close = () => {
    if (pending) return; // no closing while the delete is running
    setError(null);
    mutation.reset();
    onClose();
  };

  const onlyCount = campaign ? campaignOnlyCount(campaign) : 0;

  return (
    <Modal
      open={!!campaign}
      onClose={close}
      title="Delete this campaign?"
      footer={
        <>
          <Button variant="secondary" onClick={close} disabled={pending}>
            Keep campaign
          </Button>
          <Button variant="danger" onClick={() => campaign && mutation.mutate(campaign)} loading={pending}>
            {!pending && <Trash2 className="h-4 w-4" />}
            {pending ? 'Deleting…' : 'Delete campaign'}
          </Button>
        </>
      }
    >
      {campaign && (
        <div className="space-y-4">
          <div className="flex items-start gap-3 rounded-xl border border-red-200 bg-red-50 px-4 py-3">
            <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-red-500" />
            <p className="text-sm text-red-800">
              <b>{campaign.name}</b> will be deleted. This can't be undone in MailFlow.
            </p>
          </div>
          <ul className="list-disc space-y-1.5 pl-5 text-sm text-slate-600">
            <li>
              It's hidden from the campaign list, search, the dashboard and the statistics, and it can no longer be
              opened, edited or sent.
            </li>
            <li>
              Its delivery history is kept: any emails it already sent, their results and unsubscribe requests stay
              recorded.
            </li>
            <li>Contacts, groups and templates are not changed.</li>
            {onlyCount > 0 && (
              <li>
                Its list of {onlyCount.toLocaleString()} campaign-only address{onlyCount === 1 ? '' : 'es'} is deleted.
              </li>
            )}
          </ul>
          {error && (
            <p role="alert" className="rounded-lg border border-red-200 bg-white px-3 py-2 text-sm text-red-700">
              {error}
            </p>
          )}
        </div>
      )}
    </Modal>
  );
}
