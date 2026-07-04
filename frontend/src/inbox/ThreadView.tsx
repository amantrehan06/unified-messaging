import { useEffect, useState, useRef } from 'react';
import { AlertTriangle, Bot, User, Tag, CheckCircle, Sparkles } from 'lucide-react';
import type { Conversation, Message, Draft, AssistMode } from '../api/types';
import { fetchMessages, fetchDraft, sendReply } from '../api/client';
import { ChannelBadge } from './ChannelBadge';
import { Composer } from './Composer';
import styles from './ThreadView.module.css';

interface ThreadViewProps {
  conversation: Conversation;
}

function formatMessageTime(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
}

function formatDaySeparator(iso: string): string {
  const d = new Date(iso);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) return 'Today';
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  if (d.toDateString() === yesterday.toDateString()) return 'Yesterday';
  return d.toLocaleDateString([], { weekday: 'long', month: 'long', day: 'numeric' });
}

function groupByDay(messages: Message[]): { day: string; messages: Message[] }[] {
  const groups: { day: string; messages: Message[] }[] = [];
  let currentDay = '';
  for (const msg of messages) {
    const day = new Date(msg.createdAt).toDateString();
    if (day !== currentDay) {
      currentDay = day;
      groups.push({ day: formatDaySeparator(msg.createdAt), messages: [] });
    }
    groups[groups.length - 1].messages.push(msg);
  }
  return groups;
}

const HANDOFF_TEXT =
  "This one's for you. Sarah asked for a price, so the AI stepped back instead of quoting. Reply below when you're ready.";

export function ThreadView({ conversation }: ThreadViewProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [draft, setDraft] = useState<Draft | null>(null);
  const [assistMode, setAssistMode] = useState<AssistMode>('SILENT');
  const [sending, setSending] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetchMessages(conversation.id).then((res) => setMessages(res.items));
    fetchDraft(conversation.id).then(setDraft);

    const interval = setInterval(() => {
      fetchMessages(conversation.id).then((res) => setMessages(res.items));
    }, 3_000);
    return () => clearInterval(interval);
  }, [conversation.id]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages]);

  // Idempotency key lives across retries: generated once per compose,
  // cleared only after a successful send.
  const idempotencyKeyRef = useRef(crypto.randomUUID());

  const handleSend = async (body: string) => {
    setSending(true);
    try {
      const msg = await sendReply(conversation.id, body, idempotencyKeyRef.current);
      setMessages((prev) => [...prev, msg]);
      setDraft(null);
      // New key for the next composed reply
      idempotencyKeyRef.current = crypto.randomUUID();
    } catch {
      // Key is intentionally NOT rotated so a retry reuses it
    } finally {
      setSending(false);
    }
  };

  const handleDismissDraft = () => {
    setDraft(null);
  };

  const showHandoffBanner = conversation.state === 'WAITING_HUMAN' && conversation.stateReason === 'QUOTE_REQUESTED';
  const dayGroups = groupByDay(messages);

  const primaryLabel = conversation.labels[0];

  return (
    <div className={styles.thread}>
      <div className={styles.header}>
        <div className={styles.headerAvatar}>
          {conversation.contact.displayName.charAt(0)}
          <ChannelBadge type={conversation.channelType} className={styles.headerBadge} />
        </div>
        <div className={styles.headerInfo}>
          <span className={styles.headerName}>{conversation.contact.displayName}</span>
          <span className={styles.headerSub}>
            <span className={styles.headerChannel}>
              via {conversation.channelType}
            </span>
          </span>
        </div>
        <div className={styles.headerActions}>
          {primaryLabel && (
            <span
              className={styles.headerTag}
              style={{ background: primaryLabel.color + '18', color: primaryLabel.color }}
            >
              {primaryLabel.name}
            </span>
          )}
          <button className={styles.headerIconBtn} title="Tags">
            <Tag size={15} strokeWidth={2} />
          </button>
          <button className={styles.headerIconBtn} title="Resolve">
            <CheckCircle size={15} strokeWidth={2} />
          </button>
        </div>
      </div>

      {showHandoffBanner && (
        <div className={styles.handoffBanner}>
          <AlertTriangle size={16} strokeWidth={2} />
          <span>
            <strong>Handed off to you</strong> - {HANDOFF_TEXT}
          </span>
        </div>
      )}

      <div className={styles.messages}>
        {dayGroups.map((group) => (
          <div key={group.day}>
            <div className={styles.daySeparator}>
              <span>{group.day}</span>
            </div>
            {group.messages.map((msg) => (
              <div
                key={msg.id}
                className={`${styles.bubble} ${msg.direction === 'outbound' ? styles.outbound : styles.inbound}`}
              >
                {msg.author === 'ai' && (
                  <span className={styles.authorTag}>
                    <Bot size={12} strokeWidth={2} /> AI
                  </span>
                )}
                {msg.author === 'human' && msg.direction === 'outbound' && (
                  <span className={styles.authorTag}>
                    <User size={12} strokeWidth={2} /> You
                  </span>
                )}
                <p className={styles.bubbleText}>{msg.body}</p>
                <span className={styles.bubbleTime}>{formatMessageTime(msg.createdAt)}</span>
              </div>
            ))}
          </div>
        ))}

        {showHandoffBanner && primaryLabel && (
          <div className={styles.systemEvent}>
            <span className={styles.systemEventPill}>
              <Sparkles size={13} strokeWidth={2} />
              AI replied - tagged as {primaryLabel.name}
            </span>
          </div>
        )}

        <div ref={bottomRef} />
      </div>

      <Composer
        draft={draft}
        assistMode={assistMode}
        onAssistModeChange={setAssistMode}
        onSend={handleSend}
        onDismissDraft={handleDismissDraft}
        sending={sending}
        channelType={conversation.channelType}
      />
    </div>
  );
}
