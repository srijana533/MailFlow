import { apiClient } from './client';
import type { TeamMember, TeamRole } from '../types';

export interface NewTeamMember {
  email: string;
  firstName: string;
  lastName: string;
  password: string;
  role: TeamRole;
}

export const teamApi = {
  list: () => apiClient.get<TeamMember[]>('/api/team').then((r) => r.data),
  add: (member: NewTeamMember) => apiClient.post<TeamMember>('/api/team', member).then((r) => r.data),
  update: (id: number, change: { role?: TeamRole; enabled?: boolean }) =>
    apiClient.patch<TeamMember>(`/api/team/${id}`, change).then((r) => r.data),
};
