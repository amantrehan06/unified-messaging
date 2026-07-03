import { createContext, useContext, useEffect, useState, useCallback, type ReactNode } from 'react';
import type { Me } from '../api/types';
import { fetchMe, getToken, setToken, clearToken } from '../api/client';

interface AuthState {
  user: Me | null;
  loading: boolean;
  login: (token: string) => void;
  demoLogin: () => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<Me | null>(null);
  const [loading, setLoading] = useState(true);

  const loadUser = useCallback(async () => {
    try {
      const me = await fetchMe();
      setUser(me);
    } catch {
      clearToken();
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const token = params.get('token');
    if (token) {
      setToken(token);
      window.history.replaceState({}, '', '/');
    }

    if (getToken()) {
      loadUser();
    } else {
      setLoading(false);
    }
  }, [loadUser]);

  const login = useCallback((token: string) => {
    setToken(token);
    loadUser();
  }, [loadUser]);

  const demoLogin = useCallback(() => {
    setToken('demo_token');
    loadUser();
  }, [loadUser]);

  const logout = useCallback(() => {
    clearToken();
    setUser(null);
  }, []);

  return (
    <AuthContext value={{ user, loading, login, demoLogin, logout }}>
      {children}
    </AuthContext>
  );
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
