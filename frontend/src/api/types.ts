export type ConversationState = 'AI_HANDLING' | 'WAITING_HUMAN' | 'WITH_HUMAN' | 'CLOSED';
export type StateReason = 'QUOTE_REQUESTED' | 'HUMAN_REQUESTED' | 'AI_FAILED' | 'OUT_OF_SCOPE' | null;
export type ChannelType = 'whatsapp' | 'instagram' | 'sms' | 'email';
export type AssistMode = 'SILENT' | 'DRAFT' | 'AUTO';

export interface Me {
  userId: string;
  email: string;
  role: 'owner' | 'agent';
  tenantId: string;
  tenantName: string;
}

export interface Channel {
  id: string;
  type: ChannelType;
  status: 'pending' | 'connected' | 'error' | 'disabled';
  connectedAt?: string;
}

export interface Label {
  id: string;
  name: string;
  color: string;
}

export interface Contact {
  id: string;
  displayName: string;
  channelType: ChannelType;
}

export interface Conversation {
  id: string;
  contact: Contact;
  channelType: ChannelType;
  state: ConversationState;
  stateReason: StateReason;
  labels: Label[];
  lastMessageAt: string;
  lastMessagePreview: string;
  unread: boolean;
}

export interface Message {
  id: string;
  direction: 'inbound' | 'outbound';
  author: 'contact' | 'ai' | 'human';
  body: string;
  createdAt: string;
}

export interface Draft {
  id: string;
  body: string;
  reason?: string;
  status: 'suggested' | 'dismissed' | 'sent';
}

export interface PaginatedResponse<T> {
  items: T[];
  cursor: string | null;
}
