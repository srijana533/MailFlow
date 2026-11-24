import { describe, expect, it } from 'vitest';
import {
  applyTemplateChange,
  BODY_MAX_LENGTH,
  bodyError,
  contentReady,
  customBodyToSave,
  customFieldKeys,
  customize,
  customizeFromTemplate,
  hasEmailContent,
  htmlToText,
  initialContent,
  insertAtCursor,
  isControlChar,
  placeholderChips,
  placeholderToken,
  planTemplateChange,
  sampleFor,
  STANDARD_PLACEHOLDERS,
  SUBJECT_MAX_LENGTH,
  subjectError,
  switchToTemplateContent,
  type ContentState,
} from './campaignContent';
import { EMPTY_SELECTION, type CombinedRecipient, type RecipientSelection } from './recipientSelection';

const NEWSLETTER = { subject: 'October news', htmlBody: '<p>Hi {{FirstName}}</p>', plainBody: 'Hi {{FirstName}}' };
const HTML_ONLY = {
  subject: 'Launch',
  htmlBody: '<h1>Hello {{Name}}</h1>\n<p>We&rsquo;re <b>live</b> &amp; ready.<br>See you</p><ul><li>One</li><li>Two</li></ul>',
  plainBody: null,
};

describe('insertAtCursor (placeholder chips)', () => {
  it('inserts at the cursor and puts the cursor after the placeholder', () => {
    expect(insertAtCursor('Hello , welcome', '{{FirstName}}', 6, 6)).toEqual({
      value: 'Hello {{FirstName}}, welcome',
      cursor: 6 + '{{FirstName}}'.length,
    });
  });

  it('replaces the selected text', () => {
    expect(insertAtCursor('Dear customer,', '{{Name}}', 5, 13)).toEqual({ value: 'Dear {{Name}},', cursor: 13 });
  });

  it('appends when the field has no cursor position', () => {
    expect(insertAtCursor('Thanks', ' {{Company}}', null)).toEqual({ value: 'Thanks {{Company}}', cursor: 18 });
    expect(insertAtCursor('', '{{Email}}', undefined)).toEqual({ value: '{{Email}}', cursor: 9 });
  });

  it('copes with positions outside the text', () => {
    expect(insertAtCursor('abc', 'X', 10, 12).value).toBe('abcX');
    expect(insertAtCursor('abc', 'X', 1, 0)).toEqual({ value: 'aXbc', cursor: 2 });
    expect(insertAtCursor('abc', 'X', -1).value).toBe('abcX');
  });

  it('writes placeholders as {{key}}', () => {
    expect(placeholderToken('Team')).toBe('{{Team}}');
  });
});

describe('placeholder chips', () => {
  it('offers the supported placeholders, then custom fields that are not one of them', () => {
    expect(placeholderChips([])).toEqual([...STANDARD_PLACEHOLDERS]);
    expect(placeholderChips(['Team', 'role', 'Team', ' Region ', 'bad key', 'email', 'course'])).toEqual([
      ...STANDARD_PLACEHOLDERS,
      'Team',
      'Region',
    ]);
  });

  it('takes custom fields from the upload mapping and from the draft addresses', () => {
    const sel: RecipientSelection = {
      ...EMPTY_SELECTION,
      upload: {
        file: {} as File,
        mapping: {},
        removedRows: [],
        preview: {
          fileName: 'people.csv', totalRows: 0, validRows: 0, newRows: 0, existingRows: 0, invalidRows: 0,
          duplicateRows: 0, skippedRows: 0, columns: ['Email', 'Team', 'Role'], unmappedColumns: [],
          detectedColumns: { Email: 'email', Team: 'custom:Team', Role: 'custom:role' },
          hasEmailColumn: true, rows: [],
        },
      },
      draft: [{ email: 'ada@test.local', fields: { Region: 'EU' } }],
    };
    expect(customFieldKeys(sel).sort()).toEqual(['Region', 'Team', 'role']);
    expect(placeholderChips(customFieldKeys(sel)).slice(STANDARD_PLACEHOLDERS.length).sort()).toEqual(['Region', 'Team']);
  });
});

