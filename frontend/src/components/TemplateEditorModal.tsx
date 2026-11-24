import { FormEvent, useMemo, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import { AlertCircle, ShieldAlert } from 'lucide-react';
import clsx from 'clsx';
import { templatesApi, type TemplatePayload } from '../api/templates';
import type { EmailTemplate } from '../types';
import { Button, Modal } from './ui';
import { TemplatePreviewPanel } from './TemplatePreviewPanel';
import { apiErrorMessage, apiErrorStatus, apiFieldErrors, isForbidden } from '../lib/errors';
import { noPermissionMessage } from '../lib/roles';
import { extractPlaceholders } from '../lib/recipientSelection';
import { TEMPLATE_LIMITS, type SampleData } from '../lib/templateSample';

interface TemplateEditorModalProps {
  /** The template to edit, or null to create a new one. */
  template: EmailTemplate | null;
  onClose: () => void;
  onSaved: (template: EmailTemplate) => void;
  /** Called when the template being edited no longer exists (the save returned 404). */
  onMissing?: () => void;
  sample: SampleData;
  onSampleChange: (sample: SampleData) => void;
}

interface FormState {
  name: string;
  description: string;
  subject: string;
  htmlBody: string;
  plainBody: string;
}

type FieldKey = keyof FormState;
/** Field errors, plus "body" when neither body is filled in. */
type FormErrors = Partial<Record<FieldKey | 'body', string>>;

const FIELD_KEYS: FieldKey[] = ['name', 'description', 'subject', 'htmlBody', 'plainBody'];

function formFrom(t: EmailTemplate | null): FormState {
  return {
    name: t?.name ?? '',
    description: t?.description ?? '',
    subject: t?.subject ?? '',
    htmlBody: t?.htmlBody ?? '',
    plainBody: t?.plainBody ?? '',
  };
}

function validate(f: FormState): FormErrors {
  const errors: FormErrors = {};
  if (!f.name.trim()) errors.name = 'A template name is required';
  else if (f.name.trim().length > TEMPLATE_LIMITS.name) errors.name = `Use at most ${TEMPLATE_LIMITS.name} characters`;
  if (!f.subject.trim()) errors.subject = 'A subject is required';
  else if (f.subject.trim().length > TEMPLATE_LIMITS.subject) {
    errors.subject = `Use at most ${TEMPLATE_LIMITS.subject} characters`;
  }
  if (!f.htmlBody.trim() && !f.plainBody.trim()) errors.body = 'Add an HTML body, a plain-text body, or both.';
  return errors;
}

/**
 * What is sent to POST/PUT /api/templates. Variables are never sent: the backend
 * extracts them from the subject and bodies. Bodies are sent as typed, because
 * whitespace can matter; an empty body is sent as null.
 */
function toPayload(f: FormState): TemplatePayload {
  return {
    name: f.name.trim(),
    description: f.description.trim() || null,
    subject: f.subject.trim(),
    htmlBody: f.htmlBody.trim() ? f.htmlBody : null,
    plainBody: f.plainBody.trim() ? f.plainBody : null,
  };
}

export function TemplateEditorModal({ template, onClose, onSaved, onMissing, sample, onSampleChange }: TemplateEditorModalProps) {
  const isEdit = !!template;
  const [initial] = useState<FormState>(() => formFrom(template));
  const [form, setForm] = useState<FormState>(initial);
  const [errors, setErrors] = useState<FormErrors>({});
  const [serverError, setServerError] = useState<string | null>(null);
  const [forbidden, setForbidden] = useState(false);
  // The template was deleted while it was being edited: saving again would fail the same way.
  const [missing, setMissing] = useState(false);
  const [tab, setTab] = useState<'edit' | 'preview'>('edit');

  const payload = toPayload(form);
  const placeholders = useMemo(
    () =>
      Array.from(
        new Set([
          ...extractPlaceholders(form.subject),
          ...extractPlaceholders(form.htmlBody),
          ...extractPlaceholders(form.plainBody),
        ]),
      ),
    [form.subject, form.htmlBody, form.plainBody],
  );

  const set = (key: FieldKey, value: string) => {
    setForm((f) => ({ ...f, [key]: value }));
    setErrors((e) => {
      if (!e[key] && !(e.body && (key === 'htmlBody' || key === 'plainBody'))) return e;
      const next = { ...e, [key]: undefined };
      if (key === 'htmlBody' || key === 'plainBody') next.body = undefined;
      return next;
    });
  };

  const saveMutation = useMutation({
    mutationFn: (p: TemplatePayload) =>
      template ? templatesApi.update(template.id, p) : templatesApi.create(p),
    onSuccess: (saved) => {
      toast.success(isEdit ? `Template "${saved.name}" updated` : `Template "${saved.name}" created`, {
        id: 'template-save',
      });
      onSaved(saved);
      onClose();
    },
    onError: (e: unknown) => {
      if (isForbidden(e)) {
        setForbidden(true);
        toast.error(noPermissionMessage('save templates'), { id: 'template-save' });
        return;
      }
      if (isEdit && apiErrorStatus(e) === 404) {
        const message =
          'This template no longer exists. It may have been deleted. Your changes are still here, so you can copy them.';
        setMissing(true);
        setServerError(message);
        toast.error(message, { id: 'template-save' });
        onMissing?.();
        return;
      }
      // Bean Validation errors (ApiError.fieldErrors) are shown next to their fields.
      const fieldErrors = apiFieldErrors(e);
      if (fieldErrors.length > 0) {
        const next: FormErrors = {};
        const other: string[] = [];
        for (const fe of fieldErrors) {
          if ((FIELD_KEYS as string[]).includes(fe.field)) next[fe.field as FieldKey] = fe.message;
          else other.push(`${fe.field}: ${fe.message}`);
        }
        setErrors(next);
        setServerError(other.length > 0 ? other.join('; ') : null);
        setTab('edit');
        toast.error('The template was not saved. Check the highlighted fields.', { id: 'template-save' });
        return;
      }
      const message = apiErrorMessage(e, 'Could not save the template.');
      setServerError(message);
      toast.error(message, { id: 'template-save' });
    },
  });
  const saving = saveMutation.isPending;

  const submit = (e?: FormEvent) => {
    e?.preventDefault();
    if (saving || forbidden || missing) return;
    const next = validate(form);
    setErrors(next);
    setServerError(null);
    if (Object.keys(next).length > 0) {
      setTab('edit');
      return;
    }
    saveMutation.mutate(payload);
  };

  const requestClose = () => {
    if (saving) return;
    const dirty = FIELD_KEYS.some((k) => form[k] !== initial[k]);
    if (dirty && !window.confirm('Discard your unsaved changes to this template?')) return;
    onClose();
  };

  const tooLong = [payload.htmlBody, payload.plainBody].some(
    (b) => (b?.length ?? 0) > TEMPLATE_LIMITS.previewBody,
  );
  const blockedReason = !payload.htmlBody && !payload.plainBody
    ? 'Add an HTML or plain-text body on the Edit tab to preview it.'
    : tooLong
    ? `The preview renders bodies of up to ${TEMPLATE_LIMITS.previewBody.toLocaleString()} characters. This body is longer; you can still save it.`
    : null;

  const inputClass = (key: FieldKey | 'body') =>
    clsx('input', errors[key] && 'border-red-400 focus:border-red-500 focus:ring-red-500/10');

  return (
    <Modal
      open
      onClose={requestClose}
      widthClass="max-w-4xl"
      title={isEdit ? `Edit template: ${template?.name ?? ''}` : 'New template'}
      description="Use placeholders such as {{FirstName}} or {{Company}}; they are filled for each recipient when a campaign is sent."
      footer={
        <>
          <Button variant="secondary" type="button" onClick={requestClose} disabled={saving}>
            Cancel
          </Button>
          <Button type="submit" form="template-form" loading={saving} disabled={forbidden || missing}>
            {saving ? 'Saving…' : isEdit ? 'Save changes' : 'Create template'}
          </Button>
        </>
      }
    >
      <div className="mb-4 flex gap-1 border-b border-slate-200 text-sm font-medium" role="tablist">
        {(['edit', 'preview'] as const).map((t) => (
          <button
            key={t}
            type="button"
            role="tab"
            aria-selected={tab === t}
            onClick={() => setTab(t)}
            className={clsx(
              '-mb-px border-b-2 px-3 py-2',
              tab === t ? 'border-brand-600 text-brand-700' : 'border-transparent text-slate-500 hover:text-slate-700',
            )}
          >
            {t === 'edit' ? 'Edit' : 'Preview with sample data'}
          </button>
        ))}
      </div>

      {forbidden && (
        <p role="alert" className="mb-4 flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
          {noPermissionMessage('save templates')} Your changes are still here, so you can copy them.
        </p>
      )}
      {serverError && !forbidden && (
        <p role="alert" className="mb-4 flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
          <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
          {serverError}
        </p>
      )}

      {/* The form stays mounted on the Preview tab so the footer's submit button always works. */}
      <form id="template-form" onSubmit={submit} noValidate className={clsx('space-y-4', tab !== 'edit' && 'hidden')}>
        <div>
          <label htmlFor="t-name" className="label">Name <span className="text-red-500">*</span></label>
          <input
            id="t-name"
            className={inputClass('name')}
            placeholder="e.g. Workshop invitation"
            value={form.name}
            maxLength={TEMPLATE_LIMITS.name}
            onChange={(e) => set('name', e.target.value)}
            aria-invalid={!!errors.name}
          />
          {errors.name && <FieldError message={errors.name} />}
        </div>

        <div>
          <label htmlFor="t-description" className="label">Description</label>
          <input
            id="t-description"
            className={inputClass('description')}
            placeholder="What this template is for (optional)"
            value={form.description}
            onChange={(e) => set('description', e.target.value)}
            aria-invalid={!!errors.description}
          />
          {errors.description && <FieldError message={errors.description} />}
        </div>

        <div>
          <label htmlFor="t-subject" className="label">Subject <span className="text-red-500">*</span></label>
          <input
            id="t-subject"
            className={inputClass('subject')}
            placeholder="e.g. Hello {{FirstName}}, you're invited"
            value={form.subject}
            maxLength={TEMPLATE_LIMITS.subject}
            onChange={(e) => set('subject', e.target.value)}
            aria-invalid={!!errors.subject}
          />
          {errors.subject ? (
            <FieldError message={errors.subject} />
          ) : (
            <p className="mt-1 text-xs text-slate-400">A campaign that uses this template starts with this subject and can change it.</p>
          )}
        </div>

        <div>
          <label htmlFor="t-html" className="label">HTML body</label>
          <textarea
            id="t-html"
            className={clsx(inputClass('htmlBody'), 'min-h-[180px] resize-y font-mono text-xs', errors.body && 'border-red-400')}
            placeholder="<p>Hi {{FirstName}},</p>"
            value={form.htmlBody}
            onChange={(e) => set('htmlBody', e.target.value)}
            aria-invalid={!!errors.htmlBody || !!errors.body}
            spellCheck={false}
          />
          {errors.htmlBody && <FieldError message={errors.htmlBody} />}
        </div>

        <div>
          <label htmlFor="t-plain" className="label">Plain-text body</label>
          <textarea
            id="t-plain"
            className={clsx(inputClass('plainBody'), 'min-h-[120px] resize-y text-sm', errors.body && 'border-red-400')}
            placeholder="Hi {{FirstName}}, …"
            value={form.plainBody}
            onChange={(e) => set('plainBody', e.target.value)}
            aria-invalid={!!errors.plainBody || !!errors.body}
          />
          {errors.plainBody && <FieldError message={errors.plainBody} />}
          {errors.body ? (
            <FieldError message={errors.body} />
          ) : (
            <p className="mt-1 text-xs text-slate-400">
              At least one body is required. Email clients that don't show HTML display the plain-text body.
            </p>
          )}
        </div>

        {placeholders.length > 0 && (
          <div className="flex flex-wrap items-center gap-1 text-xs">
            <span className="text-slate-500">Placeholders found:</span>
            {placeholders.map((p) => (
              <code key={p} className="rounded bg-accent-50 px-1.5 py-0.5 text-accent-700">{`{{${p}}}`}</code>
            ))}
          </div>
        )}
      </form>

      {tab === 'preview' && (
        <TemplatePreviewPanel
          sample={sample}
          onSampleChange={onSampleChange}
          render={(recipient) =>
            templatesApi.renderContent({
              subject: payload.subject,
              htmlBody: payload.htmlBody ?? null,
              plainBody: payload.plainBody ?? null,
              recipient,
            })
          }
          contentKey={JSON.stringify([payload.subject, payload.htmlBody, payload.plainBody])}
          placeholders={placeholders}
          blockedReason={blockedReason}
        />
      )}
    </Modal>
  );
}

function FieldError({ message }: { message: string }) {
  return (
    <p className="mt-1 flex items-center gap-1 text-xs text-red-600">
      <AlertCircle className="h-3.5 w-3.5" />
      {message}
    </p>
  );
}
