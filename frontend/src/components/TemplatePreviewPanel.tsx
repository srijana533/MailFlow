import { useEffect, useRef, useState } from 'react';
import { useMutation } from '@tanstack/react-query';
import { AlertTriangle, Info, Loader2, Plus, RefreshCw, Trash2 } from 'lucide-react';
import clsx from 'clsx';
import type { RenderRecipient, RenderedEmail } from '../api/templates';
import { Button } from './ui';
import { apiErrorMessage, isForbidden } from '../lib/errors';
import { noPermissionMessage } from '../lib/roles';
import {
  TEMPLATE_LIMITS,
  newSampleField,
  sampleFieldErrors,
  toRenderRecipient,
  type SampleData,
  type SampleField,
} from '../lib/templateSample';

interface TemplatePreviewPanelProps {
  sample: SampleData;
  onSampleChange: (sample: SampleData) => void;
  /** Renders the content on the server for one sample recipient. */
  render: (recipient: RenderRecipient) => Promise<RenderedEmail>;
  /** Identifies the content being previewed. When it changes, the preview shown is out of date. */
  contentKey: string;
  /** Placeholders the content uses, listed so the user knows which values to fill in. */
  placeholders: string[];
  /** Set when the content can't be previewed, for example because it has no body. */
  blockedReason?: string | null;
}

type TextKey = Exclude<keyof SampleData, 'fields'>;

const TEXT_INPUTS: { key: TextKey; label: string; fills: string }[] = [
  { key: 'firstName', label: 'First name', fills: '{{firstName}} {{FirstName}}' },
  { key: 'lastName', label: 'Last name', fills: '{{lastName}} {{LastName}}' },
  { key: 'name', label: 'Full name', fills: '{{name}} {{Name}}' },
  { key: 'email', label: 'Email', fills: '{{email}} {{Email}}' },
  { key: 'company', label: 'Company', fills: '{{company}} {{Company}}' },
];

function renderErrorMessage(e: unknown): string {
  if (isForbidden(e)) return noPermissionMessage('preview templates with sample data');
  return apiErrorMessage(e, 'Could not render the preview.');
}

/**
 * Sample-data editor plus a server-rendered preview. Placeholders are only ever
 * filled by the server (the same model as a real send), never in the browser.
 */
export function TemplatePreviewPanel({
  sample,
  onSampleChange,
  render,
  contentKey,
  placeholders,
  blockedReason,
}: TemplatePreviewPanelProps) {
  const fieldErrors = sampleFieldErrors(sample.fields);
  const sampleValid = Object.keys(fieldErrors).length === 0;
  const recipient = toRenderRecipient(sample);
  const inputKey = `${contentKey}\n${JSON.stringify(recipient)}`;
  const canRender = !blockedReason && sampleValid;

  // The last successful preview, with the inputs it was rendered from.
  const [shown, setShown] = useState<{ key: string; email: RenderedEmail; to: string | null } | null>(null);

  const mutation = useMutation({
    mutationFn: (vars: { key: string; recipient: RenderRecipient }) => render(vars.recipient),
    onSuccess: (email, vars) => setShown({ key: vars.key, email, to: vars.recipient.email ?? null }),
  });

  const run = () => {
    if (canRender && !mutation.isPending) mutation.mutate({ key: inputKey, recipient });
  };

  // Render once when the panel opens; after that the user renders again on demand.
  const autoRendered = useRef(false);
  useEffect(() => {
    if (autoRendered.current) return;
    autoRendered.current = true;
    run();
  });

  const stale = !!shown && shown.key !== inputKey;

  const setText = (key: TextKey, value: string) => onSampleChange({ ...sample, [key]: value });
  const setField = (id: number, patch: Partial<SampleField>) =>
    onSampleChange({ ...sample, fields: sample.fields.map((f) => (f.id === id ? { ...f, ...patch } : f)) });
  const addField = () => onSampleChange({ ...sample, fields: [...sample.fields, newSampleField()] });
  const removeField = (id: number) => onSampleChange({ ...sample, fields: sample.fields.filter((f) => f.id !== id) });

  return (
    <div className="space-y-4">
      <p className="flex items-start gap-2 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600">
        <Info className="mt-0.5 h-4 w-4 shrink-0" />
        The server fills the placeholders with these sample values, the same way it does for a real send.
        Nothing is saved and no email is sent.
      </p>

      <fieldset className="rounded-xl border border-slate-200 p-4">
        <legend className="px-1 text-sm font-semibold text-slate-900">Sample recipient</legend>
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          {TEXT_INPUTS.map((f) => (
            <div key={f.key}>
              <label htmlFor={`sample-${f.key}`} className="label">{f.label}</label>
              <input
                id={`sample-${f.key}`}
                className="input"
                value={sample[f.key]}
                maxLength={TEMPLATE_LIMITS.sampleText}
                onChange={(e) => setText(f.key, e.target.value)}
              />
              <p className="mt-1 font-mono text-[11px] text-slate-400">{f.fills}</p>
            </div>
          ))}
        </div>

        <div className="mt-4">
          <p className="text-sm font-medium text-slate-700">Custom fields</p>
          <p className="text-xs text-slate-500">
            Values for other placeholders, such as {'{{Team}}'}, {'{{role}}'} or {'{{course}}'}. Enter the name without braces.
          </p>
          {sample.fields.map((f) => (
            <div key={f.id} className="mt-2">
              <div className="flex items-center gap-2">
                <input
                  className={clsx('input w-40 font-mono text-xs', fieldErrors[f.id] && 'border-red-400')}
                  placeholder="Team"
                  aria-label="Placeholder name"
                  aria-invalid={!!fieldErrors[f.id]}
                  value={f.key}
                  maxLength={TEMPLATE_LIMITS.sampleKey}
                  onChange={(e) => setField(f.id, { key: e.target.value })}
                />
                <input
                  className="input flex-1"
                  placeholder="Platform"
                  aria-label={f.key.trim() ? `Value for ${f.key.trim()}` : 'Value'}
                  value={f.value}
                  maxLength={TEMPLATE_LIMITS.sampleValue}
                  onChange={(e) => setField(f.id, { value: e.target.value })}
                />
                <button
                  type="button"
                  className="btn-icon text-slate-400 hover:bg-red-50 hover:text-red-600"
                  aria-label={f.key.trim() ? `Remove ${f.key.trim()}` : 'Remove field'}
                  title="Remove"
                  onClick={() => removeField(f.id)}
                >
                  <Trash2 className="h-4 w-4" />
                </button>
              </div>
              {fieldErrors[f.id] && <p className="mt-1 text-xs text-red-600">{fieldErrors[f.id]}</p>}
            </div>
          ))}
          <Button
            type="button"
            variant="secondary"
            size="sm"
            className="mt-2"
            onClick={addField}
            disabled={sample.fields.length >= TEMPLATE_LIMITS.sampleFields}
          >
            <Plus className="h-4 w-4" /> Add field
          </Button>
        </div>
      </fieldset>

      {placeholders.length > 0 && (
        <div className="flex flex-wrap items-center gap-1 text-xs">
          <span className="text-slate-500">Placeholders used:</span>
          {placeholders.map((p) => (
            <code key={p} className="rounded bg-accent-50 px-1.5 py-0.5 text-accent-700">{`{{${p}}}`}</code>
          ))}
        </div>
      )}

      <div className="space-y-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <p className="text-sm font-semibold text-slate-900">Preview</p>
          <Button type="button" size="sm" variant="secondary" onClick={run} loading={mutation.isPending} disabled={!canRender}>
            {!mutation.isPending && <RefreshCw className="h-4 w-4" />}
            {shown ? 'Render again' : 'Render preview'}
          </Button>
        </div>

        {blockedReason && <p className="text-sm text-slate-500">{blockedReason}</p>}
        {!blockedReason && !sampleValid && (
          <p className="text-xs text-red-600">Fix the custom fields above to render the preview.</p>
        )}
        {mutation.isError && (
          <p role="alert" className="flex items-start gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            {renderErrorMessage(mutation.error)}
          </p>
        )}
        {mutation.isPending && !shown && (
          <p className="flex items-center gap-2 text-sm text-slate-500">
            <Loader2 className="h-4 w-4 animate-spin" /> Rendering…
          </p>
        )}
        {stale && !mutation.isPending && (
          <p className="flex items-start gap-2 text-xs text-amber-700">
            <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            The content or the sample values changed after this preview. Render it again to see the changes.
          </p>
        )}
        {shown && <RenderedEmailView email={shown.email} to={shown.to} />}
      </div>
    </div>
  );
}

