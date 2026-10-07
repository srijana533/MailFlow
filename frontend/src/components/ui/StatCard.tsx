import { ElementType, ReactNode } from 'react';
import clsx from 'clsx';

type Tone = 'brand' | 'emerald' | 'amber' | 'red' | 'violet' | 'sky';

interface StatCardProps {
  icon: ElementType;
  label: string;
  value: ReactNode;
  /** Optional sub-text under the value (e.g. a delta or caption). */
  sub?: ReactNode;
  tone?: Tone;
  loading?: boolean;
}

const TONE: Record<Tone, string> = {
  brand:   'bg-brand-50 text-brand-600',
  emerald: 'bg-emerald-50 text-emerald-600',
  amber:   'bg-amber-50 text-amber-600',
  red:     'bg-red-50 text-red-600',
  violet:  'bg-accent-50 text-accent-600',
  sky:     'bg-sky-50 text-sky-600',
};

export function StatCard({ icon: Icon, label, value, sub, tone = 'brand', loading = false }: StatCardProps) {
  return (
    <div className="card card-pad card-hoverable">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="truncate text-sm font-medium text-slate-500">{label}</p>
          {loading ? (
            <div className="mt-2 h-8 w-20 skeleton" />
          ) : (
            <p className="mt-1.5 text-3xl font-bold tracking-tight text-slate-900">{value}</p>
          )}
          {sub && !loading && <p className="mt-1 text-xs text-slate-400">{sub}</p>}
        </div>
        <div className={clsx('flex h-11 w-11 shrink-0 items-center justify-center rounded-xl', TONE[tone])}>
          <Icon className="h-5 w-5" />
        </div>
      </div>
    </div>
  );
}
