import { MessageCircle, Plus, Search } from 'lucide-react';
import { useState } from 'react';
import type { Conversation, Label } from '../api/types';
import { ChannelBadge } from './ChannelBadge';
import { StateReasonPill } from './StateReasonPill';
import styles from './ConversationList.module.css';

interface ConversationListProps {
  conversations: Conversation[];
  selectedId: string | null;
  onSelect: (id: string) => void;
  labels: Label[];
  activeLabelId: string | null;
  onFilterLabel: (labelId: string | null) => void;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  const now = new Date();
  if (d.toDateString() === now.toDateString()) {
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  }
  return d.toLocaleDateString([], { month: 'short', day: 'numeric' });
}

const LABEL_EMOJI: Record<string, string> = {
  'Hot lead': '\uD83D\uDD25',
  'VIP': '\u2B50',
  'New': '\u2728',
};

export function ConversationList({
  conversations,
  selectedId,
  onSelect,
  labels,
  activeLabelId,
  onFilterLabel,
}: ConversationListProps) {
  const [search, setSearch] = useState('');

  const searched = search.trim()
    ? conversations.filter((c) =>
        c.contact.displayName.toLowerCase().includes(search.trim().toLowerCase()),
      )
    : conversations;

  const filtered = activeLabelId
    ? searched.filter((c) => c.labels.some((l) => l.id === activeLabelId))
    : searched;

  const waiting = filtered.filter((c) => c.state === 'WAITING_HUMAN');
  const aiHandling = filtered.filter((c) => c.state === 'AI_HANDLING');
  const closed = filtered.filter((c) => c.state === 'CLOSED');

  return (
    <div className={styles.panel}>
      <div className={styles.header}>
        <div className={styles.titleRow}>
          <h2 className={styles.title}>
            <MessageCircle size={18} strokeWidth={2} />
            Inbox
          </h2>
          <button className={styles.newBtn} title="New conversation">
            <Plus size={16} strokeWidth={2} />
          </button>
        </div>
        <div className={styles.searchBar}>
          <Search size={15} strokeWidth={2} className={styles.searchIcon} />
          <input
            className={styles.searchInput}
            placeholder="Search conversations..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
      </div>

      <div className={styles.filters}>
        <button
          className={`${styles.filterChip} ${!activeLabelId ? styles.activeFilter : ''}`}
          onClick={() => onFilterLabel(null)}
        >
          All
        </button>
        {labels.map((label) => (
          <button
            key={label.id}
            className={`${styles.filterChip} ${activeLabelId === label.id ? styles.activeFilter : ''}`}
            onClick={() => onFilterLabel(label.id)}
          >
            <span className={styles.labelDot} style={{ background: label.color }} />
            {label.name}
          </button>
        ))}
      </div>

      <div className={styles.list}>
        {waiting.length > 0 && (
          <>
            <div className={styles.sectionHeader}>
              <span className={styles.sectionDot} />
              Waiting for you
              <span className={styles.sectionCount}>{waiting.length}</span>
            </div>
            {waiting.map((conv) => (
              <ConversationItem
                key={conv.id}
                conversation={conv}
                selected={conv.id === selectedId}
                onSelect={onSelect}
                isWaiting
              />
            ))}
          </>
        )}

        {aiHandling.length > 0 && (
          <>
            <div className={styles.sectionHeader}>AI handling</div>
            {aiHandling.map((conv) => (
              <ConversationItem
                key={conv.id}
                conversation={conv}
                selected={conv.id === selectedId}
                onSelect={onSelect}
              />
            ))}
          </>
        )}

        {closed.length > 0 && (
          <>
            <div className={styles.sectionHeader}>Closed</div>
            {closed.map((conv) => (
              <ConversationItem
                key={conv.id}
                conversation={conv}
                selected={conv.id === selectedId}
                onSelect={onSelect}
              />
            ))}
          </>
        )}
      </div>
    </div>
  );
}

function ConversationItem({
  conversation: conv,
  selected,
  onSelect,
  isWaiting,
}: {
  conversation: Conversation;
  selected: boolean;
  onSelect: (id: string) => void;
  isWaiting?: boolean;
}) {
  const itemClass = [
    styles.item,
    selected ? styles.selected : '',
    conv.unread ? styles.unread : '',
    isWaiting ? styles.waitingItem : '',
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <button className={itemClass} onClick={() => onSelect(conv.id)}>
      <div className={styles.avatar}>
        {conv.contact.displayName.charAt(0)}
        <ChannelBadge type={conv.channelType} className={styles.channelBadge} />
      </div>
      <div className={styles.itemContent}>
        <div className={styles.itemTop}>
          <span className={styles.contactName}>{conv.contact.displayName}</span>
          <span className={styles.time}>{formatTime(conv.lastMessageAt)}</span>
        </div>
        <div className={styles.itemBottom}>
          <span className={styles.preview}>{conv.lastMessagePreview}</span>
          {conv.unread && <span className={styles.unreadDot} />}
        </div>
        <div className={styles.itemMeta}>
          {conv.stateReason && <StateReasonPill reason={conv.stateReason} />}
          {conv.labels.map((label) => (
            <span
              key={label.id}
              className={styles.labelChip}
              style={{ background: label.color + '18', color: label.color }}
            >
              {LABEL_EMOJI[label.name] ? `${LABEL_EMOJI[label.name]} ` : ''}
              {label.name}
            </span>
          ))}
        </div>
      </div>
    </button>
  );
}
