import type { RenderRecipient } from '../api/templates';

/**
 * Sample recipient for previewing a template on the Templates page. It is only sent
 * to the render endpoints and is never saved.
 */
export interface SampleData {
  email: string;
  name: string;
  firstName: string;
  lastName: string;
  company: string;
  /** Extra placeholder values, e.g. name "Team" and value "Platform" for {{Team}}. */
  fields: SampleField[];
}

export interface SampleField {
  /** Row id for React keys (rows can be added and removed). */
  id: number;
  key: string;
  value: string;
}

/** Limits of the template form and of POST /api/templates/render. */
export const TEMPLATE_LIMITS = {
  name: 255,
  /** Same as the subject input of the campaign form. */
  subject: 998,
  /** Longest body the preview renders. A longer body can still be saved. */
  previewBody: 500_000,
  sampleText: 255,
  sampleFields: 50,
  sampleKey: 40,
  sampleValue: 1000,
} as const;

export const DEFAULT_SAMPLE: SampleData = {
  email: 'alex.sample@demo.local',
  name: 'Alex Sample',
  firstName: 'Alex',
  lastName: 'Sample',
  company: 'Demo Company',
  fields: [],
};

let nextFieldId = 1;

export function newSampleField(): SampleField {
  return { id: nextFieldId++, key: '', value: '' };
}

/** Placeholder names are letters, digits and underscores, as in {{FirstName}}. */
const KEY_RE = /^\w+$/;

/** Placeholders filled from the sample's standard inputs. */
const STANDARD_KEYS = new Set([
  'email', 'Email', 'name', 'Name', 'firstName', 'FirstName', 'lastName', 'LastName', 'company', 'Company',
]);

/**
 * Capitalised placeholders that the send-time model (PersonalizationModel) copies
 * from a lower-case field, so a custom field with the capitalised name is ignored.
 */
const ALIAS_SOURCE: Record<string, string> = {
  Course: 'course',
  Role: 'role',
  Phone: 'phone',
  Location: 'location',
  Interest: 'interest',
  Department: 'department',
};

/** Why a custom field can't be used, by row id. Empty when every row is usable. */
export function sampleFieldErrors(fields: SampleField[]): Record<number, string> {
  const errors: Record<number, string> = {};
  const seen = new Set<string>();
  for (const f of fields) {
    const key = f.key.trim();
    if (!key) {
      if (f.value.trim()) errors[f.id] = 'Enter the placeholder name, for example Team';
      continue;
    }
    if (!KEY_RE.test(key)) errors[f.id] = 'Use letters, digits and underscores only';
    else if (key.length > TEMPLATE_LIMITS.sampleKey) errors[f.id] = `Use at most ${TEMPLATE_LIMITS.sampleKey} characters`;
    else if (STANDARD_KEYS.has(key)) errors[f.id] = 'Set this with the fields above';
    else if (ALIAS_SOURCE[key]) {
      errors[f.id] = `{{${key}}} is filled from {{${ALIAS_SOURCE[key]}}}. Name this field ${ALIAS_SOURCE[key]}`;
    } else if (seen.has(key)) errors[f.id] = 'This name is already used';
    seen.add(key);
  }
  return errors;
}

/** The sample as the render endpoints expect it. Blank text is sent as null; rows without a name are left out. */
export function toRenderRecipient(sample: SampleData): RenderRecipient {
  const text = (v: string) => (v.trim() ? v.trim() : null);
  const fields: Record<string, string> = {};
  for (const f of sample.fields) {
    const key = f.key.trim();
    if (key) fields[key] = f.value.trim();
  }
  return {
    email: text(sample.email),
    name: text(sample.name),
    firstName: text(sample.firstName),
    lastName: text(sample.lastName),
    company: text(sample.company),
    fields,
  };
}
