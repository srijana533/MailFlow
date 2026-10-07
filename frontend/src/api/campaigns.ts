import { apiClient } from './client';
import type { Campaign, PageResponse } from '../types';

export const campaignsApi = {
  list: (params?: Record<string, unknown>) =>
    apiClient.get<PageResponse<Campaign>>('/api/campaigns', { params }).then(r => r.data),

  getById: (id: number) =>
    apiClient.get<Campaign>(`/api/campaigns/${id}`).then(r => r.data),

  create: (data: unknown) =>
    apiClient.post<Campaign>('/api/campaigns', data).then(r => r.data),

  update: (id: number, data: unknown) =>
    apiClient.put<Campaign>(`/api/campaigns/${id}`, data).then(r => r.data),

  launch: (id: number) =>
    apiClient.post<Campaign>(`/api/campaigns/${id}/launch`).then(r => r.data),

  pause: (id: number) =>
    apiClient.post<Campaign>(`/api/campaigns/${id}/pause`).then(r => r.data),

  resume: (id: number) =>
    apiClient.post<Campaign>(`/api/campaigns/${id}/resume`).then(r => r.data),

  cancel: (id: number) =>
    apiClient.post<Campaign>(`/api/campaigns/${id}/cancel`).then(r => r.data),

  delete: (id: number) =>
    apiClient.delete(`/api/campaigns/${id}`),

  getAnalytics: (id: number) =>
    apiClient.get(`/api/analytics/campaigns/${id}`).then(r => r.data),
};
