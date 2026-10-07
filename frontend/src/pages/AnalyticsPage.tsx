import { useQuery } from '@tanstack/react-query';
import { apiClient } from '../api/client';
import type { DashboardSummary } from '../types';
import { Mail, XCircle, CheckCircle2, Info, BarChart3 } from 'lucide-react';
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
} from 'recharts';
import { PageHeader, Card, StatCard, EmptyState } from '../components/ui';

export default function AnalyticsPage() {
  const { data: summary, isLoading } = useQuery<DashboardSummary>({
    queryKey: ['dashboard-summary'],
    queryFn: () => apiClient.get('/api/analytics/dashboard').then((r) => r.data),
  });

  const { data: volume } = useQuery<{ date: string; count: number }[]>({
    queryKey: ['daily-volume-30'],
    queryFn: () => apiClient.get('/api/analytics/volume/daily?days=30').then((r) => r.data),
  });

  const hasVolume = (volume?.length ?? 0) > 0 && (volume?.some((v) => v.count > 0) ?? false);

  return (
    <div className="page">
      <PageHeader
        title="Analytics"
        description="Platform-wide delivery performance and engagement."
      />

      {/* KPI cards */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatCard
          icon={CheckCircle2}
          label="Total Sent"
          value={summary?.totalEmailsSent?.toLocaleString() ?? 0}
          tone="emerald"
          loading={isLoading}
        />
        <StatCard
          icon={XCircle}
          label="Total Failed"
          value={summary?.totalEmailsFailed?.toLocaleString() ?? 0}
          tone="red"
          loading={isLoading}
        />
        <StatCard
          icon={Mail}
          label="Success Rate"
          value={summary ? `${summary.overallSuccessRate.toFixed(1)}%` : '0%'}
          tone="brand"
          loading={isLoading}
        />
      </div>

      {/* 30-day volume */}
      <Card className="mt-6">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="section-title">Daily Email Volume</h2>
          <span className="text-xs font-medium text-slate-400">Last 30 days</span>
        </div>
        {hasVolume ? (
          <ResponsiveContainer width="100%" height={300}>
            <BarChart data={volume ?? []} margin={{ left: -16, right: 8, top: 4 }}>
              <CartesianGrid strokeDasharray="3 3" stroke="#eef2f7" vertical={false} />
              <XAxis dataKey="date" tick={{ fill: '#94a3b8', fontSize: 10 }} tickLine={false} axisLine={{ stroke: '#e2e8f0' }} />
              <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} tickLine={false} axisLine={false} allowDecimals={false} />
              <Tooltip
                cursor={{ fill: '#f1f5f9' }}
                contentStyle={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: 12, boxShadow: '0 4px 12px -2px rgba(16,24,40,0.08)', fontSize: 12 }}
                labelStyle={{ color: '#475569', fontWeight: 600 }}
              />
              <Bar dataKey="count" name="Emails Sent" fill="#2563eb" radius={[4, 4, 0, 0]} maxBarSize={28} />
            </BarChart>
          </ResponsiveContainer>
        ) : (
          <EmptyState
            icon={BarChart3}
            title="No email activity yet"
            description="Launch a campaign to start seeing delivery analytics here."
          />
        )}
      </Card>

      {/* Note */}
      <Card className="mt-6 border-slate-200 bg-slate-50/60">
        <div className="flex gap-3">
          <Info className="h-5 w-5 shrink-0 text-slate-400" />
          <div>
            <h2 className="text-sm font-semibold text-slate-900">About these metrics</h2>
            <p className="mt-1 text-sm text-slate-500">
              Email open and click tracking depends on the recipient's email client behavior and may
              not be 100% reliable — some clients block tracking pixels. Delivery counts are accurate
              as they are confirmed by the SMTP server.
            </p>
          </div>
        </div>
      </Card>
    </div>
  );
}
