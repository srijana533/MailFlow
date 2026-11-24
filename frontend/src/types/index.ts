// ── Auth ──────────────────────────────────────────────────────────────────────
export interface UserInfo {
  id: number;
  email: string;
  firstName: string;
  lastName: string;
  roles: string[];
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  expiresIn: number;
  user: UserInfo;
}

// ── Campaign ───────────────────────────────────────────────────────────────────
export type CampaignStatus =
  | 'DRAFT' | 'SCHEDULED' | 'QUEUED' | 'RUNNING'
  | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'CANCELLED';

export interface Campaign {
  id: number;
  name: string;
  description?: string;
  subject: string;
  templateId?: number;
  templateName?: string;
  senderName?: string;
  senderEmail?: string;
  replyTo?: string;
  status: CampaignStatus;
  scheduledAt?: string;
  createdAt: string;
  updatedAt: string;
  startedAt?: string;
  completedAt?: string;
  totalRecipients: number;
  sentCount: number;
  failedCount: number;
  skippedCount: number;
  bouncedCount: number;
  retryCount: number;
  successRate: number;
  batchJobId?: string;
  /** Recipient groups targeted by the campaign (set via the API; not editable in the UI). */
  recipientGroupIds?: number[];
  /** Saved contacts selected for the campaign. */
  recipientIds?: number[];
  /** Number of campaign-only addresses (present in every response). */
  targetAddressCount?: number;
  /**
   * Campaign-only addresses: emailed by this campaign without being saved as contacts.
   * Only single-campaign responses (get, create, update) include them; the list omits them.
   */
  targetAddresses?: TargetAddress[];
  /**
   * The campaign's own plain-text email body, sent instead of the template's bodies.
   * Only single-campaign responses include it, and only when the campaign has one.
   */
  customBody?: string | null;
  /**
   * Whether the campaign has email content to send (its own body, or a non-deleted
   * template with a body). Present in every response, including the list.
   */
  hasContent?: boolean;
}

/** An address used by one campaign only. It is stored with the campaign, not as a contact. */
export interface TargetAddress {
  email: string;
  name?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  company?: string | null;
  /** Extra personalisation values, e.g. { Team: "Platform" } for {{Team}}. */
  fields?: Record<string, string>;
}

// ── Recipient ───────────────────────────────────────────────────────────────────
export type RecipientStatus = 'ACTIVE' | 'UNSUBSCRIBED' | 'BOUNCED' | 'INVALID';

export interface Recipient {
  id: number;
  email: string;
  name?: string;
  firstName?: string;
  lastName?: string;
  company?: string;
  course?: string;
  customFields: Record<string, string>;
  status: RecipientStatus;
  importSource?: string;
  createdAt: string;
  updatedAt: string;
}

// ── Template ────────────────────────────────────────────────────────────────────
export interface EmailTemplate {
  id: number;
  name: string;
  description?: string;
  subject: string;
  htmlBody?: string;
  plainBody?: string;
  variables: string[];
  createdAt: string;
  updatedAt: string;
}

// ── Analytics ────────────────────────────────────────────────────────────────────
export interface CampaignAnalytics {
  campaignId: number;
  campaignName: string;
  total: number;
  sent: number;
  failed: number;
  skipped: number;
  bounced: number;
  retried: number;
  successRate: number;
  throughputPerMinute: number;
  durationSeconds?: number;
}

export interface DashboardSummary {
  totalCampaigns: number;
  activeCampaigns: number;
  totalEmailsSent: number;
  totalEmailsFailed: number;
  overallSuccessRate: number;
  totalRecipients: number;
}

// ── Page ─────────────────────────────────────────────────────────────────────────
export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  first: boolean;
  last: boolean;
}
