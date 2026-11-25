import { useState, FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { UserPlus } from 'lucide-react';
import toast from 'react-hot-toast';
import { PageHeader, Card, LoadingState, Button, Modal } from '../components/ui';
import { teamApi, type NewTeamMember } from '../api/team';
import { useAuthStore } from '../store/authStore';
import { apiErrorMessage } from '../lib/errors';
import type { TeamMember, TeamRole } from '../types';

const ROLE_LABELS: Record<TeamRole, string> = {
  ADMIN: 'Admin',
  CAMPAIGN_MANAGER: 'Campaign manager',
  VIEWER: 'Viewer',
};

const ROLE_HELP: Record<TeamRole, string> = {
  ADMIN: 'Everything, including managing the team',
  CAMPAIGN_MANAGER: 'Create and send campaigns, manage contacts and templates',
  VIEWER: 'Read-only: view campaigns and analytics',
};

const EMPTY: NewTeamMember = { email: '', firstName: '', lastName: '', password: '', role: 'CAMPAIGN_MANAGER' };

/** The Admin's company team: add people, change roles, disable accounts. Admin only (server-enforced). */
export default function TeamPage() {
  const qc = useQueryClient();
  const me = useAuthStore((s) => s.user);
  const [adding, setAdding] = useState(false);
  const [form, setForm] = useState<NewTeamMember>(EMPTY);

  const query = useQuery({ queryKey: ['team'], queryFn: teamApi.list });

  const addMutation = useMutation({
    mutationFn: teamApi.add,
    onSuccess: (m) => {
      qc.invalidateQueries({ queryKey: ['team'] });
      toast.success(`${m.firstName} added. Share their email and starting password with them.`);
      setAdding(false);
      setForm(EMPTY);
    },
    onError: (e) => toast.error(apiErrorMessage(e, 'Could not add this person.')),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, change }: { id: number; change: { role?: TeamRole; enabled?: boolean } }) =>
      teamApi.update(id, change),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['team'] }),
    onError: (e) => toast.error(apiErrorMessage(e, 'Could not update this person.')),
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (form.password.length < 8) {
      toast.error('The starting password needs at least 8 characters.');
      return;
    }
    addMutation.mutate({ ...form, email: form.email.trim(), firstName: form.firstName.trim(), lastName: form.lastName.trim() });
  };

  const field = (name: keyof NewTeamMember, label: string, type = 'text') => (
    <div>
      <label htmlFor={`team-${name}`} className="label">{label}</label>
      <input
        id={`team-${name}`}
        type={type}
        required
        className="input"
        value={form[name]}
        onChange={(e) => setForm((f) => ({ ...f, [name]: e.target.value }))}
        autoComplete={type === 'password' ? 'new-password' : 'off'}
      />
    </div>
  );

  return (
    <div className="page">
      <PageHeader
        title="Team"
        description={`People in ${me?.organizationName ?? 'your company'}. Only admins can see this page.`}
        actions={<Button onClick={() => setAdding(true)}><UserPlus className="h-4 w-4" /> Add person</Button>}
      />

      <Card padded={false}>
        {query.isLoading ? (
          <LoadingState label="Loading team…" />
        ) : query.isError ? (
          <p className="p-6 text-sm text-red-600">{apiErrorMessage(query.error, 'Could not load the team.')}</p>
        ) : (
          <table className="w-full text-sm">
            <thead className="border-b border-slate-200 bg-slate-50 text-left text-xs uppercase tracking-wide text-slate-500">
              <tr>
                <th className="px-4 py-3">Name</th>
                <th className="px-4 py-3">Email</th>
                <th className="px-4 py-3">Role</th>
                <th className="px-4 py-3">Status</th>
                <th className="px-4 py-3" />
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {(query.data ?? []).map((m: TeamMember) => {
                const self = m.id === me?.id;
                return (
                  <tr key={m.id}>
                    <td className="px-4 py-3 font-medium text-slate-900">
                      {m.firstName} {m.lastName} {self && <span className="text-xs text-slate-400">(you)</span>}
                    </td>
                    <td className="px-4 py-3 text-slate-600">{m.email}</td>
                    <td className="px-4 py-3">
                      <select
                        className="input py-1.5"
                        value={m.role}
                        disabled={self || updateMutation.isPending}
                        title={self ? "You can't change your own role" : ROLE_HELP[m.role]}
                        aria-label={`Role of ${m.firstName} ${m.lastName}`}
                        onChange={(e) => updateMutation.mutate({ id: m.id, change: { role: e.target.value as TeamRole } })}
                      >
                        {(Object.keys(ROLE_LABELS) as TeamRole[]).map((r) => (
                          <option key={r} value={r}>{ROLE_LABELS[r]}</option>
                        ))}
                      </select>
                    </td>
                    <td className="px-4 py-3">
                      <span className={m.enabled ? 'text-emerald-700' : 'text-slate-400'}>
                        {m.enabled ? 'Active' : 'Disabled'}
                      </span>
                    </td>
                    <td className="px-4 py-3 text-right">
                      {!self && (
                        <Button
                          variant={m.enabled ? 'ghost' : 'secondary'}
                          size="sm"
                          disabled={updateMutation.isPending}
                          onClick={() => updateMutation.mutate({ id: m.id, change: { enabled: !m.enabled } })}
                        >
                          {m.enabled ? 'Disable' : 'Enable'}
                        </Button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        )}
      </Card>

      <Modal
        open={adding}
        onClose={() => setAdding(false)}
        title="Add a person to your company"
        description="They sign in with this email and starting password. Share both with them."
      >
        <form onSubmit={submit} className="space-y-4">
          <div className="grid grid-cols-2 gap-3">
            {field('firstName', 'First name')}
            {field('lastName', 'Last name')}
          </div>
          {field('email', 'Email', 'email')}
          {field('password', 'Starting password (min. 8 characters)', 'password')}
          <div>
            <label htmlFor="team-role" className="label">Role</label>
            <select
              id="team-role"
              className="input"
              value={form.role}
              onChange={(e) => setForm((f) => ({ ...f, role: e.target.value as TeamRole }))}
            >
              {(Object.keys(ROLE_LABELS) as TeamRole[]).map((r) => (
                <option key={r} value={r}>{ROLE_LABELS[r]} — {ROLE_HELP[r]}</option>
              ))}
            </select>
          </div>
          <div className="flex justify-end gap-2 pt-2">
            <Button type="button" variant="secondary" onClick={() => setAdding(false)}>Cancel</Button>
            <Button type="submit" loading={addMutation.isPending}>Add person</Button>
          </div>
        </form>
      </Modal>
    </div>
  );
}
