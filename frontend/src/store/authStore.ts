import { create } from 'zustand';
import type { UserInfo } from '../types';
import { apiClient } from '../api/client';

interface AuthState {
  user: UserInfo | null;
  isAuthenticated: boolean;
  login: (email: string, password: string) => Promise<void>;
  logout: () => void;
  initFromStorage: () => void;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isAuthenticated: false,

  login: async (email, password) => {
    const res = await apiClient.post('/api/auth/login', { email, password });
    const { accessToken, user } = res.data;
    localStorage.setItem('accessToken', accessToken);
    set({ user, isAuthenticated: true });
  },

  logout: () => {
    localStorage.removeItem('accessToken');
    set({ user: null, isAuthenticated: false });
    window.location.href = '/login';
  },

  initFromStorage: () => {
    const token = localStorage.getItem('accessToken');
    if (token) {
      // Decode JWT payload (not verified here — server verifies on every request)
      try {
        const payload = JSON.parse(atob(token.split('.')[1]));
        if (payload.exp * 1000 > Date.now()) {
          set({ isAuthenticated: true });
        } else {
          localStorage.removeItem('accessToken');
        }
      } catch {
        localStorage.removeItem('accessToken');
      }
    }
  },
}));
