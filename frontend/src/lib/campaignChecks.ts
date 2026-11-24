import type { Campaign } from '../types';
import { hasEmailContent } from './campaignContent';

/** Number of campaign-only addresses (emailed without being saved as contacts). */
export function campaignOnlyCount(c: Campaign): number {
  return c.targetAddressCount ?? c.targetAddresses?.length ?? 0;
}

/**
 * Number of recipients selected for a campaign: saved contacts plus campaign-only
 * addresses (group members are resolved at launch).
 */
export function selectedRecipientCount(c: Campaign): number {
  return (c.recipientIds?.length ?? 0) + campaignOnlyCount(c);
}

export function hasRecipientTargets(c: Campaign): boolean {
  return selectedRecipientCount(c) > 0 || (c.recipientGroupIds?.length ?? 0) > 0;
}

/**
 * Whether a saved campaign has email content: its own body, or a non-deleted template
 * with a body. The server computes it for every campaign (`hasContent`); without that
 * field, the campaign's own body or a template ID is the best the client knows.
 */
export function campaignHasContent(c: Pick<Campaign, 'hasContent' | 'customBody' | 'templateId'>): boolean {
  if (typeof c.hasContent === 'boolean') return c.hasContent;
  return hasEmailContent(c.customBody, null) || !!c.templateId;
}

/** The launch blocker for a campaign without email content. */
export const CONTENT_BLOCKER = 'add the email content (choose a template or write the email)';

/**
 * What still blocks launching a draft, mirroring the backend's launch validation:
 * email content (the campaign's own body, or a template with a body), a subject, a
 * sender email and at least one recipient are required.
 * An empty list means the campaign can be launched.
 */
export function launchBlockers(c: Campaign): string[] {
  const out: string[] = [];
  if (!campaignHasContent(c)) out.push(CONTENT_BLOCKER);
  if (!c.subject?.trim()) out.push('add a subject');
  if (!c.senderEmail?.trim()) out.push('add a sender email');
  if (!hasRecipientTargets(c)) out.push('select recipients');
  return out;
}

/** What the campaign form knows before saving, for its launch checklist. */
export interface DraftLaunchInput {
  /** Email content (see contentReady in campaignContent). */
  hasContent: boolean;
  /** A subject that can be saved (no subjectError). */
  subjectOk: boolean;
  /** A valid sender email. */
  senderEmailOk: boolean;
  /** Recipients who can be emailed, after skipping unsubscribed and inactive contacts. */
  sendableRecipients: number;
  /** Recipient groups set through the API (their members are added at launch). */
  groupCount: number;
}

export type LaunchCheckKey = 'content' | 'subject' | 'sender' | 'recipients';

/** The form's launch checklist: the same four rules as launchBlockers and the server. */
export function draftLaunchChecks(d: DraftLaunchInput): { key: LaunchCheckKey; ok: boolean }[] {
  return [
    { key: 'content', ok: d.hasContent },
    { key: 'subject', ok: d.subjectOk },
    { key: 'sender', ok: d.senderEmailOk },
    { key: 'recipients', ok: d.sendableRecipients > 0 || d.groupCount > 0 },
  ];
}

/** "Save and send now" is offered only when every launch check passes and the user may send. */
export function canSaveAndSend(d: DraftLaunchInput & { canManage: boolean }): boolean {
  return d.canManage && draftLaunchChecks(d).every((c) => c.ok);
}

/** The server's answer (409) to deleting a QUEUED or RUNNING campaign. */
export const DELETE_WHILE_SENDING = 'This campaign is sending. Cancel it, wait until sending stops, then delete it.';

/**
 * Why a campaign can't be deleted yet, mirroring the server's rule as far as the
 * client can know it: QUEUED and RUNNING campaigns are sending. An empty list means
 * the delete can be offered. The server also refuses (409, with the reason) while a
 * send job of the campaign is still running or some of its emails are still in the
 * send queue; the campaign data on the client doesn't show that.
 */
export function deleteBlockers(c: Pick<Campaign, 'status'>): string[] {
  return c.status === 'QUEUED' || c.status === 'RUNNING' ? [DELETE_WHILE_SENDING] : [];
}

/** "choose an email template and select recipients" */
export function joinBlockers(items: string[]): string {
  if (items.length <= 1) return items.join('');
  return `${items.slice(0, -1).join(', ')} and ${items[items.length - 1]}`;
}
