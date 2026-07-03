const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

const TOKEN_KEY = "jwt_token";

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

function authHeaders(): HeadersInit {
  const token = getToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export async function fetchHealth(): Promise<{ status: string; service: string }> {
  const res = await fetch(`${BASE_URL}/api/health`);
  if (!res.ok) {
    throw new Error(`Health check failed: ${res.status}`);
  }
  return res.json();
}

export interface MeResponse {
  user_id: string;
  tenant_id: string;
  email: string;
  role: string;
}

export async function fetchMe(): Promise<MeResponse> {
  const res = await fetch(`${BASE_URL}/api/me`, {
    headers: authHeaders(),
  });
  if (!res.ok) {
    throw new Error(`Auth check failed: ${res.status}`);
  }
  return res.json();
}

export function getGoogleLoginUrl(): string {
  return `${BASE_URL}/oauth2/authorization/google`;
}
