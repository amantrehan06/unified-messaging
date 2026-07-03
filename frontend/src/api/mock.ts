import type {
  Me,
  Channel,
  Label,
  Conversation,
  Message,
  Draft,
  PaginatedResponse,
} from './types';

const MOCK_DELAY = 300;

function delay<T>(value: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), MOCK_DELAY));
}

const me: Me = {
  userId: 'u_demo',
  email: 'demo@acme.com',
  role: 'owner',
  tenantId: 't_acme',
  tenantName: 'Acme Corp',
};

const labels: Label[] = [
  { id: 'lbl_hot', name: 'Hot leads', color: '#D8453C' },
  { id: 'lbl_new', name: 'New leads', color: '#4F46E5' },
  { id: 'lbl_follow', name: 'Follow-up', color: '#C6820E' },
  { id: 'lbl_closed', name: 'Closed', color: '#8A93A8' },
];

const conversations: Conversation[] = [
  {
    id: 'conv_1',
    contact: { id: 'c_1', displayName: 'Sarah Chen', channelType: 'whatsapp' },
    channelType: 'whatsapp',
    state: 'WAITING_HUMAN',
    stateReason: 'QUOTE_REQUESTED',
    labels: [labels[0]],
    lastMessageAt: '2026-07-03T10:30:00Z',
    lastMessagePreview: 'Can I get a quote for 500 units?',
    unread: true,
  },
  {
    id: 'conv_2',
    contact: { id: 'c_2', displayName: 'James Rodriguez', channelType: 'instagram' },
    channelType: 'instagram',
    state: 'WAITING_HUMAN',
    stateReason: 'HUMAN_REQUESTED',
    labels: [labels[0], labels[2]],
    lastMessageAt: '2026-07-03T09:45:00Z',
    lastMessagePreview: 'I want to speak to a person please',
    unread: true,
  },
  {
    id: 'conv_3',
    contact: { id: 'c_3', displayName: 'Emily Watson', channelType: 'whatsapp' },
    channelType: 'whatsapp',
    state: 'WAITING_HUMAN',
    stateReason: 'AI_FAILED',
    labels: [labels[1]],
    lastMessageAt: '2026-07-03T09:15:00Z',
    lastMessagePreview: 'My order #4521 arrived damaged',
    unread: false,
  },
  {
    id: 'conv_4',
    contact: { id: 'c_4', displayName: 'Michael Park', channelType: 'sms' },
    channelType: 'sms',
    state: 'AI_HANDLING',
    stateReason: null,
    labels: [labels[1]],
    lastMessageAt: '2026-07-03T08:20:00Z',
    lastMessagePreview: 'What are your business hours?',
    unread: false,
  },
  {
    id: 'conv_5',
    contact: { id: 'c_5', displayName: 'Priya Sharma', channelType: 'email' },
    channelType: 'email',
    state: 'AI_HANDLING',
    stateReason: null,
    labels: [labels[2]],
    lastMessageAt: '2026-07-03T07:55:00Z',
    lastMessagePreview: 'Thanks for the info! I will review and get back.',
    unread: false,
  },
  {
    id: 'conv_6',
    contact: { id: 'c_6', displayName: 'Alex Turner', channelType: 'whatsapp' },
    channelType: 'whatsapp',
    state: 'CLOSED',
    stateReason: null,
    labels: [labels[3]],
    lastMessageAt: '2026-07-02T16:00:00Z',
    lastMessagePreview: 'Great, thanks for your help!',
    unread: false,
  },
];

