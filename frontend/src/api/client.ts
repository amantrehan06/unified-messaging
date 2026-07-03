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

export interface ChannelResponse {
  id: string;
  type: string;
  provider: string;
  status: string;
  externalAccountId: string | null;
  connectedAt: string | null;
  lastStatusAt: string | null;
}

export async function fetchChannels(): Promise<ChannelResponse[]> {
  const res = await fetch(`${BASE_URL}/api/channels`, {
    headers: authHeaders(),
  });
  if (!res.ok) {
    throw new Error(`Failed to fetch channels: ${res.status}`);
  }
  return res.json();
}

export async function connectChannel(
  type: string
): Promise<{ url: string }> {
  const res = await fetch(`${BASE_URL}/api/channels/connect`, {
    method: "POST",
    headers: { ...authHeaders(), "Content-Type": "application/json" },
    body: JSON.stringify({ type }),
  });
  if (!res.ok) {
    throw new Error(`Failed to connect channel: ${res.status}`);
  }
  return res.json();
}

export async function reconnectChannel(
  channelId: string
): Promise<{ url: string }> {
  const res = await fetch(`${BASE_URL}/api/channels/${channelId}/reconnect`, {
    method: "POST",
    headers: authHeaders(),
  });
  if (!res.ok) {
    throw new Error(`Failed to reconnect channel: ${res.status}`);
  }
  return res.json();
}
