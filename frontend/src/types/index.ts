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