describe('subject and body validation (same rules as the backend)', () => {
  it('requires a subject on one line of at most 998 characters', () => {
    expect(SUBJECT_MAX_LENGTH).toBe(998);
    expect(subjectError('')).toBe('A subject is required, even for a draft');
    expect(subjectError('   ')).toBe('A subject is required, even for a draft');
    expect(subjectError('Hello {{FirstName}}')).toBeNull();
    expect(subjectError('Tab\there')).toBeNull();
    expect(subjectError('s'.repeat(998))).toBeNull();
    expect(subjectError('s'.repeat(999))).toBe('Use at most 998 characters');
    expect(subjectError('Hello\nBcc: someone@test.local')).toBe('The subject must be on one line');
    expect(subjectError('Hello\rWorld')).toBe('The subject must be on one line');
    expect(subjectError(`Bell ${String.fromCharCode(7)}`)).toBe('Remove the control character from the subject');
    // Trimmed as it is sent: a line break at the end is not part of the subject.
    expect(subjectError('Hello\n')).toBeNull();
  });

  it('allows a blank body (drafts), tabs and line breaks, and refuses other control characters', () => {
    expect(BODY_MAX_LENGTH).toBe(100_000);
    expect(bodyError('')).toBeNull();
    expect(bodyError('Line one\r\nLine two\n\tindented')).toBeNull();
    expect(bodyError('b'.repeat(100_000))).toBeNull();
    expect(bodyError('b'.repeat(100_001))).toContain('at most 100,000 characters');
    for (const code of [0x00, 0x07, 0x08, 0x0b, 0x0c, 0x0e, 0x1b, 0x1f, 0x7f, 0x85, 0x9f]) {
      expect(bodyError(`text ${String.fromCharCode(code)} more`), `U+${code.toString(16)}`).toContain('control character');
    }
    expect(bodyError('café \u00a0 😀 שלום')).toBeNull();
  });

  it('treats U+0000-U+001F and U+007F-U+009F as control characters', () => {
    expect(isControlChar(0x00)).toBe(true);
    expect(isControlChar(0x1f)).toBe(true);
    expect(isControlChar(0x20)).toBe(false);
    expect(isControlChar(0x7e)).toBe(false);
    expect(isControlChar(0x7f)).toBe(true);
    expect(isControlChar(0x9f)).toBe(true);
    expect(isControlChar(0xa0)).toBe(false);
  });
});

