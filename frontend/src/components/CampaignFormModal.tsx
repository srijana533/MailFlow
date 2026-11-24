import { useState, FormEvent, useEffect, useMemo, useRef } from 'react';
import { useQuery, useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import toast from 'react-hot-toast';
import {
  AlertCircle, ArrowLeft, ArrowRight, CheckCircle2, XCircle, Loader2, UserPlus, AlertTriangle, Info, Send,
} from 'lucide-react';
import { campaignsApi } from '../api/campaigns';
import { recipientsApi, type BulkRecipientRow, type RecipientLookupResponse } from '../api/recipients';
import { templatesApi } from '../api/templates';
import type { Campaign, Recipient, TargetAddress } from '../types';
import { Modal, Button } from './ui';
import { CampaignRecipientSelector } from './CampaignRecipientSelector';
import { CampaignContentSection, ContentPreview } from './CampaignContentSection';
import { AiImproveModal } from './AiImproveModal';
import type { ImprovedEmail } from '../api/ai';
import {
  CAMPAIGN_ONLY_LIMIT,
  combineRecipients,
  EMPTY_SELECTION,
  extractPlaceholders,
  isValidEmail,
  normalizeEmail,
  removeAddress,
  skipReason,
  toTargetAddress,
  trimEmail,
  type CombinedRecipient,
  type RecipientSelection,
} from '../lib/recipientSelection';
import {
  bodyError as validateBody,
  contentReady,
  customBodyToSave,
  customFieldKeys,
  htmlToText,
  initialContent,
  subjectError as validateSubject,
  type ContentState,
} from '../lib/campaignContent';
import { manualRowStatuses, UNUSED_STATES } from '../lib/manualTable';
import { canSaveAndSend, draftLaunchChecks, type LaunchCheckKey } from '../lib/campaignChecks';
import { apiErrorMessage, apiErrorStatus, isForbidden } from '../lib/errors';
import { canManage, noPermissionMessage } from '../lib/roles';
import { useAuthStore } from '../store/authStore';

interface CampaignFormModalProps {
  open: boolean;
  onClose: () => void;
  /** Called after the campaign is created or updated (and again after it is launched). */
  onSaved: (campaign: Campaign) => void;
  /** When set, the modal edits this DRAFT campaign instead of creating a new one. */
  campaign?: Campaign | null;
}

interface FormState {
  name: string;
  subject: string;
  description: string;
  senderName: string;
  senderEmail: string;
  replyTo: string;
}

const EMPTY: FormState = {
  name: '',
  subject: '',
  description: '',
  senderName: '',
  senderEmail: '',
  replyTo: '',
};

type FieldErrors = Partial<Record<keyof FormState | 'body', string>>;

type Step = 'details' | 'recipients' | 'review';
const STEPS: { key: Step; label: string }[] = [
  { key: 'details', label: 'Details' },
  { key: 'recipients', label: 'Recipients & content' },
  { key: 'review', label: 'Review & send' },
];

type EditLoad =
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; missing: number };

type ReviewState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'error'; message: string }
  | { status: 'ready'; contacts: Map<string, Recipient>; deleted: Set<string>; unsubscribed: Set<string> };

/** The draft was saved, but launching it failed: the saved draft and the launch error. */
class LaunchError extends Error {
  constructor(readonly saved: Campaign, readonly launchError: unknown) {
    super('launch failed');
  }
}

function toBulkRow(e: CombinedRecipient): BulkRecipientRow {
  return {
    email: e.email,
    name: e.name ?? null,
    firstName: e.firstName ?? null,
    lastName: e.lastName ?? null,
    company: e.company ?? null,
    fields: e.fields ?? {},
  };
}

function plural(n: number, one: string, many = `${one}s`) {
  return `${n.toLocaleString()} ${n === 1 ? one : many}`;
}

/** The bulk endpoint accepts at most this many rows per request. */
const BULK_CHUNK = 10_000;

/** Creates (or reuses) contacts for the rows, in chunks, and returns their IDs. */
async function bulkCreateAll(rows: BulkRecipientRow[], source: 'UPLOAD' | 'MANUAL'): Promise<number[]> {
  const ids: number[] = [];
  for (let i = 0; i < rows.length; i += BULK_CHUNK) {
    const res = await recipientsApi.bulkCreate({ rows: rows.slice(i, i + BULK_CHUNK), source });
    ids.push(...res.recipientIds);
  }
  return ids;
}

