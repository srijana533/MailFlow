import { apiClient } from './client';
import type { EmailTemplate, PageResponse } from '../types';

export interface TemplatePayload {
  name: string;
  description?: string | null;
  subject: string;
  htmlBody?: string | null;
  plainBody?: string | null;
}

/** Recipient values used to resolve placeholders in a personalised preview. */
export interface RenderRecipient {
  email?: string | null;
  name?: string | null;
  firstName?: string | null;
  lastName?: string | null;
  company?: string | null;
  course?: string | null;
  /** Custom fields exactly as stored on a contact (role, phone, Team, …). */
  fields?: Record<string, string>;
}

export interface RenderedEmail {
  subject: string;
  htmlBody: string;
  plainBody: string;
  /** Placeholders that resolve to an empty value for this recipient. */
  emptyVariables: string[];
}

export const templatesApi = {
  list: (params?: { search?: string; page?: number; size?: number }) =>
    apiClient.get<PageResponse<EmailTemplate>>('/api/templates', { params }).then((r) => r.data),

  getById: (id: number) =>
    apiClient.get<EmailTemplate>(`/api/templates/${id}`).then((r) => r.data),

  create: (payload: TemplatePayload) =>
    apiClient.post<EmailTemplate>('/api/templates', payload).then((r) => r.data),

  update: (id: number, payload: TemplatePayload) =>
    apiClient.put<EmailTemplate>(`/api/templates/${id}`, payload).then((r) => r.data),

  delete: (id: number) => apiClient.delete(`/api/templates/${id}`),

  /**
   * Renders the template for one recipient with the same personalisation model
   * as a real send. `subject` (the campaign subject) overrides the template subject.
   */
  render: (id: number, body: { subject?: string; recipient: RenderRecipient }) =>
    apiClient.post<RenderedEmail>(`/api/templates/${id}/render`, body).then((r) => r.data),

  /**
   * Renders content that hasn't been saved (a template being created or edited, or a
   * campaign's own body) for one sample recipient, with the same personalisation model
   * as a real send. A non-blank `customBody` is rendered exactly as a campaign sends it:
   * `plainBody` is the rendered text and `htmlBody` the HTML part made from it.
   * Nothing is saved. Limits: subject 1,000 characters, each body 500,000, customBody
   * 100,000.
   */
  renderContent: (body: {
    subject: string | null;
    htmlBody: string | null;
    plainBody: string | null;
    recipient: RenderRecipient;
    customBody?: string | null;
  }) => apiClient.post<RenderedEmail>('/api/templates/render', body).then((r) => r.data),
};