describe('template or the campaign’s own text', () => {
  const own = (body: string, templateId: number | null = null): ContentState => ({ templateId, source: 'own', body });
  const fromTemplate = (templateId: number): ContentState => ({ templateId, source: 'template', body: '' });

  it('opens a stored campaign with its own body, or with its template’s content', () => {
    expect(initialContent('Hi {{FirstName}}', 3)).toEqual(own('Hi {{FirstName}}', 3));
    expect(initialContent(null, 3)).toEqual(fromTemplate(3));
    expect(initialContent('   ', 3)).toEqual(fromTemplate(3));
    expect(initialContent(undefined, null)).toEqual(own(''));
  });

  it('saves the own body, and "" (clear) for the template’s content', () => {
    expect(customBodyToSave(own('Hello'))).toBe('Hello');
    expect(customBodyToSave(fromTemplate(3))).toBe('');
    expect(customBodyToSave(own('', 3))).toBe('');
  });

  it('"Customize for this campaign" copies the plain body, or the text of an HTML-only template', () => {
    expect(customizeFromTemplate(NEWSLETTER)).toEqual({ body: 'Hi {{FirstName}}', from: 'plain', formattingLost: true });
    expect(customizeFromTemplate({ plainBody: 'Plain only' })).toEqual({ body: 'Plain only', from: 'plain', formattingLost: false });
    expect(customizeFromTemplate(HTML_ONLY)).toEqual({
      body: 'Hello {{Name}}\n\nWe’re live & ready.\nSee you\n\n- One\n- Two',
      from: 'html',
      formattingLost: true,
    });
    expect(customizeFromTemplate({ htmlBody: ' ', plainBody: '' })).toEqual({ body: '', from: 'none', formattingLost: false });
    expect(customize(fromTemplate(3), NEWSLETTER)).toEqual(own('Hi {{FirstName}}', 3));
  });

  it('"Use the template’s content" clears the own body', () => {
    expect(switchToTemplateContent(own('My text', 3))).toEqual(fromTemplate(3));
    // Without a template there is nothing to go back to.
    expect(switchToTemplateContent(own('My text'))).toEqual(own('My text'));
  });

  it('choosing a template fills an empty subject, or one that came from the previous template, without asking', () => {
    expect(planTemplateChange({ subject: '', previous: null, next: NEWSLETTER, state: own('') })).toEqual({
      fillSubject: true, askSubject: false, askBody: false,
    });
    expect(
      planTemplateChange({ subject: 'Launch', previous: HTML_ONLY, next: NEWSLETTER, state: fromTemplate(1) }),
    ).toEqual({ fillSubject: true, askSubject: false, askBody: false });
  });

  it('asks before replacing a subject the user typed', () => {
    expect(planTemplateChange({ subject: 'My own subject', previous: null, next: NEWSLETTER, state: own('') })).toEqual({
      fillSubject: false, askSubject: true, askBody: false,
    });
    // Nothing to replace when the subject already is the template's.
    expect(planTemplateChange({ subject: 'October news', previous: null, next: NEWSLETTER, state: own('') }).askSubject).toBe(false);
  });

  it('asks before replacing the campaign’s own text, after customizing or writing it', () => {
    expect(planTemplateChange({ subject: '', previous: NEWSLETTER, next: HTML_ONLY, state: own('Customized', 2) }).askBody).toBe(true);
    expect(planTemplateChange({ subject: '', previous: null, next: NEWSLETTER, state: own('Written by me') }).askBody).toBe(true);
    expect(planTemplateChange({ subject: '', previous: null, next: NEWSLETTER, state: own('   ') }).askBody).toBe(false);
    expect(planTemplateChange({ subject: '', previous: NEWSLETTER, next: HTML_ONLY, state: fromTemplate(2) }).askBody).toBe(false);
    // "No template" never asks: the own text stays.
    expect(planTemplateChange({ subject: 'x', previous: NEWSLETTER, next: null, state: own('Mine', 2) })).toEqual({
      fillSubject: false, askSubject: false, askBody: false,
    });
  });

  it('applies the choice: the template’s content, or the own text kept', () => {
    expect(applyTemplateChange(own('Mine'), 4, false)).toEqual(fromTemplate(4));
    expect(applyTemplateChange(own('Mine'), 4, true)).toEqual(own('Mine', 4));
    expect(applyTemplateChange(own('  '), 4, true)).toEqual(fromTemplate(4));
    expect(applyTemplateChange(fromTemplate(4), null, true)).toEqual(own(''));
    expect(applyTemplateChange(own('Mine', 4), null, false)).toEqual(own('Mine'));
  });

  it('applies the server’s content rule: an own body, or a usable template', () => {
    expect(hasEmailContent('Hello', null)).toBe(true);
    expect(hasEmailContent('  ', NEWSLETTER)).toBe(true);
    expect(hasEmailContent(null, { htmlBody: ' ', plainBody: '' })).toBe(false);
    expect(hasEmailContent(null, { ...NEWSLETTER, deleted: true })).toBe(false);
    expect(hasEmailContent('Mine', { deleted: true })).toBe(true);
    expect(hasEmailContent(undefined, undefined)).toBe(false);

    expect(contentReady(fromTemplate(3), NEWSLETTER)).toBe(true);
    expect(contentReady(fromTemplate(3), { htmlBody: '', plainBody: null })).toBe(false);
    expect(contentReady(own(''), NEWSLETTER)).toBe(false); // no template chosen
    expect(contentReady(own('', 3), NEWSLETTER)).toBe(true); // blank own text: the template is sent
    expect(contentReady(own('Hi'), null)).toBe(true);
  });
});

describe('htmlToText', () => {
  it('keeps the text and the placeholders and drops markup, scripts, styles and comments', () => {
    expect(
      htmlToText(
        '<html><head><title>x</title><style>p{color:red}</style></head><body><!-- note --><p>Hi {{FirstName}},</p>' +
          '<script>alert(1)</script><p>Visit <a href="https://x.test/?a=1&amp;b=2">our site</a>.</p></body></html>',
      ),
    ).toBe('Hi {{FirstName}},\n\nVisit our site.');
  });

  it('decodes entities once', () => {
    expect(htmlToText('<p>&lt;b&gt; &amp;amp; &#39;q&#39; &#x263A; &nbsp;x</p>')).toBe("<b> &amp; 'q' ☺ x");
  });
});

describe('sampleFor (the preview recipient)', () => {
  it('uses a saved contact’s stored data, otherwise the address’s own values', () => {
    const contact: CombinedRecipient = {
      email: 'ada@test.local', sources: ['manual'], origin: 'manual', name: 'Typed name',
      contact: {
        id: 7, email: 'ada@test.local', name: 'Ada Lovelace', firstName: 'Ada', company: 'Acme',
        customFields: { Team: 'Platform' }, status: 'ACTIVE', createdAt: '', updatedAt: '',
      },
    };
    expect(sampleFor(contact)).toMatchObject({ email: 'ada@test.local', name: 'Ada Lovelace', fields: { Team: 'Platform' } });
    expect(sampleFor({ email: 'new@test.local', sources: ['manual'], origin: 'manual', company: 'Beta' }))
      .toMatchObject({ email: 'new@test.local', company: 'Beta', fields: {} });
  });
});
