import { useEffect, useState } from 'react';
import type { Conversation, Label } from '../api/types';
import { fetchConversations, fetchLabels } from '../api/client';
import { Rail } from '../inbox/Rail';
import { ConversationList } from '../inbox/ConversationList';
import { ThreadView } from '../inbox/ThreadView';
import { ChannelsPage } from './ChannelsPage';
import styles from './InboxPage.module.css';

export function InboxPage() {
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [labels, setLabels] = useState<Label[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [activeLabelId, setActiveLabelId] = useState<string | null>(null);
  const [activeView, setActiveView] = useState<'inbox' | 'channels'>('inbox');

  useEffect(() => {
    fetchConversations().then((res) => setConversations(res.items));
    fetchLabels().then(setLabels);
  }, []);

  // Polling
  useEffect(() => {
    const interval = setInterval(() => {
      fetchConversations().then((res) => setConversations(res.items));
    }, 10_000);
    return () => clearInterval(interval);
  }, []);

  const waitingCount = conversations.filter((c) => c.state === 'WAITING_HUMAN').length;
  const selected = conversations.find((c) => c.id === selectedId) ?? null;

  return (
    <div className={styles.layout}>
      <Rail
        waitingCount={waitingCount}
        activeView={activeView}
        onNavigate={setActiveView}
      />
      {activeView === 'inbox' ? (
        <>
          <ConversationList
            conversations={conversations}
            selectedId={selectedId}
            onSelect={setSelectedId}
            labels={labels}
            activeLabelId={activeLabelId}
            onFilterLabel={setActiveLabelId}
          />
          <div className={styles.threadPane}>
            {selected ? (
              <ThreadView conversation={selected} />
            ) : (
              <div className={styles.emptyState}>
                <p>Select a conversation to get started</p>
              </div>
            )}
          </div>
        </>
      ) : (
        <div className={styles.fullPane}>
          <ChannelsPage />
        </div>
      )}
    </div>
  );
}
