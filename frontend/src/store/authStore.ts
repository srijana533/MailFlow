import { create } from 'zustand';
import type { UserInfo } from '../types';
import { apiClient } from '../api/client';

interface AuthState {
  user: UserInfo | null;
  /**
   * Role names without the "ROLE_" prefix, e.g. ["CAMPAIGN_MANAGER"]. Set at login and
   * restored from the stored token after a page reload, when `user` is not known.
   * The server checks the roles on every request; the UI only uses them to decide
   * which actions to offer.
   */
  roles: string[];
  isAuthenticated: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  initFromStorage: () => void;
}

/** Decodes a JWT payload (base64url). Not verified here: the server verifies every request. */
function decodeTokenPayload(token: string): { exp?: number; roles?: unknown } {
  const part = (token.split('.')[1] ?? '').replace(/-/g, '+').replace(/_/g, '/');
  return JSON.parse(atob(part));
}

/** The token's "roles" claim holds authorities such as "ROLE_CAMPAIGN_MANAGER". */
function rolesFromToken(payload: { roles?: unknown }): string[] {
  if (!Array.isArray(payload.roles)) return [];
  return payload.roles
    .filter((r): r is string => typeof r === 'string')
    .map((r) => r.replace(/^ROLE_/, ''));
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  roles: [],
  isAuthenticated: false,

  login: async (email, password) => {
    const res = await apiClient.post('/api/auth/login', { email, password });
    const { accessToken, user } = res.data;
    localStorage.setItem('accessToken', accessToken);
    set({ user, roles: user?.roles ?? [], isAuthenticated: true });
  },

  logout: () => {
    localStorage.removeItem('accessToken');
    set({ user: null, roles: [], isAuthenticated: false });
    window.location.href = '/login';
  },

  initFromStorage: () => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      // Decode JWT payload (not verified here — server verifies on every request)
      try {
        const payload = decodeTokenPayload(token);
        if (payload.exp !== undefined && payload.exp * 1000 > Date.now()) {
          set({ isAuthenticated: true, roles: rolesFromToken(payload) });
        } else {
          localStorage.removeItem('accessToken');
        }
      } catch {
        localStorage.removeItem('accessToken');
      }
    }
  },
}));
