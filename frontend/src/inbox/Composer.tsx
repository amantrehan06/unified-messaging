import { useState } from 'react';
import { Send, Sparkles, X, Pencil } from 'lucide-react';
import type { Draft, AssistMode, ChannelType } from '../api/types';
import styles from './Composer.module.css';

const CHANNEL_COLORS: Record<ChannelType, string> = {
  whatsapp: '#25D366',
  instagram: '#E4405F',
  sms: '#6366F1',
  email: '#0EA5E9',
};

const CHANNEL_LABELS: Record<ChannelType, string> = {
  whatsapp: 'WhatsApp',
  instagram: 'Instagram',
  sms: 'SMS',
  email: 'Email',
};

interface ComposerProps {
  draft: Draft | null;
  assistMode: AssistMode;
  onAssistModeChange: (mode: AssistMode) => void;
  onSend: (body: string) => void;
  onDismissDraft: () => void;
  sending: boolean;
  channelType: ChannelType;
}

const ASSIST_MODES: AssistMode[] = ['SILENT', 'DRAFT', 'AUTO'];

export function Composer({ draft, assistMode, onAssistModeChange, onSend, onDismissDraft, sending, channelType }: ComposerProps) {
  const [text, setText] = useState('');

  const handleSend = () => {
    const body = text.trim();
    if (!body) return;
    onSend(body);
    setText('');
  };

  const handleSendDraft = () => {
    if (draft) {
      onSend(draft.body);
    }
  };

  const handleEditDraft = () => {
    if (draft) {
      setText(draft.body);
      onDismissDraft();
    }
  };

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSend();
    }
  };

  return (
    <div className={styles.composer}>
      {assistMode === 'DRAFT' && (
        <div className={styles.assistNote}>
          <Sparkles size={13} strokeWidth={2} className={styles.assistNoteIcon} />
          AI drafts, you send. It won't quote prices on its own.
        </div>
      )}

      {draft && draft.status === 'suggested' && (
        <div className={styles.draftCard}>
          <div className={styles.draftHeader}>
            <span className={styles.draftBadge}>
              <Sparkles size={11} strokeWidth={2.5} />
              Suggested reply
            </span>
            {draft.reason && <span className={styles.draftReason}>{draft.reason}</span>}
          </div>
          <p className={styles.draftBody}>{draft.body}</p>
          <div className={styles.draftActions}>
            <button className={styles.draftSend} onClick={handleSendDraft}>
              <Send size={14} strokeWidth={2} /> Send this
            </button>
            <button className={styles.draftEdit} onClick={handleEditDraft}>
              <Pencil size={14} strokeWidth={2} /> Edit first
            </button>
            <button className={styles.draftDismiss} onClick={onDismissDraft}>
              <X size={14} strokeWidth={2} /> Dismiss
            </button>
          </div>
        </div>
      )}

      <div className={styles.inputRow}>
        <div className={styles.modeToggle}>
          {ASSIST_MODES.map((mode) => (
            <button
              key={mode}
              className={`${styles.modeBtn} ${assistMode === mode ? styles.modeActive : ''}`}
              onClick={() => onAssistModeChange(mode)}
            >
              {mode.charAt(0) + mode.slice(1).toLowerCase()}
            </button>
          ))}
        </div>
        <div className={styles.inputWrap}>
          <button
            className={styles.channelPill}
            style={{ background: CHANNEL_COLORS[channelType] }}
          >
            {CHANNEL_LABELS[channelType]}
          </button>
          <textarea
            className={styles.input}
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={handleKeyDown}
            placeholder="Type a reply..."
            rows={1}
          />
        </div>
        <button
          className={styles.sendBtn}
          onClick={handleSend}
          disabled={!text.trim() || sending}
        >
          <Send size={18} strokeWidth={2} />
        </button>
      </div>
    </div>
  );
}
