import clsx from 'clsx';
import type { CampaignStatus, RecipientStatus } from '../../types';

type AnyStatus = CampaignStatus | RecipientStatus | string;

interface StatusConfig {
  label: string;
  dot: string;
  chip: string;
}

/**
 * Maps every known campaign & recipient status to a consistent colour chip
 * with a leading status dot. Falls back to neutral for unknown values.
 */
const STATUS_MAP: Record<string, StatusConfig> = {
  // Campaign statuses
  DRAFT:     { label: 'Draft',     dot: 'bg-slate-400',   chip: 'bg-slate-100 text-slate-700' },
  SCHEDULED: { label: 'Scheduled', dot: 'bg-amber-500',   chip: 'bg-amber-50 text-amber-700' },
  QUEUED:    { label: 'Queued',    dot: 'bg-blue-500',    chip: 'bg-blue-50 text-blue-700' },
  RUNNING:   { label: 'Sending',   dot: 'bg-sky-500',     chip: 'bg-sky-50 text-sky-700' },
  PAUSED:    { label: 'Paused',    dot: 'bg-yellow-500',  chip: 'bg-yellow-50 text-yellow-700' },
  COMPLETED: { label: 'Completed', dot: 'bg-emerald-500', chip: 'bg-emerald-50 text-emerald-700' },
  FAILED:    { label: 'Failed',    dot: 'bg-red-500',     chip: 'bg-red-50 text-red-700' },
  CANCELLED: { label: 'Cancelled', dot: 'bg-slate-400',   chip: 'bg-slate-100 text-slate-500' },
  // Recipient statuses
  ACTIVE:       { label: 'Active',       dot: 'bg-emerald-500', chip: 'bg-emerald-50 text-emerald-700' },
  UNSUBSCRIBED: { label: 'Unsubscribed', dot: 'bg-amber-500',   chip: 'bg-amber-50 text-amber-700' },
  BOUNCED:      { label: 'Bounced',      dot: 'bg-orange-500',  chip: 'bg-orange-50 text-orange-700' },
  INVALID:      { label: 'Invalid',      dot: 'bg-red-500',     chip: 'bg-red-50 text-red-700' },
};

export function StatusBadge({ status, className }: { status: AnyStatus; className?: string }) {
  const cfg = STATUS_MAP[status] ?? {
    label: String(status),
    dot: 'bg-slate-400',
    chip: 'bg-slate-100 text-slate-700',
  };
  return (
    <span className={clsx('badge', cfg.chip, className)}>
      <span className={clsx('h-1.5 w-1.5 rounded-full', cfg.dot)} />
      {cfg.label}
    </span>
  );
}