function RenderedEmailView({ email, to }: { email: RenderedEmail; to: string | null }) {
  const hasHtml = email.htmlBody.trim().length > 0;
  const hasPlain = email.plainBody.trim().length > 0;
  const [view, setView] = useState<'html' | 'plain'>('html');
  const current = view === 'plain' ? (hasPlain ? 'plain' : 'html') : hasHtml ? 'html' : 'plain';

  return (
    <div className="space-y-2 rounded-xl border border-slate-200 p-4">
      <p className="text-sm">
        <span className="text-slate-500">To:</span>{' '}
        <span className="text-slate-800">{to ?? '(no email address in the sample)'}</span>
      </p>
      <p className="text-sm">
        <span className="text-slate-500">Subject:</span>{' '}
        <span className="font-medium text-slate-900">{email.subject || '(empty)'}</span>
      </p>

      {hasHtml && hasPlain && (
        <div className="flex gap-1 text-xs font-medium">
          {(['html', 'plain'] as const).map((v) => (
            <button
              key={v}
              type="button"
              aria-pressed={current === v}
              onClick={() => setView(v)}
              className={clsx(
                'rounded-md px-2.5 py-1',
                current === v ? 'bg-brand-50 text-brand-700' : 'text-slate-500 hover:bg-slate-100',
              )}
            >
              {v === 'html' ? 'HTML' : 'Plain text'}
            </button>
          ))}
        </div>
      )}

      {!hasHtml && !hasPlain ? (
        <p className="text-sm text-slate-500">(The body is empty.)</p>
      ) : current === 'html' ? (
        // sandbox="" blocks scripts, forms, popups and same-origin access in the rendered HTML.
        <iframe
          title="Rendered HTML body"
          sandbox=""
          srcDoc={email.htmlBody}
          className="h-80 w-full rounded-lg border border-slate-200 bg-white"
        />
      ) : (
        <pre className="max-h-80 overflow-auto whitespace-pre-wrap rounded-lg border border-slate-200 bg-slate-50 p-3 text-xs text-slate-700">
          {email.plainBody}
        </pre>
      )}

      {email.emptyVariables.length > 0 && (
        <p className="flex items-start gap-1.5 text-xs text-amber-700">
          <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>
            No value in the sample for{' '}
            {email.emptyVariables.map((v) => (
              <code key={v} className="mr-1">{`{{${v}}}`}</code>
            ))}
            These render as blank text, as they would for a recipient without these values.
          </span>
        </p>
      )}
    </div>
  );
}
