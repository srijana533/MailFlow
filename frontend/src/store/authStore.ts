import { create } from 'zustand';
import type { UserInfo } from '../types';
import { apiClient } from '../api/client';

export interface SignUpInput {
  companyName: string;
  firstName: string;
  lastName: string;
  email: string;
  password: string;
}

interface AuthState {
  user: UserInfo | null;
  /**
   * Role names without the "ROLE_" prefix, e.g. ["CAMPAIGN_MANAGER"]. Set at login and
   * restored from the stored token after a page reload.
   * The server checks the roles on every request; the UI only uses them to decide
   * which actions to offer.
   */
  roles: string[];
  isAuthenticated: boolean;
  login: (email: string, password: string) => Promise<void>;
  /** Creates a new company workspace with the person as its Admin, and signs them in. */
  signUp: (input: SignUpInput) => Promise<void>;
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

/** Profile shown in the UI (name, company). Not a credential: the token is what the server checks. */
function storedUser(): UserInfo | null {
  try {
    const raw = localStorage.getItem('user');
    return raw ? (JSON.parse(raw) as UserInfo) : null;
  } catch {
    return null;
  }
}

function clearSession() {
  localStorage.removeItem('accessToken');
  localStorage.removeItem('user');
}

export const useAuthStore = create<AuthState>((set) => {
  const startSession = (data: { accessToken: string; user: UserInfo }) => {
    localStorage.setItem('accessToken', data.accessToken);
    localStorage.setItem('user', JSON.stringify(data.user));
    set({ user: data.user, roles: data.user?.roles ?? [], isAuthenticated: true });
  };

  return {
    user: null,
    roles: [],
    isAuthenticated: false,

    login: async (email, password) => {
      const res = await apiClient.post('/api/auth/login', { email, password });
      startSession(res.data);
    },

    signUp: async (input) => {
      const res = await apiClient.post('/api/auth/register', input);
      startSession(res.data);
    },

    logout: () => {
      clearSession();
      set({ user: null, roles: [], isAuthenticated: false });
      window.location.href = '/login';
    },

    initFromStorage: () => {
      const token = localStorage.getItem('accessToken');
      if (!token) return;
      try {
        const payload = decodeTokenPayload(token);
        if (payload.exp !== undefined && payload.exp * 1000 > Date.now()) {
          set({ isAuthenticated: true, roles: rolesFromToken(payload), user: storedUser() });
        } else {
          clearSession();
        }
      } catch {
        clearSession();
      }
    },
  };
});