export function CampaignFormModal({ open, onClose, onSaved, campaign }: CampaignFormModalProps) {
  const isEdit = !!campaign;
  const navigate = useNavigate();
  const mayManage = useAuthStore((s) => canManage(s.roles));
  const [step, setStep] = useState<Step>('details');
  const [form, setForm] = useState<FormState>(EMPTY);
  const [content, setContent] = useState<ContentState>(() => initialContent(null, null));
  const [errors, setErrors] = useState<FieldErrors>({});
  const [selection, setSelection] = useState<RecipientSelection>(EMPTY_SELECTION);
  const [editLoad, setEditLoad] = useState<EditLoad>({ status: 'ready', missing: 0 });
  const [review, setReview] = useState<ReviewState>({ status: 'idle' });
  const [reviewAttempt, setReviewAttempt] = useState(0);
  // Opt-in, unchecked by default: new addresses are campaign-only unless the user
  // explicitly chooses to save them as contacts.
  const [saveAsContacts, setSaveAsContacts] = useState(false);
  const [aiOpen, setAiOpen] = useState(false);
  // "Save and send now": the confirmation view, and the server's reason when launching failed.
  const [confirmSend, setConfirmSend] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  // The campaign this form saves to once it exists. A new campaign gets an ID at its first
  // save; a retry after a failed launch (or "Save draft" afterwards) updates that same
  // campaign instead of creating a second one.
  const [savedId, setSavedId] = useState<number | null>(null);

  const initializedFor = useRef<string | null>(null);
  const loadToken = useRef(0);

  const loadDraftRecipients = (c: Campaign) => {
    const ids = c.recipientIds ?? [];
    const token = ++loadToken.current;
    // A campaign from a list response carries neither its campaign-only addresses (only
    // their count) nor its own body: load the full campaign so saving the draft never
    // drops any of them.
    const needFull = c.targetAddresses === undefined;
    if (ids.length === 0 && !needFull) {
      setSelection({ ...EMPTY_SELECTION, draft: c.targetAddresses ?? [] });
      setEditLoad({ status: 'ready', missing: 0 });
      return;
    }
    setEditLoad({ status: 'loading' });
    Promise.all([
      ids.length > 0
        ? recipientsApi.lookup({ ids })
        : Promise.resolve<RecipientLookupResponse>({ recipients: [], deletedEmails: [], unsubscribedEmails: [] }),
      needFull ? campaignsApi.getById(c.id) : Promise.resolve<Campaign | null>(null),
    ])
      .then(([res, full]) => {
        if (token !== loadToken.current) return;
        let draft: TargetAddress[] = c.targetAddresses ?? [];
        if (full) {
          if (!full.targetAddresses) throw new Error('The campaign-only addresses were not returned.');
          draft = full.targetAddresses;
          setContent(initialContent(full.customBody, full.templateId ?? null));
        }
        setSelection({
          saved: Object.fromEntries(res.recipients.map((r) => [r.id, r])) as Record<number, Recipient>,
          upload: null,
          manual: [],
          draft,
        });
        setEditLoad({ status: 'ready', missing: ids.length - res.recipients.length });
      })
      .catch((e) => {
        if (token !== loadToken.current) return;
        setEditLoad({ status: 'error', message: apiErrorMessage(e, "Couldn't load this draft.") });
      });
  };

  // Initialise once per opening (not on every refetch of the campaign prop).
  useEffect(() => {
    if (!open) {
      initializedFor.current = null;
      loadToken.current++;
      return;
    }
    const key = campaign ? `edit-${campaign.id}` : 'create';
    if (initializedFor.current === key) return;
    initializedFor.current = key;

    setStep('details');
    setErrors({});
    setReview({ status: 'idle' });
    setSaveAsContacts(false);
    setAiOpen(false);
    setConfirmSend(false);
    setSendError(null);
    setSavedId(campaign ? campaign.id : null);
    setSelection(EMPTY_SELECTION);
    if (campaign) {
      setForm({
        name: campaign.name ?? '',
        subject: campaign.subject ?? '',
        description: campaign.description ?? '',
        senderName: campaign.senderName ?? '',
        senderEmail: campaign.senderEmail ?? '',
        replyTo: campaign.replyTo ?? '',
      });
      setContent(initialContent(campaign.customBody, campaign.templateId ?? null));
      loadDraftRecipients(campaign);
    } else {
      setForm(EMPTY);
      setContent(initialContent(null, null));
      setEditLoad({ status: 'ready', missing: 0 });
    }
    // loadDraftRecipients only uses setters and refs.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, campaign]);

  // ── Templates ───────────────────────────────────────────────────────────────
  const templatesQuery = useQuery({
    queryKey: ['templates', 'campaign-form'],
    queryFn: () => templatesApi.list({ size: 200 }),
    enabled: open,
  });
  const templates = useMemo(() => templatesQuery.data?.content ?? [], [templatesQuery.data]);

  // The draft's current template may be missing from the active list (deleted).
  const editTemplateId = isEdit ? campaign?.templateId : undefined;
  const editTemplateListed = !!editTemplateId && templates.some((t) => t.id === editTemplateId);
  const editTemplateQuery = useQuery({
    queryKey: ['template', editTemplateId],
    queryFn: () => templatesApi.getById(editTemplateId as number),
    enabled: open && templatesQuery.isSuccess && !!editTemplateId && !editTemplateListed,
    retry: false,
  });
  // Only a 404 means deleted; another error (network, 500) says nothing about the template.
  const templateDeleted =
    !!editTemplateId && !editTemplateListed && editTemplateQuery.isError &&
    apiErrorStatus(editTemplateQuery.error) === 404;
  // The draft's template while it is loading or couldn't be loaded: keep it selectable
  // under its stored name so the select shows what the draft really uses.
  const editTemplateUnresolved =
    !!editTemplateId && !editTemplateListed && !editTemplateQuery.data && !templateDeleted;
  const allTemplates = useMemo(
    () =>
      editTemplateQuery.data && !templates.some((t) => t.id === editTemplateQuery.data.id)
        ? [...templates, editTemplateQuery.data]
        : templates,
    [templates, editTemplateQuery.data],
  );

  const selectedTemplate = allTemplates.find((t) => t.id === content.templateId);
  const chosenTemplateDeleted = templateDeleted && content.templateId === editTemplateId;
  const templateWithoutBody =
    !!selectedTemplate && !selectedTemplate.htmlBody?.trim() && !selectedTemplate.plainBody?.trim();
  const templateIdToSend =
    content.templateId != null && !chosenTemplateDeleted ? content.templateId : null;
  // A template that isn't loaded (yet) counts as no content until it is.
  const hasContent = contentReady(content, chosenTemplateDeleted ? { deleted: true } : selectedTemplate ?? null);
  const ownBody = content.source === 'own' && content.body.trim() !== '';
  const chosenTemplateLoading = editTemplateUnresolved && content.templateId === editTemplateId;

  // Placeholders the email uses (for the upload's column coverage).
  const contentPlaceholders = useMemo(
    () =>
      Array.from(
        new Set([
          ...extractPlaceholders(form.subject),
          ...(content.source === 'own'
            ? extractPlaceholders(content.body)
            : [...extractPlaceholders(selectedTemplate?.htmlBody), ...extractPlaceholders(selectedTemplate?.plainBody)]),
        ]),
      ),
    [form.subject, content, selectedTemplate],
  );

  const set = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm((f) => ({ ...f, [key]: value }));
    if (errors[key]) setErrors((e) => ({ ...e, [key]: undefined }));
  };

  const changeContent = (next: ContentState) => {
    setContent(next);
    if (errors.body) setErrors((e) => ({ ...e, body: undefined }));
  };

  // Body context for AI: the campaign's own text, or the selected template's text.
  const aiBody =
    content.source === 'own'
      ? content.body
      : selectedTemplate?.plainBody?.trim()
      ? selectedTemplate.plainBody
      : htmlToText(selectedTemplate?.htmlBody ?? '');

  const handleAiAccept = (result: ImprovedEmail) => {
    set('subject', result.subject);
    // The improved body becomes the campaign's own text (plain text; the template isn't changed).
    if (result.body.trim()) changeContent({ ...content, source: 'own', body: result.body });
  };

  // ── Recipients: combined list + authoritative check on the Review step ───────
  const combined = useMemo(() => combineRecipients(selection), [selection]);
  // Addresses without a known saved-contact record (file rows, typed addresses and
  // campaign-only addresses stored with the draft): one of them may be a contact by now.
  const lookupEmails = useMemo(
    () => combined.list.filter((e) => !e.contact).map((e) => e.email),
    [combined],
  );
  const lookupKey = lookupEmails.join(',');
  // Table rows that won't be used (no or invalid email, repeated, deleted contact).
  const unusedTableRows = useMemo(
    () => manualRowStatuses(selection.manual).filter((s) => UNUSED_STATES.has(s.state)).length,
    [selection.manual],
  );

  useEffect(() => {
    if (!open || step !== 'review') return;
    if (lookupEmails.length === 0) {
      setReview({ status: 'ready', contacts: new Map(), deleted: new Set(), unsubscribed: new Set() });
      return;
    }
    let cancelled = false;
    setReview({ status: 'loading' });
    recipientsApi
      .lookup({ emails: lookupEmails })
      .then((res) => {
        if (cancelled) return;
        setReview({
          status: 'ready',
          contacts: new Map(res.recipients.map((r) => [normalizeEmail(r.email), r])),
          deleted: new Set(res.deletedEmails.map(normalizeEmail)),
          unsubscribed: new Set((res.unsubscribedEmails ?? []).map(normalizeEmail)),
        });
      })
      .catch((e) => {
        if (!cancelled) setReview({ status: 'error', message: apiErrorMessage(e, 'Could not check the recipients.') });
      });
    return () => {
      cancelled = true;
    };
    // lookupKey captures the content of lookupEmails.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, step, lookupKey, reviewAttempt]);

  const resolved = useMemo(() => {
    if (review.status !== 'ready') return null;
    const final: CombinedRecipient[] = [];
    const blocked: string[] = [];
    // Not saved contacts and on the unsubscribe list: never emailed, so not included.
    const unsubscribed: string[] = [];
    for (const e of combined.list) {
      const contact = e.contact ?? review.contacts.get(e.email);
      if (contact) {
        final.push({
          ...e,
          contact,
          recipientId: contact.id,
          status: contact.status,
          name: contact.name ?? undefined,
          firstName: contact.firstName ?? undefined,
          lastName: contact.lastName ?? undefined,
          company: contact.company ?? undefined,
        });
      } else if (review.deleted.has(e.email)) {
        blocked.push(e.email);
      } else if (review.unsubscribed.has(e.email)) {
        unsubscribed.push(e.email);
      } else {
        final.push({ ...e, contact: undefined, recipientId: undefined, status: undefined });
      }
    }
    // Addresses that are not saved contacts: campaign-only unless the user opts in
    // to saving them as contacts.
    const newAddresses = final.filter((e) => !e.contact);
    // Saved contacts that are skipped when sending, with the reason (never "duplicate":
    // a contact can be in any number of campaigns).
    const skipped: { email: string; reason: string }[] = [];
    for (const e of final) {
      const reason = e.contact ? skipReason(e.contact.status, review.unsubscribed.has(e.email)) : null;
      if (reason) skipped.push({ email: e.email, reason });
    }
    return {
      final,
      blocked,
      unsubscribed,
      newAddresses,
      newFromFile: newAddresses.filter((e) => e.origin === 'upload').length,
      newTyped: newAddresses.filter((e) => e.origin === 'manual').length,
      newStored: newAddresses.filter((e) => e.origin === 'draft').length,
      skipped,
      sendable: final.length - skipped.length,
    };
  }, [combined, review]);

  // The opt-in applies to a specific set of new addresses; changing the set resets it.
  const newAddressesKey = resolved?.newAddresses.map((e) => e.email).join(',') ?? '';
  useEffect(() => setSaveAsContacts(false), [newAddressesKey]);

  const groupCount = campaign?.recipientGroupIds?.length ?? 0;

  // ── Save (and launch) ─────────────────────────────────────────────────────────
  /** Creates the campaign, or updates it once it exists (this form's draft or the edited one). */
  const persist = async (): Promise<Campaign> => {
    if (!resolved) throw new Error('Recipients have not been checked yet');
    const contactIds = resolved.final.filter((e) => e.contact).map((e) => e.contact!.id);

    // By default new addresses are campaign-only: stored with this campaign as
    // targetAddresses, never as contacts. Only an explicit opt-in creates contacts
    // (existing contacts are reused), which are then targeted by ID instead.
    let targetAddresses: TargetAddress[] = resolved.newAddresses.map(toTargetAddress);
    const createdIds: number[] = [];
    if (saveAsContacts && resolved.newAddresses.length > 0) {
      const fileRows = resolved.newAddresses.filter((e) => e.origin === 'upload').map(toBulkRow);
      const otherRows = resolved.newAddresses.filter((e) => e.origin !== 'upload').map(toBulkRow);
      createdIds.push(...(await bulkCreateAll(fileRows, 'UPLOAD')));
      createdIds.push(...(await bulkCreateAll(otherRows, 'MANUAL')));
      targetAddresses = [];
    }

    const recipientIds = Array.from(new Set([...contactIds, ...createdIds]));

    // Always the full payload. PUT replaces every scalar field (an omitted one is
    // cleared), so fields this form doesn't edit are passed through from the draft.
    const payload = {
      name: form.name.trim(),
      subject: form.subject.trim(),
      description: form.description.trim() || null,
      // null keeps the draft's current template (for example a deleted one).
      templateId: templateIdToSend,
      // The campaign's own body; "" clears a stored one, so the template's body is sent.
      customBody: customBodyToSave(content),
      senderName: form.senderName.trim() || null,
      // Same trim as the validation (isValidEmail), so an address pasted with an
      // invisible character is sent without it.
      senderEmail: trimEmail(form.senderEmail) || null,
      replyTo: trimEmail(form.replyTo) || null,
      // Not editable here: keep any schedule set through the API.
      scheduledAt: campaign?.scheduledAt ?? null,
      recipientIds,
      // Always the full list: an empty list removes the stored campaign-only addresses.
      targetAddresses,
      // Not editable here: pass the groups set through the API back unchanged (null keeps them).
      recipientGroupIds: campaign?.recipientGroupIds ?? null,
    };
    const saved = savedId != null ? await campaignsApi.update(savedId, payload) : await campaignsApi.create(payload);
    setSavedId(saved.id);
    return saved;
  };

  const savedToast = (saved: Campaign, updated: boolean) => {
    const campaignOnly = saved.targetAddressCount ?? saved.targetAddresses?.length ?? 0;
    const n = (saved.recipientIds?.length ?? 0) + campaignOnly;
    toast.success(
      `${updated ? 'Draft updated' : 'Draft saved'} with ${plural(n, 'recipient')}` +
        (campaignOnly > 0 ? ` (${campaignOnly.toLocaleString()} campaign-only)` : ''),
      { id: 'campaign-save' },
    );
  };

  // Set synchronously when a save or a send starts: two clicks in the same moment (before
  // the buttons re-render as disabled) must never create two campaigns.
  const inFlight = useRef(false);
  const once = <T,>(run: () => Promise<T>) => async (): Promise<T> => {
    if (inFlight.current) throw new Error('A save is already in progress.');
    inFlight.current = true;
    try {
      return await run();
    } finally {
      inFlight.current = false;
    }
  };

  const saveMutation = useMutation({
    mutationFn: once<{ saved: Campaign; updated: boolean }>(async () => ({
      saved: await persist(),
      updated: savedId != null,
    })),
    onSuccess: ({ saved, updated }) => {
      savedToast(saved, updated);
      onSaved(saved);
      onClose();
    },
    onError: (e: unknown) => {
      toast.error(apiErrorMessage(e, 'Could not save the campaign'), { id: 'campaign-save' });
    },
  });

  const sendMutation = useMutation({
    // Create or update the draft first, then launch it. When the launch fails, the saved
    // draft stays and savedId points at it, so "Send now" again launches the same campaign.
    mutationFn: once<Campaign>(async () => {
      const saved = await persist();
      onSaved(saved);
      try {
        return await campaignsApi.launch(saved.id);
      } catch (e) {
        throw new LaunchError(saved, e);
      }
    }),
    onSuccess: (launched) => {
      setSendError(null);
      // "Sent" means handed to the mail server; delivery to inboxes isn't tracked.
      toast.success('Campaign launched: the emails are being sent.', { id: `campaign-send-${launched.id}` });
      onSaved(launched);
      onClose();
      navigate(`/campaigns/${launched.id}`);
    },
    onError: (e: unknown) => {
      if (e instanceof LaunchError) {
        const reason = isForbidden(e.launchError)
          ? noPermissionMessage('launch campaigns')
          : apiErrorMessage(e.launchError, 'The campaign could not be launched.');
        setSendError(`${reason} The campaign was saved as a draft; sending it again launches the same campaign.`);
        toast.error(reason, { id: `campaign-send-${e.saved.id}` });
      } else {
        const reason = apiErrorMessage(e, 'Could not save the campaign, so nothing was sent.');
        setSendError(reason);
        toast.error(reason, { id: 'campaign-send' });
      }
    },
  });

  const saving = saveMutation.isPending;
  const sending = sendMutation.isPending;
  const busy = saving || sending;
  const safeClose = () => {
    if (!busy) onClose();
  };

  // ── Navigation ─────────────────────────────────────────────────────────────────
  const validateDetails = (): boolean => {
    const next: FieldErrors = {};
    if (!form.name.trim()) next.name = 'Campaign name is required';
    else if (form.name.trim().length > 255) next.name = 'Use at most 255 characters';
    if (form.senderEmail.trim() && !isValidEmail(form.senderEmail)) next.senderEmail = 'Enter a valid email';
    if (form.replyTo.trim() && !isValidEmail(form.replyTo)) next.replyTo = 'Enter a valid email';
    setErrors(next);
    return Object.keys(next).length === 0;
  };

  const subjectProblem = validateSubject(form.subject);
  const bodyProblem = content.source === 'own' ? validateBody(content.body) : null;

  const validateContent = (): boolean => {
    setErrors((e) => ({ ...e, subject: subjectProblem ?? undefined, body: bodyProblem ?? undefined }));
    return !subjectProblem && !bodyProblem;
  };

  const goToRecipients = (e?: FormEvent) => {
    e?.preventDefault();
    if (validateDetails()) setStep('recipients');
  };

  const goToReview = () => {
    if (validateContent()) {
      setConfirmSend(false);
      setSendError(null);
      setStep('review');
    } else {
      toast.error('Check the Email Content section: the subject or the email needs a fix.', { id: 'campaign-content' });
    }
  };

  const campaignOnlyOverLimit =
    !!resolved && !saveAsContacts && resolved.newAddresses.length > CAMPAIGN_ONLY_LIMIT;

  const saveBlocked =
    busy ||
    editLoad.status !== 'ready' ||
    !resolved ||
    campaignOnlyOverLimit ||
    !!subjectProblem ||
    !!bodyProblem;

  const launchInput = {
    hasContent,
    subjectOk: !subjectProblem,
    senderEmailOk: isValidEmail(form.senderEmail),
    sendableRecipients: resolved?.sendable ?? 0,
    groupCount,
  };
  const checkResults = draftLaunchChecks(launchInput);
  const readyToLaunch = checkResults.every((c) => c.ok);
  const sendAllowed = !saveBlocked && canSaveAndSend({ ...launchInput, canManage: mayManage });

  const contentHint = chosenTemplateDeleted && !ownBody
    ? 'The assigned template was deleted. Write the email or choose another template in Email Content (Recipients step).'
    : chosenTemplateLoading && !ownBody
    ? editTemplateQuery.isError
      ? "This draft's template couldn't be loaded. Go back to the Recipients step to retry, or write the email."
      : "This draft's template is still loading."
    : templateWithoutBody && !ownBody
    ? 'The selected template has no body. Write the email or choose another template in Email Content (Recipients step).'
    : 'Write the email or choose a template in Email Content (Recipients step).';
  const CHECK_TEXT: Record<LaunchCheckKey, { label: string; hint: string }> = {
    content: { label: 'Email content', hint: contentHint },
    subject: { label: 'Subject line', hint: 'Add a subject in Email Content (Recipients step).' },
    sender: { label: 'Sender email', hint: 'Add a sender email on the Details step.' },
    recipients: { label: 'At least one recipient who can be emailed', hint: 'Add recipients on the Recipients step.' },
  };

  const stepIndex = STEPS.findIndex((s) => s.key === step);
  const senderText = [form.senderName.trim(), form.senderEmail.trim() && `<${trimEmail(form.senderEmail)}>`]
    .filter(Boolean).join(' ') || 'Not set';
  const contentSummary = ownBody
    ? 'Written for this campaign'
    : selectedTemplate
    ? `Template: ${selectedTemplate.name}`
    : chosenTemplateDeleted
    ? 'Deleted template'
    : 'None yet';

  const footer =
    step === 'details' ? (
      <>
        <Button variant="secondary" type="button" onClick={safeClose}>Cancel</Button>
        <Button type="submit" form="campaign-form">
          Next: Recipients <ArrowRight className="h-4 w-4" />
        </Button>
      </>
    ) : step === 'recipients' ? (
      <>
        <Button variant="secondary" type="button" onClick={() => setStep('details')}>
          <ArrowLeft className="h-4 w-4" /> Back
        </Button>
        <Button type="button" onClick={goToReview} disabled={editLoad.status !== 'ready'}>
          Next: Review &amp; send <ArrowRight className="h-4 w-4" />
        </Button>
      </>
    ) : confirmSend ? (
      <>
        <Button variant="secondary" type="button" onClick={() => setConfirmSend(false)} disabled={sending}>
          <ArrowLeft className="h-4 w-4" /> Back
        </Button>
        <Button type="button" loading={sending} disabled={!sendAllowed && !sending} onClick={() => sendMutation.mutate()}>
          {!sending && <Send className="h-4 w-4" />}
          {sending ? 'Sending…' : 'Send now'}
        </Button>
      </>
    ) : (
      <>
        <Button variant="secondary" type="button" onClick={() => setStep('recipients')} disabled={busy}>
          <ArrowLeft className="h-4 w-4" /> Back
        </Button>
        <Button variant="secondary" type="button" loading={saving} disabled={saveBlocked} onClick={() => saveMutation.mutate()}>
          {saving ? 'Saving…' : savedId != null ? 'Save changes' : 'Save draft'}
        </Button>
        <Button
          type="button"
          disabled={!sendAllowed}
          onClick={() => {
            setSendError(null);
            setConfirmSend(true);
          }}
          title={
            !mayManage
              ? 'Only admins and campaign managers can send campaigns.'
              : !readyToLaunch
              ? 'Complete the checklist above first.'
              : undefined
          }
        >
          <Send className="h-4 w-4" /> Save and send now
        </Button>
      </>
    );

  return (
    <>
      <Modal
        open={open}
        // While the AI dialog is open on top, Escape belongs to it, not to this form.
        onClose={aiOpen ? () => undefined : safeClose}
        widthClass="max-w-4xl"
        title={confirmSend ? 'Send this campaign now?' : isEdit ? `Edit draft: ${campaign?.name ?? ''}` : 'New Campaign'}
        description={
          confirmSend
            ? 'Check who gets it and what they get. Sending starts as soon as you confirm.'
            : step === 'details'
            ? 'Step 1 of 3: campaign name, sender and reply-to.'
            : step === 'recipients'
            ? 'Step 2 of 3: choose who gets the email, then write it in Email Content below.'
            : 'Step 3 of 3: review, then save as a draft or send now.'
        }
        footer={footer}
      >
        {/* Step indicator */}
        {!confirmSend && (
          <ol className="mb-5 flex items-center gap-2 text-xs font-medium">
            {STEPS.map((s, i) => (
              <li key={s.key} className="flex items-center gap-2">
                {i > 0 && <span className="h-px w-6 bg-slate-200" />}
                <button
                  type="button"
                  disabled={i >= stepIndex || busy}
                  onClick={() => setStep(s.key)}
                  className={
                    step === s.key
                      ? 'text-brand-600'
                      : i < stepIndex
                      ? 'text-slate-500 hover:text-brand-600 hover:underline'
                      : 'cursor-default text-slate-400'
                  }
                >
                  {i + 1}. {s.label}
                </button>
              </li>
            ))}
          </ol>
        )}

        {step === 'details' && (
          <form id="campaign-form" onSubmit={goToRecipients} noValidate className="space-y-4">
            {/* Name */}
            <div>
              <label htmlFor="c-name" className="label">Campaign name <span className="text-red-500">*</span></label>
              <input
                id="c-name"
                className={`input ${errors.name ? 'border-red-400 focus:border-red-500 focus:ring-red-500/10' : ''}`}
                placeholder="e.g. October Product Launch"
                value={form.name}
                maxLength={255}
                onChange={(e) => set('name', e.target.value)}
                aria-invalid={!!errors.name}
              />
              {errors.name && <p className="mt-1 flex items-center gap-1 text-xs text-red-600"><AlertCircle className="h-3.5 w-3.5" />{errors.name}</p>}
            </div>

            {/* Description */}
            <div>
              <label htmlFor="c-desc" className="label">Description</label>
              <textarea
                id="c-desc"
                className="input min-h-[72px] resize-y"
                placeholder="Internal note describing this campaign (optional, not sent)"
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
                  maxLength={255}
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
                  maxLength={255}
                  onChange={(e) => set('senderEmail', e.target.value)}
                  aria-invalid={!!errors.senderEmail}
                />
                {errors.senderEmail ? (
                  <p className="mt-1 text-xs text-red-600">{errors.senderEmail}</p>
                ) : (
                  !form.senderEmail.trim() && <p className="mt-1 text-xs text-slate-400">Required before sending.</p>
                )}
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
                maxLength={255}
                onChange={(e) => set('replyTo', e.target.value)}
                aria-invalid={!!errors.replyTo}
              />
              {errors.replyTo && <p className="mt-1 text-xs text-red-600">{errors.replyTo}</p>}
            </div>

            <p className="flex items-start gap-2 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-500">
              <Info className="mt-0.5 h-4 w-4 shrink-0" />
              The subject and the email itself are on the next step, below the recipients.
            </p>
          </form>
        )}

        {step === 'recipients' && (
          <div className="space-y-4">
            {editLoad.status === 'loading' && (
              <p className="flex items-center gap-2 rounded-lg bg-slate-50 px-3 py-2 text-sm text-slate-600">
                <Loader2 className="h-4 w-4 animate-spin" /> Loading this draft's recipients and content…
              </p>
            )}
            {editLoad.status === 'error' && (
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
                <span className="flex items-center gap-2">
                  <AlertTriangle className="h-4 w-4" />
                  {editLoad.message} Saving is disabled so the existing selection and content aren't replaced.
                </span>
                {campaign && (
                  <Button type="button" size="sm" variant="secondary" onClick={() => loadDraftRecipients(campaign)}>Retry</Button>
                )}
              </div>
            )}
            {editLoad.status === 'ready' && editLoad.missing > 0 && (
              <p className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">
                <Info className="mt-0.5 h-4 w-4 shrink-0" />
                {plural(editLoad.missing, 'previously selected recipient')} no longer {editLoad.missing === 1 ? 'exists' : 'exist'} (deleted) and will be removed from this draft when you save.
              </p>
            )}
            {groupCount > 0 && (
              <p className="flex items-start gap-2 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600">
                <Info className="mt-0.5 h-4 w-4 shrink-0" />
                This draft also targets {plural(groupCount, 'recipient group')} set up through the API. {groupCount === 1 ? 'It is' : 'They are'} kept unchanged; group members are added when the campaign launches.
              </p>
            )}
            {editLoad.status === 'ready' && selection.draft.length > 0 && (
              <p className="flex items-start gap-2 rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600">
                <Info className="mt-0.5 h-4 w-4 shrink-0" />
                {plural(selection.draft.length, 'campaign-only address', 'campaign-only addresses')} saved with this draft {selection.draft.length === 1 ? 'is' : 'are'} in the final list below, marked "Saved with draft". Remove any you no longer want.
              </p>
            )}
            {editLoad.status === 'ready' && (
              <>
                <CampaignRecipientSelector
                  value={selection}
                  onChange={setSelection}
                  templatePlaceholders={contentPlaceholders}
                />
                <CampaignContentSection
                  templates={allTemplates}
                  templatesLoading={templatesQuery.isLoading}
                  templatesError={templatesQuery.isError ? apiErrorMessage(templatesQuery.error, '') : null}
                  deletedTemplate={
                    templateDeleted && editTemplateId
                      ? { id: editTemplateId, name: campaign?.templateName ?? 'Previous template' }
                      : null
                  }
                  unresolvedTemplate={
                    editTemplateUnresolved && editTemplateId
                      ? { id: editTemplateId, name: campaign?.templateName ?? 'Current template' }
                      : null
                  }
                  unresolvedTemplateError={
                    editTemplateQuery.isError && !templateDeleted ? apiErrorMessage(editTemplateQuery.error, '') : null
                  }
                  templateRequired={isEdit && !!editTemplateId && !templateDeleted}
                  content={content}
                  onContentChange={changeContent}
                  subject={form.subject}
                  onSubjectChange={(s) => set('subject', s)}
                  subjectError={errors.subject ?? null}
                  bodyError={errors.body ?? bodyProblem}
                  customKeys={customFieldKeys(selection)}
                  previewRecipients={combined.list}
                  onImproveWithAi={() => setAiOpen(true)}
                />
              </>
            )}
          </div>
        )}

        {step === 'review' && confirmSend && resolved && (
          <div className="space-y-4">
            <div className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-900">
              <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0 text-amber-500" />
              <p>
                This saves the campaign and <b>sends it now</b> to{' '}
                <b>{plural(resolved.sendable, 'recipient')}</b>
                {groupCount > 0 ? ` plus the members of ${plural(groupCount, 'recipient group')}` : ''}. It can't be
                undone: once sending starts, emails that went out can't be called back.
              </p>
            </div>
            <dl className="grid grid-cols-1 gap-3 text-sm sm:grid-cols-2">
              <SummaryItem label="Recipients to be emailed" value={resolved.sendable.toLocaleString()} />
              <SummaryItem label="Subject" value={form.subject.trim()} />
              <SummaryItem label="From (sender)" value={senderText} />
              <SummaryItem label="Content" value={contentSummary} />
            </dl>
            {(resolved.skipped.length > 0 || resolved.unsubscribed.length > 0 || resolved.blocked.length > 0) && (
              <div className="space-y-1 text-xs text-amber-700">
                {resolved.skipped.length > 0 && (
                  <p>
                    Skipped ({resolved.skipped.length}):{' '}
                    {resolved.skipped.slice(0, 5).map((s) => `${s.email} (${s.reason.toLowerCase()})`).join(', ')}
                    {resolved.skipped.length > 5 ? '…' : ''}
                  </p>
                )}
                {resolved.unsubscribed.length > 0 && (
                  <p>Not included, unsubscribed ({resolved.unsubscribed.length}): {resolved.unsubscribed.slice(0, 5).join(', ')}{resolved.unsubscribed.length > 5 ? '…' : ''}</p>
                )}
                {resolved.blocked.length > 0 && (
                  <p>Not included, deleted contacts ({resolved.blocked.length}): {resolved.blocked.slice(0, 5).join(', ')}{resolved.blocked.length > 5 ? '…' : ''}</p>
                )}
              </div>
            )}
            <p className="text-xs text-slate-500">
              Only these recipients get the email: the ones you selected, uploaded or added in the table. "Sent" means
              the mail server accepted the email; delivery to each inbox isn't tracked.
            </p>
            {sendError && (
              <p role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
                {sendError}
              </p>
            )}
          </div>
        )}

        {step === 'review' && !confirmSend && (
          <div className="space-y-5">
            {/* Summary */}
            <dl className="grid grid-cols-2 gap-3 text-sm sm:grid-cols-3">
              <SummaryItem label="Campaign" value={form.name} />
              <SummaryItem label="Content" value={contentSummary} />
              <SummaryItem label="Subject" value={form.subject} />
              <SummaryItem label="Sender" value={senderText} />
              <SummaryItem label="Reply-to" value={form.replyTo || 'Not set'} />
              <SummaryItem
                label="Recipients"
                value={resolved ? resolved.final.length.toLocaleString() + (groupCount ? ` + ${plural(groupCount, 'group')}` : '') : '…'}
              />
            </dl>

            {review.status === 'loading' && (
              <p className="flex items-center gap-2 text-sm text-slate-500"><Loader2 className="h-4 w-4 animate-spin" /> Checking recipients…</p>
            )}
            {review.status === 'error' && (
              <div className="flex flex-wrap items-center justify-between gap-2 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
                <span>{review.message}</span>
                <Button type="button" size="sm" variant="secondary" onClick={() => setReviewAttempt((n) => n + 1)}>
                  Retry
                </Button>
              </div>
            )}

            {resolved && (
              <>
                {/* Launch readiness */}
                <div className="rounded-xl border border-slate-200 p-4">
                  <p className="mb-2 text-sm font-semibold text-slate-900">
                    {readyToLaunch ? 'Ready to send' : 'You can save this draft now. Before sending:'}
                  </p>
                  <ul className="space-y-1.5 text-sm">
                    {checkResults.map((c) => (
                      <li key={c.key} className="flex items-start gap-2">
                        {c.ok ? (
                          <CheckCircle2 className="mt-0.5 h-4 w-4 shrink-0 text-emerald-500" />
                        ) : (
                          <XCircle className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
                        )}
                        <span className={c.ok ? 'text-slate-700' : 'text-slate-600'}>
                          {CHECK_TEXT[c.key].label}
                          {!c.ok && <span className="text-xs text-slate-400"> · {CHECK_TEXT[c.key].hint}</span>}
                        </span>
                      </li>
                    ))}
                  </ul>
                  <p className="mt-2 text-xs text-slate-400">
                    "Save draft" never sends email. "Save and send now" saves and starts sending after you confirm
                    {mayManage ? '' : ' (only admins and campaign managers can send)'}.
                  </p>
                </div>

                {/* Recipient breakdown */}
                <div className="space-y-2 text-sm">
                  <p className="text-slate-700">
                    <b>{resolved.final.length.toLocaleString()}</b> recipient{resolved.final.length === 1 ? '' : 's'}:{' '}
                    {plural(resolved.final.length - resolved.newAddresses.length, 'saved contact')},{' '}
                    {saveAsContacts
                      ? plural(resolved.newAddresses.length, 'new contact')
                      : plural(resolved.newAddresses.length, 'campaign-only address', 'campaign-only addresses')}.
                  </p>
                  {resolved.skipped.length > 0 && (
                    <p className="flex items-start gap-2 text-xs text-amber-700">
                      <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                      <span>
                        {plural(resolved.skipped.length, 'selected contact')} won't be emailed and will be skipped when sending:{' '}
                        {resolved.skipped.slice(0, 3).map((s) => `${s.email} (${s.reason.toLowerCase()})`).join(', ')}
                        {resolved.skipped.length > 3 ? '…' : ''}.
                      </span>
                    </p>
                  )}
                  {resolved.blocked.length > 0 && (
                    <p className="flex items-start gap-2 text-xs text-amber-700">
                      <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                      {plural(resolved.blocked.length, 'address', 'addresses')} belong{resolved.blocked.length === 1 ? 's' : ''} to deleted contacts and won't be included: {resolved.blocked.slice(0, 3).join(', ')}{resolved.blocked.length > 3 ? '…' : ''}
                    </p>
                  )}
                  {resolved.unsubscribed.length > 0 && (
                    <p className="flex items-start gap-2 text-xs text-amber-700">
                      <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                      {plural(resolved.unsubscribed.length, 'address', 'addresses')} {resolved.unsubscribed.length === 1 ? 'is' : 'are'} unsubscribed and won't be included: {resolved.unsubscribed.slice(0, 3).join(', ')}{resolved.unsubscribed.length > 3 ? '…' : ''}
                    </p>
                  )}
                  {unusedTableRows > 0 && (
                    <p className="flex items-start gap-2 text-xs text-amber-700">
                      <AlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                      {plural(unusedTableRows, 'row')} of the "Add emails manually" table {unusedTableRows === 1 ? "isn't" : "aren't"} used
                      (no email, invalid email, repeated or a deleted contact). Each row shows why.
                    </p>
                  )}
                  <p className="text-xs text-slate-400">Addresses on the unsubscribe list are also skipped at send time.</p>
                </div>

                {/* New addresses: campaign-only by default, saving them as contacts is an opt-in */}
                {resolved.newAddresses.length > 0 && (
                  <div className="rounded-xl border border-brand-200 bg-brand-50/60 p-4 text-sm">
                    <p className="flex items-center gap-2 font-semibold text-brand-900">
                      <Info className="h-4 w-4" />
                      {saveAsContacts
                        ? `${plural(resolved.newAddresses.length, 'new address', 'new addresses')} will be saved as contacts`
                        : `${plural(resolved.newAddresses.length, 'new address', 'new addresses')} will be used for this campaign only`}
                    </p>
                    <p className="mt-1 text-xs text-brand-800">
                      {[
                        resolved.newFromFile > 0 && `${plural(resolved.newFromFile, 'address', 'addresses')} from the uploaded file`,
                        resolved.newTyped > 0 && plural(resolved.newTyped, 'address added manually', 'addresses added manually'),
                        resolved.newStored > 0 && `${plural(resolved.newStored, 'address', 'addresses')} saved with this draft`,
                      ]
                        .filter(Boolean)
                        .join(', ')}
                      {' '}{resolved.newAddresses.length === 1 ? "isn't a saved contact" : "aren't saved contacts"}.
                    </p>
                    {!saveAsContacts && (
                      <ul className="mt-2 list-disc space-y-0.5 pl-5 text-xs text-brand-800">
                        <li>They're stored with this campaign, for sending and for its delivery history.</li>
                        <li>They're not added to your Recipients list, and other campaigns can't reuse them.</li>
                        <li>
                          If you delete the campaign, this address list is deleted with it. If it was already sent,
                          its delivery history is kept, including the address and details each email was sent with.
                        </li>
                        <li>If someone unsubscribes, the unsubscribe is kept for all campaigns.</li>
                      </ul>
                    )}
                    <p className="mt-2 max-h-16 overflow-y-auto text-xs text-brand-900">
                      {resolved.newAddresses.slice(0, 20).map((e) => e.email).join(', ')}
                      {resolved.newAddresses.length > 20 ? ` and ${(resolved.newAddresses.length - 20).toLocaleString()} more` : ''}
                    </p>
                    <label className="mt-3 flex items-start gap-2 text-sm text-brand-900">
                      <input
                        type="checkbox"
                        className="mt-0.5 h-4 w-4 rounded border-brand-300"
                        checked={saveAsContacts}
                        onChange={(e) => setSaveAsContacts(e.target.checked)}
                      />
                      <span className="flex items-center gap-1.5">
                        <UserPlus className="h-4 w-4 shrink-0" />
                        {resolved.newAddresses.length === 1
                          ? 'Also save this new address as a contact'
                          : `Also save these ${plural(resolved.newAddresses.length, 'new address', 'new addresses')} as contacts`}
                      </span>
                    </label>
                    {saveAsContacts && (
                      <p className="mt-1 pl-6 text-xs text-brand-800">
                        They're added to your Recipients list when you save the draft and stay after this campaign
                        (you can delete them on the Recipients page). Other campaigns can then select them.
                      </p>
                    )}
                    <button
                      type="button"
                      className="mt-2 text-xs font-medium text-brand-700 underline hover:text-brand-900"
                      onClick={() =>
                        setSelection((sel) => resolved.newAddresses.reduce((acc, e) => removeAddress(acc, e.email), sel))
                      }
                    >
                      Remove the new addresses from this campaign
                    </button>
                  </div>
                )}

                {/* Personalised preview, rendered by the server */}
                <ContentPreview
                  subject={form.subject}
                  content={content}
                  templateUnavailable={chosenTemplateDeleted ? 'The template was deleted.' : null}
                  recipients={resolved.final}
                />

                {campaignOnlyOverLimit && (
                  <p className="text-right text-xs text-red-600">
                    A campaign can use at most {CAMPAIGN_ONLY_LIMIT.toLocaleString()} campaign-only addresses. Remove some, or save them as contacts.
                  </p>
                )}
                {(subjectProblem || bodyProblem) && (
                  <p className="text-right text-xs text-red-600">
                    Fix the Email Content on the Recipients step before saving: {subjectProblem ?? bodyProblem}.
                  </p>
                )}
              </>
            )}
          </div>
        )}
      </Modal>

      <AiImproveModal
        open={aiOpen}
        onClose={() => setAiOpen(false)}
        initialSubject={form.subject}
        initialBody={aiBody}
        onAccept={handleAiAccept}
      />
    </>
  );
}

function SummaryItem({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg bg-slate-50 px-3 py-2">
      <dt className="text-xs text-slate-500">{label}</dt>
      <dd className="truncate font-medium text-slate-800" title={value}>{value || '—'}</dd>
    </div>
  );
}
