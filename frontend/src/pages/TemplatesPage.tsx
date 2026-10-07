import { useQuery } from '@tanstack/react-query';
import { Plus, Eye, Trash2, FileText, Mail } from 'lucide-react';
import { apiClient } from '../api/client';
import type { EmailTemplate, PageResponse } from '../types';
import { format } from 'date-fns';
import { PageHeader, Card, EmptyState, LoadingState, Button } from '../components/ui';

export default function TemplatesPage() {
  const { data, isLoading } = useQuery<PageResponse<EmailTemplate>>({
    queryKey: ['templates'],
    queryFn: () => apiClient.get('/api/templates').then((r) => r.data),
  });

  const templates = data?.content ?? [];

  return (
    <div className="page">
      <PageHeader
        title="Email Templates"
        description="Reusable email templates with personalization variables."
        actions={
          <Button>
            <Plus className="h-4 w-4" /> New Template
          </Button>
        }
      />

      {isLoading ? (
        <Card padded={false}>
          <LoadingState label="Loading templates…" />
        </Card>
      ) : templates.length === 0 ? (
        <Card padded={false}>
          <EmptyState
            icon={FileText}
            title="No templates yet"
            description="Create a reusable email template with variables like {{name}} and {{company}}."
            action={<Button><Plus className="h-4 w-4" /> New Template</Button>}
          />
        </Card>
      ) : (
        <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 xl:grid-cols-3">
          {templates.map((t: EmailTemplate) => (
            <Card key={t.id} hoverable className="flex flex-col">
              {/* Header */}
              <div className="mb-3 flex items-start justify-between gap-3">
                <div className="flex min-w-0 items-center gap-3">
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-xl bg-brand-50 text-brand-600">
                    <Mail className="h-5 w-5" />
                  </div>
                  <h3 className="line-clamp-1 font-semibold text-slate-900">{t.name}</h3>
                </div>
                <div className="flex shrink-0 gap-1">
                  <button
                    className="btn-icon text-slate-400 hover:bg-slate-100 hover:text-slate-600"
                    aria-label="Preview template"
                    title="Preview"
                  >
                    <Eye className="h-4 w-4" />
                  </button>
                  <button
                    className="btn-icon text-slate-400 hover:bg-red-50 hover:text-red-600"
                    aria-label="Delete template"
                    title="Delete"
                  >
                    <Trash2 className="h-4 w-4" />
                  </button>
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
              {t.variables.length > 0 && (
                <div className="mb-4 flex flex-wrap gap-1.5">
                  {t.variables.map((v) => (
                    <span
                      key={v}
                      className="rounded-md bg-accent-50 px-2 py-0.5 font-mono text-xs text-accent-700"
                    >
                      {`{{${v}}}`}
                    </span>
                  ))}
                </div>
              )}

              {/* Footer */}
              <p className="mt-auto border-t border-slate-100 pt-3 text-xs text-slate-400">
                Created {format(new Date(t.createdAt), 'MMM d, yyyy')}
              </p>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
