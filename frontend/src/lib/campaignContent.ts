import type { RenderRecipient } from '../api/templates';
import type { CombinedRecipient, RecipientSelection } from './recipientSelection';

/**
 * A campaign's email content in the campaign form: the subject and the campaign's own
 * plain-text body, or a template's content. Pure helpers: the validation shared with the
 * backend, placeholder chips and inserting them at the cursor, the choice between a
 * template and the campaign's own text, and the content rule of the launch checks.
 *
 * Previews are always rendered by the server (POST /api/templates/render and
 * /api/templates/{id}/render); nothing here fills placeholders.
 */

// ── Validation (same rules as the backend's EmailContentRules) ─────────────────
// The backend test CampaignContentTest compares the limits, isControlChar and the
// allowed control characters with this file.

export const SUBJECT_MAX_LENGTH = 998;
export const BODY_MAX_LENGTH = 100_000;

/** Control characters: U+0000-U+001F and U+007F-U+009F, as Java's Character.isISOControl. */
export function isControlChar(code: number): boolean {
  return code <= 0x1f || (code >= 0x7f && code <= 0x9f);
}

/** Control characters a subject may contain: tab only. CR and LF would start a new mail header. */
const SUBJECT_ALLOWED_CONTROLS = [0x09];
/** Control characters a body may contain: tab, LF and CR. */
const BODY_ALLOWED_CONTROLS = [0x09, 0x0a, 0x0d];

function firstForbidden(text: string, allowed: number[]): number {
  for (let i = 0; i < text.length; i++) {
    const code = text.charCodeAt(i);
    if (isControlChar(code) && !allowed.includes(code)) return code;
  }
  return -1;
}

/**
 * Why the subject can't be saved, or null. The subject is required for every save (the
 * server refuses a draft without one), at most 998 characters, on one line and without
 * other control characters. It is checked as it is sent: trimmed.
 */
export function subjectError(subject: string): string | null {
  const s = subject.trim();
  if (!s) return 'A subject is required, even for a draft';
  if (s.length > SUBJECT_MAX_LENGTH) return `Use at most ${SUBJECT_MAX_LENGTH} characters`;
  const bad = firstForbidden(s, SUBJECT_ALLOWED_CONTROLS);
  if (bad === 0x0a || bad === 0x0d) return 'The subject must be on one line';
  if (bad >= 0) return 'Remove the control character from the subject';
  return null;
}

/**
 * Why the body can't be saved, or null. A blank body is fine for a draft; launching
 * needs content. At most 100,000 characters; tabs and line breaks are allowed, other
 * control characters (for example from a pasted document) are not.
 */
export function bodyError(body: string): string | null {
  if (body.length > BODY_MAX_LENGTH) {
    return `The email can have at most ${BODY_MAX_LENGTH.toLocaleString('en-US')} characters (it has ${body.length.toLocaleString('en-US')})`;
  }
  if (firstForbidden(body, BODY_ALLOWED_CONTROLS) >= 0) {
    return 'The email contains a control character, for example from a pasted document. Remove it; tabs and line breaks are fine.';
  }
  return null;
}

// ── Placeholders ────────────────────────────────────────────────────────────────

/**
 * Placeholders every recipient has (the backend's PersonalizationModel). They are
 * case-sensitive and each also works in lower camel case ({{firstName}}); a missing value
 * is blank.
 */
export const STANDARD_PLACEHOLDERS = [
  'FirstName', 'LastName', 'Name', 'Email', 'Company', 'Course', 'Role', 'Phone', 'Location', 'Interest', 'Department',
] as const;

/** The chips: the standard placeholders, then custom fields that aren't one of them. */
export function placeholderChips(customKeys: Iterable<string>): string[] {
  const out: string[] = [...STANDARD_PLACEHOLDERS];
  const taken = new Set(out.map((p) => p.toLowerCase()));
  // The send-time model fills these from standard fields, whatever a custom field says.
  ['email', 'name', 'firstname', 'lastname', 'company', 'course'].forEach((k) => taken.add(k));
  const custom = new Set<string>();
  for (const raw of customKeys) {
    const key = raw.trim();
    if (!/^\w+$/.test(key) || taken.has(key.toLowerCase()) || custom.has(key)) continue;
    custom.add(key);
  }
  return [...out, ...custom];
}

/**
 * Custom fields the selected recipients bring: columns of the current upload mapped to a
 * custom field ("Team" mapped to custom:Team gives {{Team}}) and the fields of the
 * draft's stored campaign-only addresses.
 */
