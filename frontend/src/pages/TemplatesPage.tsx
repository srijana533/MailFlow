import { useEffect, useState } from 'react';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import { format } from 'date-fns';
import clsx from 'clsx';
import {
  AlertTriangle, ChevronLeft, ChevronRight, Eye, FileText, Lock, Mail, Pencil, Plus, RefreshCw, Trash2,
} from 'lucide-react';
import { templatesApi } from '../api/templates';
import type { EmailTemplate } from '../types';
import { useAuthStore } from '../store/authStore';
import { PageHeader, Card, EmptyState, LoadingState, Button, Modal } from '../components/ui';
import { TemplateEditorModal } from '../components/TemplateEditorModal';
import { TemplatePreviewPanel } from '../components/TemplatePreviewPanel';
import { apiErrorMessage, apiErrorStatus, isForbidden } from '../lib/errors';
import { canManage, noPermissionMessage } from '../lib/roles';
import { DEFAULT_SAMPLE, type SampleData } from '../lib/templateSample';

const PAGE_SIZE = 24;

function formatDate(value?: string | null): string {
  return value ? format(new Date(value), 'MMM d, yyyy') : '—';
}

function hasBody(t: EmailTemplate): boolean {
  return !!t.htmlBody?.trim() || !!t.plainBody?.trim();
}