const messagesByConversation: Record<string, Message[]> = {
  conv_1: [
    { id: 'm_1a', direction: 'inbound', author: 'contact', body: 'Hi! I saw your product listing for the ceramic mugs.', createdAt: '2026-07-03T10:00:00Z' },
    { id: 'm_1b', direction: 'outbound', author: 'ai', body: 'Hello Sarah! Thanks for reaching out. Yes, our ceramic mugs are available in multiple colors. How can I help you today?', createdAt: '2026-07-03T10:05:00Z' },
    { id: 'm_1c', direction: 'inbound', author: 'contact', body: 'Can I get a quote for 500 units? We need them for a corporate event.', createdAt: '2026-07-03T10:15:00Z' },
    { id: 'm_1d', direction: 'outbound', author: 'ai', body: 'That sounds like a great project! For bulk orders of 500+ units, I will need to connect you with our sales team for a custom quote. Let me get someone for you.', createdAt: '2026-07-03T10:20:00Z' },
  ],
  conv_2: [
    { id: 'm_2a', direction: 'inbound', author: 'contact', body: 'Hey, I ordered something last week and it has not arrived yet.', createdAt: '2026-07-03T09:00:00Z' },
    { id: 'm_2b', direction: 'outbound', author: 'ai', body: 'I am sorry to hear about the delay! Could you share your order number so I can look into this?', createdAt: '2026-07-03T09:10:00Z' },
    { id: 'm_2c', direction: 'inbound', author: 'contact', body: 'I want to speak to a person please', createdAt: '2026-07-03T09:45:00Z' },
  ],
  conv_3: [
    { id: 'm_3a', direction: 'inbound', author: 'contact', body: 'My order #4521 arrived damaged. The box was crushed and two items are broken.', createdAt: '2026-07-03T09:00:00Z' },
    { id: 'm_3b', direction: 'outbound', author: 'ai', body: 'I am very sorry about the damaged order. Let me connect you with our support team to arrange a replacement right away.', createdAt: '2026-07-03T09:10:00Z' },
  ],
  conv_4: [
    { id: 'm_4a', direction: 'inbound', author: 'contact', body: 'What are your business hours?', createdAt: '2026-07-03T08:00:00Z' },
    { id: 'm_4b', direction: 'outbound', author: 'ai', body: 'We are open Monday through Friday, 9 AM to 6 PM EST. On weekends we are available 10 AM to 4 PM. Is there anything else I can help with?', createdAt: '2026-07-03T08:05:00Z' },
  ],
  conv_5: [
    { id: 'm_5a', direction: 'inbound', author: 'contact', body: 'Hi, I am interested in your enterprise plan. Can you send me the details?', createdAt: '2026-07-03T07:30:00Z' },
    { id: 'm_5b', direction: 'outbound', author: 'ai', body: 'Of course! Our enterprise plan includes unlimited seats, priority support, custom integrations, and a dedicated account manager. I have sent the full breakdown to your email.', createdAt: '2026-07-03T07:35:00Z' },
    { id: 'm_5c', direction: 'inbound', author: 'contact', body: 'Thanks for the info! I will review and get back.', createdAt: '2026-07-03T07:55:00Z' },
  ],
  conv_6: [
    { id: 'm_6a', direction: 'inbound', author: 'contact', body: 'I need to update my billing address.', createdAt: '2026-07-02T15:30:00Z' },
    { id: 'm_6b', direction: 'outbound', author: 'human', body: 'Sure thing! I have updated your billing address. You should see the change reflected in your next invoice.', createdAt: '2026-07-02T15:45:00Z' },
    { id: 'm_6c', direction: 'inbound', author: 'contact', body: 'Great, thanks for your help!', createdAt: '2026-07-02T16:00:00Z' },
  ],
};

const draftByConversation: Record<string, Draft> = {
  conv_2: {
    id: 'draft_2',
    body: 'I understand you would like to speak with a team member. Let me connect you with one of our support agents who can help with your order tracking right away.',
    reason: 'Customer explicitly requested human assistance',
    status: 'suggested',
  },
};

const channels: Channel[] = [
  { id: 'ch_1', type: 'whatsapp', status: 'connected', connectedAt: '2026-06-15T10:00:00Z' },
  { id: 'ch_2', type: 'instagram', status: 'connected', connectedAt: '2026-06-20T14:00:00Z' },
  { id: 'ch_3', type: 'sms', status: 'error' },
  { id: 'ch_4', type: 'email', status: 'pending' },
];

export const mock = {
  fetchMe: () => delay(me),

  fetchChannels: () => delay(channels),

  connectChannel: (_type: string) => delay({ url: 'https://mock-oauth.example.com/connect' }),

  reconnectChannel: (_id: string) => delay({ url: 'https://mock-oauth.example.com/reconnect' }),

  fetchConversations: (_cursor?: string): Promise<PaginatedResponse<Conversation>> =>
    delay({ items: conversations, cursor: null }),

  fetchMessages: (conversationId: string, _cursor?: string): Promise<PaginatedResponse<Message>> =>
    delay({ items: messagesByConversation[conversationId] ?? [], cursor: null }),

  sendReply: (conversationId: string, body: string): Promise<Message> => {
    const msg: Message = {
      id: `m_new_${Date.now()}`,
      direction: 'outbound',
      author: 'human',
      body,
      createdAt: new Date().toISOString(),
    };
    const msgs = messagesByConversation[conversationId];
    if (msgs) msgs.push(msg);
    return delay(msg);
  },

  fetchDraft: (conversationId: string): Promise<Draft | null> =>
    delay(draftByConversation[conversationId] ?? null),

  fetchLabels: () => delay(labels),

  addLabel: (_conversationId: string, _labelId: string): Promise<Label[]> =>
    delay(labels),
};
