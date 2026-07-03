import type {
  Me,
  Channel,
  Label,
  Conversation,
  Message,
  Draft,
  PaginatedResponse,
} from './types';
import { mock } from './mock';

const USE_MOCK = import.meta.env.VITE_USE_MOCK !== 'false';
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080';
const TOKEN_KEY = 'jwt_token';

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
  return token ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } : { 'Content-Type': 'application/json' };
}

async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, { ...init, headers: { ...authHeaders(), ...init?.headers } });
  if (!res.ok) throw new Error(`API error ${res.status}: ${path}`);
  return res.json();
}

export function getGoogleLoginUrl(): string {
  return `${BASE_URL}/oauth2/authorization/google`;
}

export async function fetchMe(): Promise<Me> {
  if (USE_MOCK) return mock.fetchMe();
  return apiFetch('/api/me');
}

export async function fetchChannels(): Promise<Channel[]> {
  if (USE_MOCK) return mock.fetchChannels();
  return apiFetch('/api/channels');
}

export async function connectChannel(type: string): Promise<{ url: string }> {
  if (USE_MOCK) return mock.connectChannel(type);
  return apiFetch('/api/channels/connect', { method: 'POST', body: JSON.stringify({ type }) });
}

export async function reconnectChannel(id: string): Promise<{ url: string }> {
  if (USE_MOCK) return mock.reconnectChannel(id);
  return apiFetch(`/api/channels/${id}/reconnect`, { method: 'POST' });
}

export async function fetchConversations(cursor?: string): Promise<PaginatedResponse<Conversation>> {
  if (USE_MOCK) return mock.fetchConversations(cursor);
  const qs = cursor ? `?sinceCursor=${cursor}` : '';
  return apiFetch(`/api/conversations${qs}`);
}

export async function fetchMessages(conversationId: string, cursor?: string): Promise<PaginatedResponse<Message>> {
  if (USE_MOCK) return mock.fetchMessages(conversationId, cursor);
  const qs = cursor ? `?sinceCursor=${cursor}` : '';
  return apiFetch(`/api/conversations/${conversationId}/messages${qs}`);
}

export async function sendReply(conversationId: string, body: string): Promise<Message> {
  if (USE_MOCK) return mock.sendReply(conversationId, body);
  return apiFetch(`/api/conversations/${conversationId}/reply`, { method: 'POST', body: JSON.stringify({ body }) });
}

export async function fetchDraft(conversationId: string): Promise<Draft | null> {
  if (USE_MOCK) return mock.fetchDraft(conversationId);
  return apiFetch(`/api/conversations/${conversationId}/draft`);
}

export async function fetchLabels(): Promise<Label[]> {
  if (USE_MOCK) return mock.fetchLabels();
  return apiFetch('/api/labels');
}

export async function addLabel(conversationId: string, labelId: string): Promise<Label[]> {
  if (USE_MOCK) return mock.addLabel(conversationId, labelId);
  return apiFetch(`/api/conversations/${conversationId}/labels`, { method: 'POST', body: JSON.stringify({ labelId }) });
}
