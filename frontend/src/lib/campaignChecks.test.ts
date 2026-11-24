import { describe, expect, it } from 'vitest';
import type { Campaign } from '../types';
import {
  campaignHasContent,
  canSaveAndSend,
  CONTENT_BLOCKER,
  deleteBlockers,
  DELETE_WHILE_SENDING,
  draftLaunchChecks,
  joinBlockers,
  launchBlockers,
  selectedRecipientCount,
} from './campaignChecks';

function campaign(overrides: Partial<Campaign> = {}): Campaign {
  return {
    id: 1,
    name: 'Welcome',
    subject: 'Hello {{FirstName}}',
    senderEmail: 'sender@test.local',
    status: 'DRAFT',
    createdAt: '2026-10-25T00:00:00Z',
    updatedAt: '2026-10-25T00:00:00Z',
    totalRecipients: 0,
    sentCount: 0,
    failedCount: 0,
    skippedCount: 0,
    bouncedCount: 0,
    retryCount: 0,
    successRate: 0,
    recipientIds: [10],
    targetAddressCount: 0,
    hasContent: true,
    ...overrides,
  };
}

describe('launchBlockers: the content rule', () => {
  it('lets a campaign with content, a subject, a sender and recipients launch', () => {
    expect(launchBlockers(campaign())).toEqual([]);
  });

  it('blocks a campaign without email content, whatever its template', () => {
    expect(launchBlockers(campaign({ hasContent: false }))).toEqual([CONTENT_BLOCKER]);
    // A template is not enough when the server says it has no body (or was deleted).
    expect(launchBlockers(campaign({ hasContent: false, templateId: 3, templateName: 'Empty' }))).toEqual([
      CONTENT_BLOCKER,
    ]);
    expect(CONTENT_BLOCKER).toBe('add the email content (choose a template or write the email)');
  });

  it('needs no template when the campaign has its own body', () => {
    expect(launchBlockers(campaign({ templateId: undefined, hasContent: true, customBody: 'Hi {{FirstName}}' }))).toEqual([]);
  });

  it('trusts the server; without hasContent it falls back to the own body or a template ID', () => {
    expect(campaignHasContent({ hasContent: false, customBody: 'text', templateId: 3 })).toBe(false);
    expect(campaignHasContent({ hasContent: true })).toBe(true);
    expect(campaignHasContent({ customBody: 'Hello' })).toBe(true);
    expect(campaignHasContent({ customBody: '   ' })).toBe(false);
    expect(campaignHasContent({ templateId: 3 })).toBe(true);
    expect(campaignHasContent({})).toBe(false);
  });

  it('lists every missing piece, content first', () => {
    const c = campaign({ hasContent: false, subject: ' ', senderEmail: '', recipientIds: [], targetAddressCount: 0 });
    expect(launchBlockers(c)).toEqual([CONTENT_BLOCKER, 'add a subject', 'add a sender email', 'select recipients']);
    expect(joinBlockers(launchBlockers(c))).toBe(
      'add the email content (choose a template or write the email), add a subject, add a sender email and select recipients',
    );
  });

  it('counts campaign-only addresses and groups as recipients', () => {
    expect(launchBlockers(campaign({ recipientIds: [], targetAddressCount: 2 }))).toEqual([]);
    expect(launchBlockers(campaign({ recipientIds: [], recipientGroupIds: [4] }))).toEqual([]);
    expect(selectedRecipientCount(campaign({ recipientIds: [1, 2], targetAddressCount: 3 }))).toBe(5);
  });
});

describe('draftLaunchChecks and canSaveAndSend (the form before saving)', () => {
  const ready = { hasContent: true, subjectOk: true, senderEmailOk: true, sendableRecipients: 2, groupCount: 0 };

  it('passes when every check passes', () => {
    expect(draftLaunchChecks(ready).every((c) => c.ok)).toBe(true);
    expect(draftLaunchChecks(ready).map((c) => c.key)).toEqual(['content', 'subject', 'sender', 'recipients']);
  });

  it('fails the content check without content', () => {
    const checks = draftLaunchChecks({ ...ready, hasContent: false });
    expect(checks.find((c) => c.key === 'content')?.ok).toBe(false);
    expect(checks.filter((c) => !c.ok)).toHaveLength(1);
  });

  it('needs a recipient who can be emailed, or a group', () => {
    expect(draftLaunchChecks({ ...ready, sendableRecipients: 0 }).find((c) => c.key === 'recipients')?.ok).toBe(false);
    expect(draftLaunchChecks({ ...ready, sendableRecipients: 0, groupCount: 1 }).find((c) => c.key === 'recipients')?.ok).toBe(true);
  });

  it('offers "Save and send now" only to managers when everything is ready', () => {
    expect(canSaveAndSend({ ...ready, canManage: true })).toBe(true);
    expect(canSaveAndSend({ ...ready, canManage: false })).toBe(false);
    expect(canSaveAndSend({ ...ready, hasContent: false, canManage: true })).toBe(false);
    expect(canSaveAndSend({ ...ready, subjectOk: false, canManage: true })).toBe(false);
    expect(canSaveAndSend({ ...ready, senderEmailOk: false, canManage: true })).toBe(false);
  });
});

describe('deleteBlockers', () => {
  it('refuses QUEUED and RUNNING campaigns only', () => {
    expect(deleteBlockers({ status: 'QUEUED' })).toEqual([DELETE_WHILE_SENDING]);
    expect(deleteBlockers({ status: 'RUNNING' })).toEqual([DELETE_WHILE_SENDING]);
    for (const status of ['DRAFT', 'SCHEDULED', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED'] as const) {
      expect(deleteBlockers({ status })).toEqual([]);
    }
  });
});