export function customFieldKeys(sel: RecipientSelection): string[] {
  const keys = new Set<string>();
  for (const target of Object.values(sel.upload?.preview.detectedColumns ?? {})) {
    if (target.startsWith('custom:')) keys.add(target.slice('custom:'.length));
  }
  for (const a of sel.draft) Object.keys(a.fields ?? {}).forEach((k) => keys.add(k));
  return Array.from(keys);
}

export function placeholderToken(key: string): string {
  return `{{${key}}}`;
}

/**
 * Inserts `text` at the cursor of a text field: it replaces the selection when there is
 * one, and goes at the end when the field has no cursor position. Returns the new value
 * and the cursor position after the inserted text.
 */
export function insertAtCursor(
  value: string,
  text: string,
  selectionStart: number | null | undefined,
  selectionEnd?: number | null,
): { value: string; cursor: number } {
  const start = selectionStart == null || selectionStart < 0 || selectionStart > value.length
    ? value.length
    : selectionStart;
  const end = selectionEnd == null || selectionEnd < start || selectionEnd > value.length ? start : selectionEnd;
  return { value: value.slice(0, start) + text + value.slice(end), cursor: start + text.length };
}

// ── Template or the campaign's own text ────────────────────────────────────────

/** A template's content, as far as the form needs it. */
export interface TemplateContent {
  subject?: string | null;
  htmlBody?: string | null;
  plainBody?: string | null;
  /** The template was deleted (it can't be used to send). */
  deleted?: boolean;
}

/**
 * Where the body comes from. 'template': the chosen template's bodies are sent unchanged
 * (nothing is copied). 'own': the campaign's own plain-text body is sent.
 */
export type BodySource = 'template' | 'own';

export interface ContentState {
  /** "Start from a template"; null for none. */
  templateId: number | null;
  source: BodySource;
  /** The campaign's own body; used when `source` is 'own'. */
  body: string;
}

/**
 * The form's content for a campaign: its own body when it has one, otherwise the
 * template's content, and an empty own body when there is no template either.
 */
export function initialContent(customBody: string | null | undefined, templateId: number | null | undefined): ContentState {
  const own = typeof customBody === 'string' && customBody.trim() !== '';
  return {
    templateId: templateId ?? null,
    source: own || templateId == null ? 'own' : 'template',
    body: own ? (customBody as string) : '',
  };
}

/**
 * The customBody to save. The template's content is saved as "", which clears a stored
 * body: the server keeps no body (NULL), meaning "use the template's body". A blank own
 * body is saved the same way.
 */
export function customBodyToSave(state: ContentState): string {
  return state.source === 'own' ? state.body : '';
}

/** The content rule of the launch checks (the server's rule): an own body, or a usable template. */
export function hasEmailContent(customBody: string | null | undefined, template: TemplateContent | null | undefined): boolean {
  if (customBody?.trim()) return true;
  return !!template && !template.deleted && !!(template.htmlBody?.trim() || template.plainBody?.trim());
}

/** hasEmailContent for the form's state and the chosen template (null when none or unknown). */
export function contentReady(state: ContentState, template: TemplateContent | null | undefined): boolean {
  return hasEmailContent(customBodyToSave(state), state.templateId == null ? null : template);
}

/**
 * Text to start the campaign's own body from a template ("Customize for this campaign"):
 * its plain-text body, or the text of its HTML body when it has only HTML. The HTML
 * design is not used afterwards: the campaign sends plain text (formattingLost).
 */
export function customizeFromTemplate(t: TemplateContent): {
  body: string;
  from: 'plain' | 'html' | 'none';
  formattingLost: boolean;
} {
  const hasHtml = !!t.htmlBody?.trim();
  if (t.plainBody?.trim()) return { body: t.plainBody, from: 'plain', formattingLost: hasHtml };
  if (hasHtml) return { body: htmlToText(t.htmlBody as string), from: 'html', formattingLost: true };
  return { body: '', from: 'none', formattingLost: false };
}

/** What choosing another template does, and what to ask first. */
export interface TemplateChangePlan {
  /** Put the new template's subject in the subject field without asking. */
  fillSubject: boolean;
  /** The user typed a different subject: ask before replacing it. */
  askSubject: boolean;
  /** The campaign has text of its own: ask before replacing it with the template's content. */
  askBody: boolean;
}

/**
 * Choosing a template fills the subject; a subject the user typed is only replaced after
 * asking (an empty subject, or one that came from the previous template, is replaced
 * without asking). When the campaign has its own non-blank text, choosing a template asks
 * before replacing that text.
 */
