import { useState, FormEvent, useEffect } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';
import toast from 'react-hot-toast';
import { AlertCircle, ArrowLeft, ArrowRight, Users, Sparkles } from 'lucide-react';
import { apiClient } from '../api/client';
import { campaignsApi } from '../api/campaigns';
import { recipientsApi, type ExcelRowPreview } from '../api/recipients';
import type { EmailTemplate, PageResponse, Campaign } from '../types';
import { Modal, Button } from './ui';
import { RecipientExcelUpload } from './RecipientExcelUpload';
import { AiImproveModal } from './AiImproveModal';
import type { ImprovedEmail } from '../api/ai';

interface CampaignFormModalProps {
  open: boolean;
  onClose: () => void;
  /** Called after a campaign is successfully created. */
  onCreated: (campaign: Campaign) => void;
}

interface FormState {
  name: string;
  subject: string;
  description: string;
  templateId: string; // kept as string for the <select>, converted on submit
  senderName: string;
  senderEmail: string;
  replyTo: string;
}

const EMPTY: FormState = {
  name: '',
  subject: '',
  description: '',
  templateId: '',
  senderName: '',
  senderEmail: '',
  replyTo: '',
};

type Step = 'details' | 'recipients';

function isValidEmail(v: string): boolean {
  return /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(v);
}

/** Maps a previewed row into the bulk-create request shape. */
function toBulkRow(r: ExcelRowPreview) {
  const f = r.fields ?? {};
  // Standard fields are pulled out; everything else travels in `fields`.
  const standardKeys = new Set(['email', 'name', 'firstName', 'lastName', 'company']);
  const extra: Record<string, string> = {};
  for (const [k, v] of Object.entries(f)) {
    if (!standardKeys.has(k) && v) extra[k] = v;
  }
  return {
    email: (f.email ?? r.email ?? '').trim(),
    name: f.name ?? r.name ?? undefined,
    firstName: f.firstName ?? undefined,
    lastName: f.lastName ?? undefined,
    company: f.company ?? r.company ?? undefined,
    fields: extra,
  };
}