export default function TemplatesPage() {
  const qc = useQueryClient();
  // VIEWER is read-only: the backend allows it GET requests only (SecurityConfig), so
  // the write actions and the preview with sample data are not offered.
  const canWrite = useAuthStore((s) => canManage(s.roles));
  const [page, setPage] = useState(0);
  const [viewing, setViewing] = useState<EmailTemplate | null>(null);
  const [editing, setEditing] = useState<{ template: EmailTemplate | null } | null>(null);
  const [deleting, setDeleting] = useState<EmailTemplate | null>(null);
  // Kept here so the sample values survive closing and reopening a preview.
  const [sample, setSample] = useState<SampleData>(DEFAULT_SAMPLE);

  const query = useQuery({
    queryKey: ['templates', 'list', page],
    queryFn: () => templatesApi.list({ page, size: PAGE_SIZE }),
    placeholderData: keepPreviousData,
  });
  const data = query.data;
  const templates = data?.content ?? [];

  // Deleting the last template on a page leaves it empty: go back one page.
  useEffect(() => {
    if (data && !query.isPlaceholderData && data.content.length === 0 && page > 0) {
      setPage((p) => Math.max(0, p - 1));
    }
  }, [data, query.isPlaceholderData, page]);

  // The ['templates'] prefix covers this list and the campaign form's
  // ['templates', 'campaign-form']. The campaign form also caches single templates
  // (['template', id]) and rendered previews (['template-render', …]).
  const refreshTemplates = () => {
    qc.invalidateQueries({ queryKey: ['templates'] });
    qc.invalidateQueries({ queryKey: ['template'] });
    qc.invalidateQueries({ queryKey: ['template-render'] });
  };

  const deleteMutation = useMutation({
    mutationFn: (t: EmailTemplate) => templatesApi.delete(t.id),
    onSuccess: (_res, t) => {
      toast.success(`Template "${t.name}" deleted`, { id: `template-delete-${t.id}` });
      setDeleting(null);
      refreshTemplates();
    },
    onError: (e: unknown, t) => {
      const id = `template-delete-${t.id}`;
      if (isForbidden(e)) {
        toast.error(noPermissionMessage('delete templates'), { id });
      } else if (apiErrorStatus(e) === 404) {
        toast.error(`Template "${t.name}" no longer exists. It may have been deleted already.`, { id });
        setDeleting(null);
        refreshTemplates();
      } else {
        toast.error(apiErrorMessage(e, 'Could not delete the template.'), { id });
      }
    },
  });

  const openCreate = () => setEditing({ template: null });
  const openEdit = (t: EmailTemplate) => {
    setViewing(null);
    setEditing({ template: t });
  };
  const askDelete = (t: EmailTemplate) => {
    setViewing(null);
    setDeleting(t);
  };

  const newButton = (
    <Button onClick={openCreate}>
      <Plus className="h-4 w-4" /> New Template
    </Button>
  );

  const total = data?.totalElements ?? 0;
  const first = total === 0 ? 0 : page * PAGE_SIZE + 1;
  const last = Math.min(total, page * PAGE_SIZE + templates.length);

  return (
    <div className="page">
      <PageHeader
        title="Email Templates"
        description="Reusable email templates with personalization placeholders such as {{FirstName}}."
        actions={canWrite ? newButton : undefined}
      />

      {!canWrite && (
        <p className="mb-5 flex items-start gap-2 rounded-xl border border-slate-200 bg-white px-4 py-3 text-sm text-slate-600">
          <Lock className="mt-0.5 h-4 w-4 shrink-0 text-slate-400" />
          You have read-only access. Admins and campaign managers can create, edit and delete templates and
          preview them with sample data.
        </p>
      )}

      {query.isLoading ? (
        <Card padded={false}>
          <LoadingState label="Loading templates…" />
        </Card>
      ) : query.isError ? (
        <Card className="flex flex-col items-center gap-3 py-12 text-center">
          <AlertTriangle className="h-7 w-7 text-amber-500" />
          <p className="max-w-md text-sm text-slate-700">
            {isForbidden(query.error)
              ? noPermissionMessage('view templates')
              : apiErrorMessage(query.error, 'Could not load the templates.')}
          </p>
          <Button variant="secondary" onClick={() => query.refetch()} loading={query.isFetching}>
            {!query.isFetching && <RefreshCw className="h-4 w-4" />} Try again
          </Button>
        </Card>
      ) : templates.length === 0 ? (
        <Card padded={false}>
          <EmptyState
            icon={FileText}
            title="No templates yet"
            description={
              canWrite
                ? 'Create a reusable email template with placeholders like {{FirstName}} and {{Company}}.'
                : 'Admins and campaign managers can create templates.'
            }
            action={canWrite ? newButton : undefined}
          />
        </Card>
      ) : (
        <>
          <div className={clsx('grid grid-cols-1 gap-5 sm:grid-cols-2 xl:grid-cols-3', query.isPlaceholderData && 'opacity-60')}>
            {templates.map((t) => (
              <Card key={t.id} hoverable className="flex flex-col">
                {/* Header */}
                <div className="mb-3 flex items-start justify-between gap-3">
                  <button
                    type="button"
                    className="flex min-w-0 items-center gap-3 text-left"
                    onClick={() => setViewing(t)}
                    title="View template"
                  >
                    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-brand-50 text-brand-600">
                      <Mail className="h-5 w-5" />
                    </div>
                    <h3 className="line-clamp-1 font-semibold text-slate-900 hover:text-brand-700">{t.name}</h3>
                  </button>
                  <div className="flex shrink-0 gap-1">
                    <button
                      type="button"
                      className="btn-icon text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                      aria-label={`View template ${t.name}`}
                      title="View"
                      onClick={() => setViewing(t)}
                    >
                      <Eye className="h-4 w-4" />
                    </button>
                    {canWrite && (
                      <>
                        <button
                          type="button"
                          className="btn-icon text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                          aria-label={`Edit template ${t.name}`}
                          title="Edit"
                          onClick={() => openEdit(t)}
                        >
                          <Pencil className="h-4 w-4" />
                        </button>
                        <button
                          type="button"
                          className="btn-icon text-slate-400 hover:bg-red-50 hover:text-red-600"
                          aria-label={`Delete template ${t.name}`}
                          title="Delete"
                          onClick={() => askDelete(t)}
                        >
                          <Trash2 className="h-4 w-4" />
                        </button>
                      </>
                    )}
                  </div>
                </div>

                {/* Description */}
                <p className="mb-3 line-clamp-2 text-sm text-slate-500">
                  {t.description || 'No description provided.'}
                </p>

                {/* Subject preview */}
                <div className="mb-4 rounded-lg border border-slate-100 bg-slate-50 px-3 py-2">
                  <p className="text-xs font-medium text-slate-400">Subject</p>
                  <p className="line-clamp-1 text-sm text-slate-700">{t.subject}</p>
                </div>

                {/* Variables */}
                {(t.variables ?? []).length > 0 && (
                  <div className="mb-4 flex flex-wrap gap-1.5">
                    {(t.variables ?? []).map((v) => (
                      <span
                        key={v}
                        className="rounded-md bg-accent-50 px-2 py-0.5 font-mono text-xs text-accent-700"
                      >
                        {`{{${v}}}`}
                      </span>
                    ))}
                  </div>
                )}

                {!hasBody(t) && (
                  <p className="mb-4 flex items-center gap-1.5 text-xs text-amber-700">
                    <AlertTriangle className="h-3.5 w-3.5" /> This template has no body yet.
                  </p>
                )}

                {/* Footer */}
                <p className="mt-auto border-t border-slate-100 pt-3 text-xs text-slate-400">
                  Created {formatDate(t.createdAt)}
                  {t.updatedAt && t.updatedAt !== t.createdAt ? ` · Updated ${formatDate(t.updatedAt)}` : ''}
                </p>
              </Card>
            ))}
          </div>

          {(data?.totalPages ?? 0) > 1 && (
            <div className="mt-5 flex items-center justify-between text-sm text-slate-500">
              <span>
                {first.toLocaleString()}–{last.toLocaleString()} of {total.toLocaleString()} templates
              </span>
              <div className="flex gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0 || query.isFetching}
                >
                  <ChevronLeft className="h-4 w-4" /> Previous
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => setPage((p) => p + 1)}
                  disabled={!!data?.last || query.isFetching}
                >
                  Next <ChevronRight className="h-4 w-4" />
                </Button>
              </div>
            </div>
          )}
        </>
      )}

      {viewing && (
        <TemplateViewModal
          key={viewing.id}
          template={viewing}
          canWrite={canWrite}
          sample={sample}
          onSampleChange={setSample}
          onClose={() => setViewing(null)}
          onEdit={() => openEdit(viewing)}
          onDelete={() => askDelete(viewing)}
        />
      )}

      {editing && (
        <TemplateEditorModal
          key={editing.template?.id ?? 'new'}
          template={editing.template}
          sample={sample}
          onSampleChange={setSample}
          onClose={() => setEditing(null)}
          onMissing={refreshTemplates}
          onSaved={() => {
            // A new template is listed first (newest first), on the first page.
            if (!editing.template) setPage(0);
            refreshTemplates();
          }}
        />
      )}

      {/* Delete confirmation */}
      <Modal
        open={!!deleting}
        onClose={() => !deleteMutation.isPending && setDeleting(null)}
        title={`Delete template "${deleting?.name ?? ''}"?`}
        description="It disappears from the template list and can no longer be chosen for a campaign."
        footer={
          <>
            <Button variant="secondary" onClick={() => setDeleting(null)} disabled={deleteMutation.isPending}>
              Keep template
            </Button>
            <Button
              variant="danger"
              onClick={() => deleting && deleteMutation.mutate(deleting)}
              loading={deleteMutation.isPending}
            >
              {deleteMutation.isPending ? 'Deleting…' : 'Delete template'}
            </Button>
          </>
        }
      >
        <ul className="list-disc space-y-2 pl-5 text-sm text-slate-700">
          <li>
            <b>This is a soft delete.</b> The template is hidden, not erased from the database, but it can't be
            restored from MailFlow.
          </li>
          <li>
            <b>Campaign history is kept.</b> Campaigns that already used it keep their sent emails, delivery results
            and statistics.
          </li>
          <li>
            <b>Drafts that use it can't be launched</b> until you choose another template for them: open the
            campaign and choose Edit draft. A scheduled campaign that uses it won't start.
          </li>
          <li>Campaigns that are already sending keep using its content, and so does a paused campaign when it is resumed.</li>
        </ul>
      </Modal>
    </div>
  );
}

