import { useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import clsx from 'clsx';
import {
  AlertCircle, AlertTriangle, Eye, EyeOff, FileText, Info, LayoutTemplate, Loader2, PenLine, Sparkles,
} from 'lucide-react';
import { templatesApi, type RenderedEmail } from '../api/templates';
import type { EmailTemplate } from '../types';
import { Button } from './ui';
import {
  applyTemplateChange,
  BODY_MAX_LENGTH,
  customize,
  customizeFromTemplate,
  insertAtCursor,
  placeholderChips,
  placeholderToken,
  planTemplateChange,
  SAMPLE_RECIPIENT,
  sampleFor,
  SUBJECT_MAX_LENGTH,
  switchToTemplateContent,
  type ContentState,
  type TemplateChangePlan,
} from '../lib/campaignContent';
import type { CombinedRecipient } from '../lib/recipientSelection';
import { apiErrorMessage, apiErrorStatus } from '../lib/errors';

/** A stored template that isn't in the list: deleted, or still loading / failed to load. */
export interface StoredTemplateOption {
  id: number;
  name: string;
}

interface CampaignContentSectionProps {
  /** Templates to start from (the active list, plus the draft's own template once loaded). */
  templates: EmailTemplate[];
  templatesLoading: boolean;
  templatesError: string | null;
  /** The draft's template when it was deleted: shown as a disabled option. */
  deletedTemplate: StoredTemplateOption | null;
  /** The draft's template while it is loading or couldn't be loaded: selectable under its stored name. */
  unresolvedTemplate: StoredTemplateOption | null;
  unresolvedTemplateError: string | null;
  /** A saved draft's template can be replaced but not removed, so "No template" is hidden. */
  templateRequired: boolean;
  content: ContentState;
  onContentChange: (next: ContentState) => void;
  subject: string;
  onSubjectChange: (next: string) => void;
  subjectError: string | null;
  bodyError: string | null;
  /** Custom fields the selected recipients bring (offered as chips after the standard placeholders). */
  customKeys: string[];
  /** Recipients the preview can render for (the final list). */
  previewRecipients: CombinedRecipient[];
  onImproveWithAi: () => void;
}

interface PendingChange {
  templateId: number;
  plan: TemplateChangePlan;
  /** Also replace the subject the user typed with the template's subject. */
  replaceSubject: boolean;
}

/**
 * The "Email Content" section of the campaign form (on the Recipients step, below the
 * recipients). The content is campaign-wide: the same subject and body for every
 * recipient, personalised only through placeholders.
 *
 * - "Start from a template" is optional. Choosing one fills the subject (asking before
 *   replacing a subject the user typed) and uses the template's content unchanged.
 * - "Customize for this campaign" copies the template's text into the editor; from then
 *   on the campaign's own body is sent. "Use the template's content" goes back.
 * - The body is plain text. Chips insert placeholders at the cursor.
 * - "Preview" renders on the server exactly what is sent, for a chosen recipient or
 *   sample values.
 */
export function CampaignContentSection(props: CampaignContentSectionProps) {
  const {
    templates, templatesLoading, templatesError, deletedTemplate, unresolvedTemplate, unresolvedTemplateError,
    templateRequired, content, onContentChange, subject, onSubjectChange, subjectError, bodyError, customKeys,
    previewRecipients, onImproveWithAi,
  } = props;

  const [pending, setPending] = useState<PendingChange | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [previewOpen, setPreviewOpen] = useState(false);

  const subjectRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  // The field a chip goes into, and whether each field has had the cursor (else: append).
  const lastField = useRef<'subject' | 'body'>('body');
  const touched = useRef({ subject: false, body: false });
  const caret = useRef<{ field: 'subject' | 'body'; position: number } | null>(null);

  // After a chip was inserted: keep the focus in the field, with the cursor after the placeholder.
  useEffect(() => {
    const c = caret.current;
    if (!c) return;
    caret.current = null;
    const el = c.field === 'body' ? bodyRef.current : subjectRef.current;
    if (el) {
      el.focus();
      el.setSelectionRange(c.position, c.position);
    }
  });

  const template = content.templateId == null ? null : templates.find((t) => t.id === content.templateId) ?? null;
  const templateIsDeleted = !!deletedTemplate && content.templateId === deletedTemplate.id;
  const templateName = template?.name ?? (templateIsDeleted ? deletedTemplate?.name : unresolvedTemplate?.name) ?? 'the template';
  const templateWithoutBody = !!template && !template.htmlBody?.trim() && !template.plainBody?.trim();
  const usesTemplate = content.source === 'template' && content.templateId != null;
  const chips = placeholderChips(customKeys);

  const chooseTemplate = (value: string) => {
    setNotice(null);
    if (!value) {
      setPending(null);
      onContentChange(applyTemplateChange(content, null, true));
      return;
    }
    const id = Number(value);
    const next = templates.find((t) => t.id === id) ?? null;
    const plan = planTemplateChange({ subject, previous: template, next, state: content });
    if (plan.askBody || plan.askSubject) {
      setPending({ templateId: id, plan, replaceSubject: false });
      return;
    }
    setPending(null);
    onContentChange(applyTemplateChange(content, id, false));
    if (plan.fillSubject && next) onSubjectChange(next.subject);
  };

  const confirmPending = (keepOwnText: boolean) => {
    if (!pending) return;
    const next = templates.find((t) => t.id === pending.templateId) ?? null;
    onContentChange(applyTemplateChange(content, pending.templateId, keepOwnText));
    if (next && (pending.plan.fillSubject || (pending.plan.askSubject && pending.replaceSubject))) {
      onSubjectChange(next.subject);
    }
    setPending(null);
  };

  const startCustomizing = () => {
    if (!template) return;
    const result = customizeFromTemplate(template);
    onContentChange(customize(content, template));
    touched.current.body = false;
    setNotice(
      result.from === 'html'
        ? `"${template.name}" has only an HTML body, so its text was copied. The formatting (layout, images, link addresses) is lost: this campaign sends plain text.`
        : result.formattingLost
        ? `The plain-text version of "${template.name}" was copied. Its HTML design isn't used: this campaign sends plain text.`
        : `The text of "${template.name}" was copied. Changes here don't change the template.`,
    );
  };

  const backToTemplate = () => {
    if (content.body.trim() && !window.confirm(`Remove your text and send the content of "${templateName}" instead?`)) return;
    setNotice(null);
    onContentChange(switchToTemplateContent(content));
  };

  const insertChip = (key: string) => {
    const token = placeholderToken(key);
    const field = usesTemplate ? 'subject' : lastField.current;
    if (field === 'body') {
      const el = bodyRef.current;
      const r = insertAtCursor(content.body, token, touched.current.body ? el?.selectionStart : null,
        touched.current.body ? el?.selectionEnd : null);
      onContentChange({ ...content, source: 'own', body: r.value });
      caret.current = { field: 'body', position: r.cursor };
    } else {
      const el = subjectRef.current;
      const r = insertAtCursor(subject, token, touched.current.subject ? el?.selectionStart : null,
        touched.current.subject ? el?.selectionEnd : null);
      onSubjectChange(r.value);
      caret.current = { field: 'subject', position: r.cursor };
    }
  };

  const nextTemplate = pending ? templates.find((t) => t.id === pending.templateId) : null;

  return (
    <section className="space-y-4 rounded-xl border border-slate-200 p-4" aria-labelledby="email-content-title">
      <div>
        <h3 id="email-content-title" className="flex items-center gap-2 text-sm font-semibold text-slate-900">
          <PenLine className="h-4 w-4 text-brand-500" /> Email Content
        </h3>
        <p className="mt-0.5 text-xs text-slate-500">
          One subject and one email for everyone in the list above, whichever way you added them. Placeholders such as
          {' '}{'{{FirstName}}'} are filled in for each recipient.
        </p>
      </div>

      {/* Template (optional) */}
      <div>
        <label htmlFor="c-template" className="label">Start from a template <span className="text-slate-400">(optional)</span></label>
        <select
          id="c-template"
          className="input"
          value={content.templateId != null ? String(content.templateId) : ''}
          onChange={(e) => chooseTemplate(e.target.value)}
          disabled={templatesLoading}
        >
          {!templateRequired && <option value="">No template: write the email below</option>}
          {deletedTemplate && (
            <option value={String(deletedTemplate.id)} disabled>{deletedTemplate.name} (deleted)</option>
          )}
          {unresolvedTemplate && <option value={String(unresolvedTemplate.id)}>{unresolvedTemplate.name}</option>}
          {templates.map((t) => (
            <option key={t.id} value={t.id}>{t.name}</option>
          ))}
        </select>
        {templatesError && <p className="mt-1 text-xs text-red-600">Could not load templates. {templatesError}</p>}
        {unresolvedTemplateError && (
          <p className="mt-1 text-xs text-red-600">Could not load this draft's template. {unresolvedTemplateError}</p>
        )}
        {templateRequired && (
          <p className="mt-1 text-xs text-slate-400">
            A saved draft's template can be replaced but not removed. To send your own text instead, choose
            "Customize for this campaign".
          </p>
        )}
      </div>

      {pending && nextTemplate && (
        <div role="alert" className="space-y-2 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-900">
          <p className="font-medium">Use the template "{nextTemplate.name}"?</p>
          {pending.plan.askBody && (
            <p className="text-xs">
              You have written your own email. Choosing this template replaces your text with the template's content,
              unless you keep your text.
            </p>
          )}
          {pending.plan.askSubject && (
            <label className="flex items-start gap-2 text-xs">
              <input
                type="checkbox"
                className="mt-0.5 h-4 w-4 rounded border-amber-300"
                checked={pending.replaceSubject}
                onChange={(e) => setPending({ ...pending, replaceSubject: e.target.checked })}
              />
              <span>
                Also replace your subject “{subject.trim()}” with the template's subject “{nextTemplate.subject}”
              </span>
            </label>
          )}
          <div className="flex flex-wrap gap-2">
            {pending.plan.askBody ? (
              <>
                <Button type="button" size="sm" onClick={() => confirmPending(false)}>Replace my text</Button>
                <Button type="button" size="sm" variant="secondary" onClick={() => confirmPending(true)}>Keep my text</Button>
              </>
            ) : (
              <Button type="button" size="sm" onClick={() => confirmPending(false)}>Use this template</Button>
            )}
            <Button type="button" size="sm" variant="ghost" onClick={() => setPending(null)}>Cancel</Button>
          </div>
        </div>
      )}

      {/* Subject */}
      <div>
        <div className="flex items-center justify-between">
          <label htmlFor="c-subject" className="label">Subject <span className="text-red-500">*</span></label>
          <button
            type="button"
            onClick={onImproveWithAi}
            className="mb-1.5 inline-flex items-center gap-1 text-xs font-medium text-brand-600 hover:text-brand-700 hover:underline"
          >
            <Sparkles className="h-3.5 w-3.5" /> Improve with AI
          </button>
        </div>
        <input
          id="c-subject"
          ref={subjectRef}
          className={clsx('input', subjectError && 'border-red-400 focus:border-red-500 focus:ring-red-500/10')}
          placeholder="Enter your email subject"
          value={subject}
          maxLength={SUBJECT_MAX_LENGTH}
          onChange={(e) => onSubjectChange(e.target.value)}
          onFocus={() => { lastField.current = 'subject'; touched.current.subject = true; }}
          aria-invalid={!!subjectError}
        />
        {subjectError && (
          <p className="mt-1 flex items-center gap-1 text-xs text-red-600"><AlertCircle className="h-3.5 w-3.5" />{subjectError}</p>
        )}
      </div>

      {/* Body */}
      {usesTemplate ? (
        <div className="space-y-2 rounded-lg border border-slate-200 bg-slate-50/70 p-3 text-sm">
          <p className="flex items-start gap-2 text-slate-700">
            <LayoutTemplate className="mt-0.5 h-4 w-4 shrink-0 text-brand-500" />
            <span>
              This campaign sends the content of the template <b>{templateName}</b>
              {template && !templateWithoutBody && (
                <> ({[template.htmlBody?.trim() && 'HTML', template.plainBody?.trim() && 'plain text'].filter(Boolean).join(' and ')})</>
              )}
              . The template isn't changed. Use Preview to see it for a recipient.
            </span>
          </p>
          {templateWithoutBody && (
            <p className="flex items-center gap-1 text-xs text-amber-700">
              <AlertTriangle className="h-3.5 w-3.5" /> This template has no body. Write the email instead, or choose another template.
            </p>
          )}
          {templateIsDeleted && (
            <p className="flex items-center gap-1 text-xs text-amber-700">
              <AlertTriangle className="h-3.5 w-3.5" /> This template was deleted. Choose another template or write the email.
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            <Button type="button" size="sm" variant="secondary" onClick={startCustomizing} disabled={!template}>
              <PenLine className="h-4 w-4" /> Customize for this campaign
            </Button>
            {(templateWithoutBody || templateIsDeleted) && (
              <Button
                type="button"
                size="sm"
                variant="ghost"
                onClick={() => onContentChange({ ...content, source: 'own', body: '' })}
              >
                Write the email instead
              </Button>
            )}
          </div>
        </div>
      ) : (
        <div>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <label htmlFor="c-body" className="label">Email <span className="text-slate-400">(plain text)</span></label>
            {content.templateId != null && template && (
              <button type="button" className="mb-1.5 text-xs font-medium text-brand-600 hover:underline" onClick={backToTemplate}>
                Use the template's content
              </button>
            )}
          </div>
          <textarea
            id="c-body"
            ref={bodyRef}
            className={clsx(
              'input min-h-[200px] resize-y text-sm leading-relaxed',
              bodyError && 'border-red-400 focus:border-red-500 focus:ring-red-500/10',
            )}
            placeholder="Write your email here..."
            value={content.body}
            onChange={(e) => onContentChange({ ...content, source: 'own', body: e.target.value })}
            onFocus={() => { lastField.current = 'body'; touched.current.body = true; }}
            aria-invalid={!!bodyError}
            aria-describedby="c-body-help"
          />
          <div id="c-body-help" className="mt-1 flex flex-wrap items-start justify-between gap-2 text-xs">
            {bodyError ? (
              <p className="flex items-center gap-1 text-red-600"><AlertCircle className="h-3.5 w-3.5" />{bodyError}</p>
            ) : (
              <p className="text-slate-400">
                Required before sending; you can save a draft without it. Sent as plain text (HTML tags are sent as
                text). Line breaks are kept.
                {content.templateId != null && !content.body.trim() && " Left empty, the template's content is sent."}
              </p>
            )}
            <span className={clsx('tabular-nums', content.body.length > BODY_MAX_LENGTH ? 'text-red-600' : 'text-slate-400')}>
              {content.body.length.toLocaleString()} / {BODY_MAX_LENGTH.toLocaleString()}
            </span>
          </div>
        </div>
      )}

      {notice && (
        <p className="flex items-start gap-2 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600">
          <Info className="mt-0.5 h-4 w-4 shrink-0" /> {notice}
        </p>
      )}

      {/* Placeholder chips */}
      <div>
        <p className="mb-1 text-xs text-slate-500">
          Insert a placeholder at the cursor{usesTemplate ? ' of the subject' : ' (subject or email)'}:
        </p>
        <div className="flex flex-wrap gap-1">
          {chips.map((key) => (
            <button
              key={key}
              type="button"
              // Keep the cursor in the field: the chip is inserted where it is.
              onMouseDown={(e) => e.preventDefault()}
              onClick={() => insertChip(key)}
              className="rounded bg-accent-50 px-1.5 py-0.5 font-mono text-xs text-accent-700 transition hover:bg-accent-100"
              title={`Insert ${placeholderToken(key)}`}
            >
              {placeholderToken(key)}
            </button>
          ))}
        </div>
        <p className="mt-1 text-[11px] text-slate-400">
          Placeholders are case-sensitive. A recipient without a value gets blank text. Custom fields come from the
          columns of an uploaded file.
        </p>
      </div>

      <div>
        <Button type="button" size="sm" variant="secondary" onClick={() => setPreviewOpen((v) => !v)} aria-expanded={previewOpen}>
          {previewOpen ? <EyeOff className="h-4 w-4" /> : <Eye className="h-4 w-4" />}
          {previewOpen ? 'Hide preview' : 'Preview'}
        </Button>
      </div>
      {previewOpen && (
        <ContentPreview
          subject={subject}
          content={content}
          templateUnavailable={templateIsDeleted ? 'The template was deleted.' : null}
          recipients={previewRecipients}
        />
      )}
    </section>
  );
}

/** Debounces a JSON-serialisable value (typing in the editor doesn't render on every key). */
function useDebouncedJson<T>(value: T, delayMs: number): T {
  const key = JSON.stringify(value);
  const [debounced, setDebounced] = useState(key);
  useEffect(() => {
    const t = setTimeout(() => setDebounced(key), delayMs);
    return () => clearTimeout(t);
  }, [key, delayMs]);
  return JSON.parse(debounced) as T;
}

interface ContentPreviewProps {
  subject: string;
  content: ContentState;
  /** Why the template can't be rendered (for example deleted), or null. */
  templateUnavailable: string | null;
  recipients: CombinedRecipient[];
}

/**
 * What a recipient receives, rendered by the server with the same code as a real send:
 * the campaign's own body through POST /api/templates/render (plain text plus the HTML
 * part made from it), a template through POST /api/templates/{id}/render. Plain text is
 * shown in a <pre>, HTML only in a sandboxed iframe.
 */
export function ContentPreview({ subject, content, templateUnavailable, recipients }: ContentPreviewProps) {
  const [choice, setChoice] = useState('');
  const options = recipients.slice(0, 200);
  const entry = choice === 'sample' ? undefined : options.find((r) => r.email === choice) ?? options[0];
  const recipient = entry ? sampleFor(entry) : SAMPLE_RECIPIENT;

  const ownBody = content.source === 'own' && content.body.trim() ? content.body : null;
  const templateId = ownBody ? null : content.templateId;
  const request = useDebouncedJson({ subject: subject.trim(), ownBody, templateId, recipient }, 400);
  const blocked = !request.ownBody && (request.templateId == null || !!templateUnavailable);

  const query = useQuery({
    queryKey: ['campaign-content-preview', request],
    queryFn: () =>
      request.ownBody
        ? templatesApi.renderContent({
            subject: request.subject,
            htmlBody: null,
            plainBody: null,
            customBody: request.ownBody,
            recipient: request.recipient,
          })
        : templatesApi.render(request.templateId as number, { subject: request.subject, recipient: request.recipient }),
    enabled: !blocked,
    retry: false,
  });

  return (
    <div className="space-y-3 rounded-xl border border-slate-200 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="flex items-center gap-1.5 text-sm font-semibold text-slate-900">
          <FileText className="h-4 w-4 text-slate-400" /> Preview
        </p>
        <select
          className="input w-auto max-w-xs py-1.5 text-xs"
          value={entry ? entry.email : 'sample'}
          onChange={(e) => setChoice(e.target.value)}
          aria-label="Recipient to preview"
        >
          {options.map((e) => (
            <option key={e.email} value={e.email}>{e.email}{e.name ? ` (${e.name})` : ''}</option>
          ))}
          <option value="sample">Sample values</option>
        </select>
      </div>
      {blocked ? (
        <p className="text-sm text-slate-500">
          {templateUnavailable
            ? `${templateUnavailable} Write the email or choose another template to preview it.`
            : 'Nothing to preview yet: write the email or choose a template.'}
        </p>
      ) : query.isLoading ? (
        <p className="flex items-center gap-2 text-sm text-slate-500"><Loader2 className="h-4 w-4 animate-spin" /> Rendering preview…</p>
      ) : query.isError ? (
        <p className="text-sm text-red-600">
          {apiErrorStatus(query.error) === 404
            ? 'The template no longer exists. Choose another template or write the email.'
            : apiErrorMessage(query.error, 'Could not render the preview.')}
        </p>
      ) : query.data ? (
        <RenderedView
          email={query.data}
          to={recipient.email ?? null}
          own={!!request.ownBody}
          usingSample={!entry}
        />
      ) : null}
      <p className="text-[11px] text-slate-400">
        Rendered by the server with the same code as a real send. Nothing is saved and no email is sent.
      </p>
    </div>
  );
}

function RenderedView({ email, to, own, usingSample }: { email: RenderedEmail; to: string | null; own: boolean; usingSample: boolean }) {
  const hasHtml = email.htmlBody.trim().length > 0;
  const hasPlain = email.plainBody.trim().length > 0;
  // The campaign's own text is shown as typed (plain text) first; a template's HTML first.
  const [view, setView] = useState<'html' | 'plain'>(own ? 'plain' : 'html');
  const current = view === 'plain' ? (hasPlain ? 'plain' : 'html') : hasHtml ? 'html' : 'plain';

  return (
    <div className="space-y-2">
      {usingSample && <p className="text-xs text-slate-400">Showing sample values.</p>}
      <p className="text-sm">
        <span className="text-slate-500">To:</span> <span className="text-slate-800">{to ?? '(no address)'}</span>
      </p>
      <p className="text-sm">
        <span className="text-slate-500">Subject:</span>{' '}
        <span className="font-medium text-slate-900">{email.subject || '(empty)'}</span>
      </p>
      {hasHtml && hasPlain && (
        <div className="flex gap-1 text-xs font-medium">
          {(['plain', 'html'] as const).map((v) => (
            <button
              key={v}
              type="button"
              aria-pressed={current === v}
              onClick={() => setView(v)}
              className={clsx('rounded-md px-2.5 py-1', current === v ? 'bg-brand-50 text-brand-700' : 'text-slate-500 hover:bg-slate-100')}
            >
              {v === 'html' ? 'HTML part' : 'Plain-text part'}
            </button>
          ))}
        </div>
      )}
      {!hasHtml && !hasPlain ? (
        <p className="text-sm text-slate-500">(The body is empty.)</p>
      ) : current === 'html' ? (
        // sandbox="" blocks scripts, forms, popups and same-origin access in the rendered HTML.
        <iframe
          title="Rendered email (HTML part)"
          sandbox=""
          srcDoc={email.htmlBody}
          className="h-72 w-full rounded-lg border border-slate-200 bg-white"
        />
      ) : (
        <pre className="max-h-72 overflow-auto whitespace-pre-wrap rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs text-slate-700">
          {email.plainBody}
        </pre>
      )}
      {own && hasHtml && (
        <p className="text-[11px] text-slate-400">
          Your text is sent as plain text, with an HTML version made from it (values are escaped, line breaks kept).
        </p>
      )}
      {email.emptyVariables.length > 0 && (
        <p className="flex items-start gap-1.5 text-xs text-amber-700">
          <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>
            No value for this recipient:{' '}
            {email.emptyVariables.map((v) => <code key={v} className="mr-1">{`{{${v}}}`}</code>)}
            These appear as blank text.
          </span>
        </p>
      )}
    </div>
  );
}
