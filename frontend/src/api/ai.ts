import { apiClient } from './client';

/** Tones supported by the AI improve feature. */
export type AiTone = 'professional' | 'friendly' | 'persuasive' | 'concise';

export interface ImproveEmailRequest {
  subject: string;
  body: string;
  purpose?: string;
  audience?: string;
  tone?: AiTone | string;
  keyPoints?: string;
}

export interface ImprovedEmail {
  subject: string;
  body: string;
}

export interface AiStatus {
  available: boolean;
  message: string;
}

export const aiApi = {
  /** Whether AI assistance is configured/enabled on the backend. */
  getStatus: () =>
    apiClient.get<AiStatus>('/api/ai/status').then((r) => r.data),

  /**
   * Improve a draft (subject + body). The result is returned for human review;
   * nothing is sent automatically.
   */
  improveEmail: (payload: ImproveEmailRequest) =>
    apiClient.post<ImprovedEmail>('/api/ai/improve-email', payload).then((r) => r.data),
};
