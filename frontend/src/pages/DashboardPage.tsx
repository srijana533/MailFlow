import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import {
  Send, Activity, CheckCircle2, TrendingUp, Mail, XCircle, Users, ArrowRight, Inbox,
} from 'lucide-react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip,
  PieChart, Pie, Cell, ResponsiveContainer,
} from 'recharts';
import { apiClient } from '../api/client';
import { campaignsApi } from '../api/campaigns';
import type { DashboardSummary, Campaign } from '../types';
import { StatCard, Card, StatusBadge, EmptyState } from '../components/ui';

const PIE_COLORS = ['#2563eb', '#ef4444'];

export default function DashboardPage() {
  const { data: summary, isLoading: summaryLoading } = useQuery<DashboardSummary>({
    queryKey: ['dashboard-summary'],
    queryFn: () => apiClient.get('/api/analytics/dashboard').then((r) => r.data),
  });

  const { data: volume } = useQuery<{ date: string; count: number }[]>({
    queryKey: ['daily-volume'],
    queryFn: () => apiClient.get('/api/analytics/volume/daily?days=14').then((r) => r.data),
  });

  const { data: campaignsPage, isLoading: campaignsLoading } = useQuery({
    queryKey: ['campaigns', 'dashboard-recent'],
    queryFn: () => campaignsApi.list({ size: 5, sort: 'createdAt', dir: 'desc' }),
  });

  const recentCampaigns = campaignsPage?.content ?? [];
  const totalEmails = (summary?.totalEmailsSent ?? 0) + (summary?.totalEmailsFailed ?? 0);
  const hasDeliveryData = totalEmails > 0;

  const pieData = [
    { name: 'Sent', value: summary?.totalEmailsSent ?? 0 },
    { name: 'Failed', value: summary?.totalEmailsFailed ?? 0 },
  ];

  const hasVolume = (volume?.length ?? 0) > 0 && (volume?.some((v) => v.count > 0) ?? false);

  return (
    <div className="page">
      <div className="mb-6">
        <h1 className="text-2xl font-bold tracking-tight text-slate-900">Dashboard</h1>
        <p className="mt-1 text-sm text-slate-500">
          Overview of your email campaign performance.
        </p>
      </div>

      {/* ── KPI cards (real data) ───────────────────────────────────────── */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          icon={Send}
          label="Total Campaigns"
          value={summary?.totalCampaigns ?? 0}
          tone="brand"
          loading={summaryLoading}
          sub={summary ? `${summary.activeCampaigns} active` : undefined}
        />
        <StatCard
          icon={Mail}
          label="Emails Sent"
          value={summary?.totalEmailsSent?.toLocaleString() ?? 0}
          tone="violet"
          loading={summaryLoading}
        />
        <StatCard
          icon={CheckCircle2}
          label="Delivery Rate"
          value={summary ? `${summary.overallSuccessRate.toFixed(1)}%` : '0%'}
          tone="emerald"
          loading={summaryLoading}
          sub="Confirmed by SMTP"
        />
        <StatCard
          icon={Users}
          label="Total Recipients"
          value={summary?.totalRecipients?.toLocaleString() ?? 0}
          tone="sky"
          loading={summaryLoading}
        />
      </div>

      {/* ── Secondary metrics ───────────────────────────────────────────── */}
      <div className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
        <StatCard
          icon={Activity}
          label="Active Campaigns"
          value={summary?.activeCampaigns ?? 0}
          tone="amber"
          loading={summaryLoading}
        />
        <StatCard
          icon={XCircle}
          label="Failed Emails"
          value={summary?.totalEmailsFailed?.toLocaleString() ?? 0}
          tone="red"
          loading={summaryLoading}
        />
        <StatCard
          icon={TrendingUp}
          label="Total Emails Processed"
          value={totalEmails.toLocaleString()}
          tone="brand"
          loading={summaryLoading}
        />
      </div>

      {/* ── Charts ──────────────────────────────────────────────────────── */}
      <div className="mt-6 grid grid-cols-1 gap-5 lg:grid-cols-3">
        {/* Volume */}
        <Card className="lg:col-span-2" padded>
          <div className="mb-4 flex items-center justify-between">
            <h2 className="section-title">Daily Email Volume</h2>
            <span className="text-xs font-medium text-slate-400">Last 14 days</span>
          </div>
          {hasVolume ? (
            <ResponsiveContainer width="100%" height={260}>
              <AreaChart data={volume ?? []} margin={{ left: -16, right: 8, top: 4 }}>
                <defs>
                  <linearGradient id="volGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#2563eb" stopOpacity={0.25} />
                    <stop offset="95%" stopColor="#2563eb" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" stroke="#eef2f7" vertical={false} />
                <XAxis dataKey="date" tick={{ fill: '#94a3b8', fontSize: 11 }} tickLine={false} axisLine={{ stroke: '#e2e8f0' }} />
                <YAxis tick={{ fill: '#94a3b8', fontSize: 11 }} tickLine={false} axisLine={false} allowDecimals={false} />
                <Tooltip
                  contentStyle={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: 12, boxShadow: '0 4px 12px -2px rgba(16,24,40,0.08)', fontSize: 12 }}
                  labelStyle={{ color: '#475569', fontWeight: 600 }}
                />
                <Area type="monotone" dataKey="count" name="Emails" stroke="#2563eb" strokeWidth={2.5} fill="url(#volGrad)" />
              </AreaChart>
            </ResponsiveContainer>
          ) : (
            <EmptyState
              icon={Inbox}
              title="No email activity yet"
              description="Once you launch a campaign, daily email volume will appear here."
            />
          )}
        </Card>

        {/* Delivery distribution */}
        <Card padded>
          <h2 className="section-title mb-4">Delivery Distribution</h2>
          {hasDeliveryData ? (
            <>
              <ResponsiveContainer width="100%" height={200}>
                <PieChart>
                  <Pie data={pieData} cx="50%" cy="50%" innerRadius={58} outerRadius={85} paddingAngle={3} dataKey="value" strokeWidth={0}>
                    {pieData.map((_, i) => (
                      <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} />
                    ))}
                  </Pie>
                  <Tooltip
                    contentStyle={{ background: '#fff', border: '1px solid #e2e8f0', borderRadius: 12, fontSize: 12 }}
                  />
                </PieChart>
              </ResponsiveContainer>
              <div className="mt-3 space-y-2">
                {pieData.map((d, i) => (
                  <div key={d.name} className="flex items-center justify-between text-sm">
                    <span className="flex items-center gap-2 text-slate-600">
                      <span className="h-2.5 w-2.5 rounded-full" style={{ backgroundColor: PIE_COLORS[i] }} />
                      {d.name}
                    </span>
                    <span className="font-semibold text-slate-900">{d.value.toLocaleString()}</span>
                  </div>
                ))}
              </div>
            </>
          ) : (
            <EmptyState
              icon={Mail}
              title="No deliveries yet"
              description="Delivery stats will show once emails are sent."
            />
          )}
        </Card>
      </div>

      {/* ── Recent campaigns ────────────────────────────────────────────── */}
      <Card className="mt-6" padded={false}>
        <div className="flex items-center justify-between border-b border-slate-200 px-6 py-4">
          <h2 className="section-title">Recent Campaigns</h2>
          <Link to="/campaigns" className="flex items-center gap-1 text-sm font-medium text-brand-600 hover:text-brand-700">
            View all <ArrowRight className="h-4 w-4" />
          </Link>
        </div>

        {campaignsLoading ? (
          <div className="space-y-3 p-6">
            {[0, 1, 2].map((i) => <div key={i} className="h-12 skeleton" />)}
          </div>
        ) : recentCampaigns.length === 0 ? (
          <EmptyState
            icon={Send}
            title="No campaigns yet"
            description="Create your first campaign to start reaching your audience."
            action={<Link to="/campaigns" className="btn-primary">Go to Campaigns</Link>}
          />
        ) : (
          <div className="divide-y divide-slate-100">
            {recentCampaigns.map((c: Campaign) => (
              <Link
                key={c.id}
                to={`/campaigns/${c.id}`}
                className="flex items-center justify-between gap-4 px-6 py-4 transition hover:bg-slate-50"
              >
                <div className="min-w-0">
                  <p className="truncate font-medium text-slate-900">{c.name}</p>
                  <p className="truncate text-sm text-slate-500">{c.subject}</p>
                </div>
                <div className="flex shrink-0 items-center gap-6">
                  <div className="hidden text-right sm:block">
                    <p className="text-sm font-semibold text-slate-900">
                      {c.sentCount.toLocaleString()}
                      <span className="font-normal text-slate-400"> / {c.totalRecipients.toLocaleString()}</span>
                    </p>
                    <p className="text-xs text-slate-400">sent</p>
                  </div>
                  <StatusBadge status={c.status} />
                </div>
              </Link>
            ))}
          </div>
        )}
      </Card>
    </div>
  );
}