interface TemplateViewModalProps {
  template: EmailTemplate;
  canWrite: boolean;
  sample: SampleData;
  onSampleChange: (sample: SampleData) => void;
  onClose: () => void;
  onEdit: () => void;
  onDelete: () => void;
}

/** Shows a saved template as written; managers can also preview it with sample data. */
function TemplateViewModal({ template, canWrite, sample, onSampleChange, onClose, onEdit, onDelete }: TemplateViewModalProps) {
  const [tab, setTab] = useState<'content' | 'preview'>('content');
  const variables = template.variables ?? [];
  const tabs: { key: 'content' | 'preview'; label: string }[] = canWrite
    ? [
        { key: 'content', label: 'Content' },
        { key: 'preview', label: 'Preview with sample data' },
      ]
    : [{ key: 'content', label: 'Content' }];

  return (
    <Modal
      open
      onClose={onClose}
      widthClass="max-w-4xl"
      title={template.name}
      description={template.description || undefined}
      footer={
        <>
          {canWrite && (
            <Button variant="ghost" className="mr-auto text-red-600 hover:bg-red-50 hover:text-red-700" onClick={onDelete}>
              <Trash2 className="h-4 w-4" /> Delete
            </Button>
          )}
          <Button variant="secondary" onClick={onClose}>Close</Button>
          {canWrite && (
            <Button onClick={onEdit}>
              <Pencil className="h-4 w-4" /> Edit
            </Button>
          )}
        </>
      }
    >
      {tabs.length > 1 && (
        <div className="mb-4 flex gap-1 border-b border-slate-200 text-sm font-medium" role="tablist">
          {tabs.map((t) => (
            <button
              key={t.key}
              type="button"
              role="tab"
              aria-selected={tab === t.key}
              onClick={() => setTab(t.key)}
              className={clsx(
                '-mb-px border-b-2 px-3 py-2',
                tab === t.key ? 'border-brand-600 text-brand-700' : 'border-transparent text-slate-500 hover:text-slate-700',
              )}
            >
              {t.label}
            </button>
          ))}
        </div>
      )}

      {tab === 'content' || !canWrite ? (
        <div className="space-y-4 text-sm">
          <div>
            <p className="text-xs font-medium text-slate-500">Subject</p>
            <p className="font-medium text-slate-900">{template.subject}</p>
          </div>

          {variables.length > 0 && (
            <div className="flex flex-wrap items-center gap-1 text-xs">
              <span className="text-slate-500">Placeholders:</span>
              {variables.map((v) => (
                <code key={v} className="rounded bg-accent-50 px-1.5 py-0.5 text-accent-700">{`{{${v}}}`}</code>
              ))}
            </div>
          )}

          <div>
            <p className="mb-1 text-xs font-medium text-slate-500">HTML body, as written (placeholders not filled)</p>
            {template.htmlBody?.trim() ? (
              <>
                {/* sandbox="" blocks scripts, forms, popups and same-origin access in the template's HTML. */}
                <iframe
                  title={`HTML body of ${template.name}`}
                  sandbox=""
                  srcDoc={template.htmlBody}
                  className="h-72 w-full rounded-lg border border-slate-200 bg-white"
                />
                <details className="mt-2">
                  <summary className="cursor-pointer text-xs font-medium text-brand-600">Show HTML source</summary>
                  <pre className="mt-1 max-h-64 overflow-auto whitespace-pre-wrap rounded-lg border border-slate-200 bg-slate-50 p-3 font-mono text-xs text-slate-700">
                    {template.htmlBody}
                  </pre>
                </details>
              </>
            ) : (
              <p className="text-slate-400">No HTML body.</p>
            )}
          </div>

          <div>
            <p className="mb-1 text-xs font-medium text-slate-500">Plain-text body</p>
            {template.plainBody?.trim() ? (
              <pre className="max-h-64 overflow-auto whitespace-pre-wrap rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs text-slate-700">
                {template.plainBody}
              </pre>
            ) : (
              <p className="text-slate-400">No plain-text body.</p>
            )}
          </div>

          <p className="text-xs text-slate-400">
            Created {formatDate(template.createdAt)} · Last updated {formatDate(template.updatedAt)}
          </p>
        </div>
      ) : (
        <TemplatePreviewPanel
          sample={sample}
          onSampleChange={onSampleChange}
          render={(recipient) => templatesApi.render(template.id, { recipient })}
          contentKey={`saved:${template.id}:${template.updatedAt}`}
          placeholders={variables}
          blockedReason={hasBody(template) ? null : 'This template has no body to preview. Edit it to add one.'}
        />
      )}
    </Modal>
  );
}