export function planTemplateChange(args: {
  subject: string;
  previous: TemplateContent | null | undefined;
  next: TemplateContent | null | undefined;
  state: ContentState;
}): TemplateChangePlan {
  const { subject, previous, next, state } = args;
  const nextSubject = next?.subject?.trim() ?? '';
  const current = subject.trim();
  const changes = !!nextSubject && current !== nextSubject;
  const fromTemplate = current === '' || (!!previous?.subject?.trim() && current === previous.subject.trim());
  return {
    fillSubject: changes && fromTemplate,
    askSubject: changes && !fromTemplate,
    askBody: !!next && state.source === 'own' && state.body.trim() !== '',
  };
}

/**
 * The content after choosing `templateId` (null: no template). With a template, its
 * content is used unless the user keeps their own text (`keepOwnText`). Without one, the
 * campaign's own text is the email.
 */
export function applyTemplateChange(state: ContentState, templateId: number | null, keepOwnText: boolean): ContentState {
  if (templateId == null) return { templateId: null, source: 'own', body: state.source === 'own' ? state.body : '' };
  if (keepOwnText && state.source === 'own' && state.body.trim() !== '') return { ...state, templateId };
  return { templateId, source: 'template', body: '' };
}

/** "Use the template's content": the own body is cleared and the template's bodies are sent. */
export function switchToTemplateContent(state: ContentState): ContentState {
  return state.templateId == null ? state : { ...state, source: 'template', body: '' };
}

/** "Customize for this campaign": the template's text becomes the campaign's own body. */
export function customize(state: ContentState, template: TemplateContent): ContentState {
  return { ...state, source: 'own', body: customizeFromTemplate(template).body };
}

// ── Text of an HTML body ────────────────────────────────────────────────────────

const ENTITIES: Record<string, string> = {
  amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', ndash: '–', mdash: '—', hellip: '…',
  lsquo: '‘', rsquo: '’', ldquo: '“', rdquo: '”', copy: '©', reg: '®', trade: '™', euro: '€',
};

function decodeEntities(s: string): string {
  return s.replace(/&(#x[0-9a-f]+|#\d+|[a-z]+);/gi, (m, code: string) => {
    if (code[0] === '#') {
      const n = code[1] === 'x' || code[1] === 'X' ? parseInt(code.slice(2), 16) : parseInt(code.slice(1), 10);
      return Number.isFinite(n) && n > 0 && n <= 0x10ffff ? String.fromCodePoint(n) : m;
    }
    return ENTITIES[code.toLowerCase()] ?? m;
  });
}

// <li> starts its own line ("- "), so </li> needs no break of its own.
const BLOCK_TAGS = 'p|div|h[1-6]|ul|ol|tr|table|thead|tbody|tfoot|blockquote|pre|section|article|header|footer|hr|address';

/**
 * The text of an HTML body, to start a plain-text body from an HTML-only template. The
 * formatting is lost: paragraphs, headings, list items, table rows and <br> become line
 * breaks, links keep only their text, and scripts, styles and comments are dropped.
 * Placeholders such as {{Name}} stay as they are. The result is shown in a text editor,
 * never as HTML.
 */
export function htmlToText(html: string): string {
  let s = html
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/<(script|style|head|title)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, '')
    // Line breaks in the HTML source are only spaces.
    .replace(/\s+/g, ' ');
  s = s
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<li\b[^>]*>/gi, '\n- ')
    .replace(new RegExp(`</?(?:${BLOCK_TAGS})\\b[^>]*>`, 'gi'), '\n')
    .replace(/<[^>]*>/g, '');
  s = decodeEntities(s);
  return s
    .split('\n')
    .map((line) => line.replace(/[ \t]+/g, ' ').trim())
    .join('\n')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

// ── Preview recipient ──────────────────────────────────────────────────────────

/** Sample values for the preview when no recipient is chosen. */
export const SAMPLE_RECIPIENT: RenderRecipient = {
  email: 'sample.recipient@example.com',
  name: 'Sample Recipient',
  firstName: 'Sample',
  lastName: 'Recipient',
  company: 'Example Co',
  fields: {},
};

/** The values a recipient of the final list fills placeholders with (a saved contact's stored data wins). */
export function sampleFor(e: CombinedRecipient): RenderRecipient {
  if (e.contact) {
    const c = e.contact;
    return {
      email: c.email,
      name: c.name ?? null,
      firstName: c.firstName ?? null,
      lastName: c.lastName ?? null,
      company: c.company ?? null,
      course: c.course ?? null,
      fields: c.customFields ?? {},
    };
  }
  return {
    email: e.email,
    name: e.name ?? null,
    firstName: e.firstName ?? null,
    lastName: e.lastName ?? null,
    company: e.company ?? null,
    fields: e.fields ?? {},
  };
}