export function CampaignFormModal({ open, onClose, onCreated }: CampaignFormModalProps) {
  const [step, setStep] = useState<Step>('details');
  const [form, setForm] = useState<FormState>(EMPTY);
  const [errors, setErrors] = useState<Partial<Record<keyof FormState, string>>>({});
  const [validRows, setValidRows] = useState<ExcelRowPreview[]>([]);
  const [aiOpen, setAiOpen] = useState(false);
  // Body produced by AI (the campaign body lives in the chosen template; this
  // lets the user see/copy the improved body). Not persisted automatically.
  const [aiBody, setAiBody] = useState('');

  // Reset everything whenever the modal is (re)opened
  useEffect(() => {
    if (open) {
      setForm(EMPTY);
      setErrors({});
      setStep('details');
      setValidRows([]);
      setAiOpen(false);
      setAiBody('');
    }
  }, [open]);

  // Load templates for the dropdown (only when modal is open)
  const { data: templatesPage } = useQuery<PageResponse<EmailTemplate>>({
    queryKey: ['templates', 'campaign-form'],
    queryFn: () => apiClient.get('/api/templates', { params: { size: 100 } }).then((r) => r.data),
    enabled: open,
  });
  const templates = templatesPage?.content ?? [];

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((f) => ({ ...f, [key]: value }));
    if (errors[key]) setErrors((e) => ({ ...e, [key]: undefined }));
  };

  // When a template is chosen, prefill the subject if the user hasn't typed one
  const handleTemplateChange = (value: string) => {
    const tpl = templates.find((t) => String(t.id) === value);
    setForm((f) => ({
      ...f,
      templateId: value,
      subject: f.subject.trim() ? f.subject : tpl?.subject ?? f.subject,
    }));
    if (errors.templateId) setErrors((e) => ({ ...e, templateId: undefined }));
  };

  // Body context for AI: the selected template's body (plain or HTML), if any.
  const selectedTemplate = templates.find((t) => String(t.id) === form.templateId);
  const draftBody = aiBody
    || selectedTemplate?.plainBody
    || selectedTemplate?.htmlBody
    || form.description
    || '';

  // Apply the AI-improved draft: update the subject and keep the improved body
  // visible for the user to copy into their template. Nothing is sent.
  const handleAiAccept = (result: ImprovedEmail) => {
    set('subject', result.subject);
    setAiBody(result.body);
  };

  const createMutation = useMutation({
    mutationFn: async (): Promise<Campaign> => {
      // Step 1: persist recipients (create new / reuse existing) → IDs
      let recipientIds: number[] = [];
      if (validRows.length > 0) {
        const bulk = await recipientsApi.bulkCreate({ rows: validRows.map(toBulkRow) });
        recipientIds = bulk.recipientIds;
      }

      // Step 2: build the campaign payload, attaching recipient IDs
      const payload: Record<string, unknown> = {
        name: form.name.trim(),
        subject: form.subject.trim(),
      };
      if (form.description.trim()) payload.description = form.description.trim();
      if (form.templateId) payload.templateId = Number(form.templateId);
      if (form.senderName.trim()) payload.senderName = form.senderName.trim();
      if (form.senderEmail.trim()) payload.senderEmail = form.senderEmail.trim();
      if (form.replyTo.trim()) payload.replyTo = form.replyTo.trim();
      if (recipientIds.length > 0) payload.recipientIds = recipientIds;

      return campaignsApi.create(payload) as Promise<Campaign>;
    },
    onSuccess: (campaign) => {
      const n = validRows.length;
      toast.success(
        n > 0
          ? `Campaign created with ${n} recipient${n === 1 ? '' : 's'}`
          : 'Campaign created'
      );
      onCreated(campaign);
      onClose();
    },
    onError: (e: unknown) => {
      const msg =
        (e as { response?: { data?: { message?: string } } }).response?.data?.message ??
        'Failed to create campaign';
      toast.error(msg);
    },
  });

  const validateDetails = (): boolean => {
    const next: Partial<Record<keyof FormState, string>> = {};
    if (!form.name.trim()) next.name = 'Campaign name is required';
    if (!form.subject.trim()) next.subject = 'Subject is required';
    if (form.senderEmail && !isValidEmail(form.senderEmail)) next.senderEmail = 'Enter a valid email';
    if (form.replyTo && !isValidEmail(form.replyTo)) next.replyTo = 'Enter a valid email';
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const goToRecipients = (e: FormEvent) => {
    e.preventDefault();
    if (validateDetails()) setStep('recipients');
  };

  const footer =
    step === 'details' ? (
      <>
        <Button variant="secondary" type="button" onClick={onClose} disabled={createMutation.isPending}>
          Cancel
        </Button>
        <Button type="submit" form="campaign-form">
          Next: Recipients <ArrowRight className="h-4 w-4" />
        </Button>
      </>
    ) : (
      <>
        <Button
          variant="secondary"
          type="button"
          onClick={() => setStep('details')}
          disabled={createMutation.isPending}
        >
          <ArrowLeft className="h-4 w-4" /> Back
        </Button>
        <Button
          type="button"
          loading={createMutation.isPending}
          onClick={() => createMutation.mutate()}
        >
          {createMutation.isPending
            ? 'Creating…'
            : validRows.length > 0
            ? `Create with ${validRows.length} recipient${validRows.length === 1 ? '' : 's'}`
            : 'Create without recipients'}
        </Button>
      </>
    );

  return (
    <>
    <Modal
      open={open}
      onClose={onClose}
      title="New Campaign"
      description={
        step === 'details'
          ? 'Step 1 of 2 — campaign details.'
          : 'Step 2 of 2 — upload recipients from an Excel or CSV file.'
      }
      footer={footer}
    >
      {/* Step indicator */}
      <div className="mb-5 flex items-center gap-2 text-xs font-medium">
        <span className={step === 'details' ? 'text-brand-600' : 'text-slate-400'}>
          1. Details
        </span>
        <span className="h-px w-6 bg-slate-200" />
        <span className={step === 'recipients' ? 'text-brand-600' : 'text-slate-400'}>
          2. Recipients
        </span>
      </div>

      {step === 'details' ? (
        <form id="campaign-form" onSubmit={goToRecipients} noValidate className="space-y-4">
          {/* Name */}
          <div>
            <label htmlFor="c-name" className="label">Campaign name <span className="text-red-500">*</span></label>
            <input
              id="c-name"
              className={`input ${errors.name ? 'border-red-400 focus:border-red-500 focus:ring-red-500/10' : ''}`}
              placeholder="e.g. October Product Launch"
              value={form.name}
              onChange={(e) => set('name', e.target.value)}
              aria-invalid={!!errors.name}
            />
            {errors.name && <p className="mt-1 flex items-center gap-1 text-xs text-red-600"><AlertCircle className="h-3.5 w-3.5" />{errors.name}</p>}
          </div>

          {/* Template */}
          <div>
            <label htmlFor="c-template" className="label">Template</label>
            <select
              id="c-template"
              className="input"
              value={form.templateId}
              onChange={(e) => handleTemplateChange(e.target.value)}
            >
              <option value="">No template (plain subject only)</option>
              {templates.map((t) => (
                <option key={t.id} value={t.id}>{t.name}</option>
              ))}
            </select>
            <p className="mt-1 text-xs text-slate-400">
              Choosing a template prefills the subject line. Use {'{{Name}}'}, {'{{Company}}'},
              {' {{Role}}'} etc. for personalization.
            </p>
          </div>

          {/* Subject */}
          <div>
            <div className="flex items-center justify-between">
              <label htmlFor="c-subject" className="label">Subject <span className="text-red-500">*</span></label>
              <button
                type="button"
                onClick={() => setAiOpen(true)}
                className="mb-1.5 inline-flex items-center gap-1 text-xs font-medium text-brand-600 hover:text-brand-700 hover:underline"
              >
                <Sparkles className="h-3.5 w-3.5" /> Improve with AI
              </button>
            </div>
            <input
              id="c-subject"
              className={`input ${errors.subject ? 'border-red-400 focus:border-red-500 focus:ring-red-500/10' : ''}`}
              placeholder="e.g. Hello {{Name}}, big news inside"
              value={form.subject}
              onChange={(e) => set('subject', e.target.value)}
              aria-invalid={!!errors.subject}
            />
            {errors.subject && <p className="mt-1 flex items-center gap-1 text-xs text-red-600"><AlertCircle className="h-3.5 w-3.5" />{errors.subject}</p>}
          </div>

          {/* AI-improved body preview (for the user to copy into their template) */}
          {aiBody && (
            <div className="rounded-lg border border-emerald-200 bg-emerald-50/60 p-3">
              <div className="mb-1 flex items-center gap-1.5 text-xs font-semibold text-emerald-700">
                <Sparkles className="h-3.5 w-3.5" /> AI-improved body (copy into your template)
              </div>
              <textarea
                readOnly
                className="input min-h-[120px] resize-y bg-white font-mono text-xs"
                value={aiBody}
                onFocus={(e) => e.target.select()}
              />
              <p className="mt-1 text-xs text-slate-500">
                The campaign body comes from the selected template. Paste this improved copy into the
                template to use it. Merge variables like {'{{Name}}'} are preserved.
              </p>
            </div>
          )}

          {/* Description */}
          <div>
            <label htmlFor="c-desc" className="label">Description</label>
            <textarea
              id="c-desc"
              className="input min-h-[72px] resize-y"
              placeholder="Internal note describing this campaign (optional)"
              value={form.description}
              onChange={(e) => set('description', e.target.value)}
            />
          </div>

          {/* Sender row */}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor="c-sender-name" className="label">Sender name</label>
              <input
                id="c-sender-name"
                className="input"
                placeholder="MailFlow Team"
                value={form.senderName}
                onChange={(e) => set('senderName', e.target.value)}
              />
            </div>
            <div>
              <label htmlFor="c-sender-email" className="label">Sender email</label>
              <input
                id="c-sender-email"
                type="email"
                className={`input ${errors.senderEmail ? 'border-red-400 focus:border-red-500 focus:ring-red-500/10' : ''}`}
                placeholder="noreply@yourdomain.com"
                value={form.senderEmail}
                onChange={(e) => set('senderEmail', e.target.value)}
                aria-invalid={!!errors.senderEmail}
              />
              {errors.senderEmail && <p className="mt-1 text-xs text-red-600">{errors.senderEmail}</p>}
            </div>
          </div>

          {/* Reply-to */}
          <div>
            <label htmlFor="c-replyto" className="label">Reply-to</label>
            <input
              id="c-replyto"
              type="email"
              className={`input ${errors.replyTo ? 'border-red-400 focus:border-red-500 focus:ring-red-500/10' : ''}`}
              placeholder="support@yourdomain.com (optional)"
              value={form.replyTo}
              onChange={(e) => set('replyTo', e.target.value)}
              aria-invalid={!!errors.replyTo}
            />
            {errors.replyTo && <p className="mt-1 text-xs text-red-600">{errors.replyTo}</p>}
          </div>
        </form>
      ) : (
        <div className="space-y-4">
          <RecipientExcelUpload onValidRowsChange={setValidRows} />
          <p className="flex items-start gap-2 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
            <Users className="mt-0.5 h-4 w-4 shrink-0 text-slate-400" />
            <span>
              The campaign is created as a <span className="font-medium text-slate-700">Draft</span>.
              Recipients are attached now; emails are only sent when you launch the campaign from its
              detail page. You can also create the campaign without recipients and add them later.
            </span>
          </p>
        </div>
      )}
    </Modal>

    <AiImproveModal
      open={aiOpen}
      onClose={() => setAiOpen(false)}
      initialSubject={form.subject}
      initialBody={draftBody}
      onAccept={handleAiAccept}
    />
    </>
  );
}
