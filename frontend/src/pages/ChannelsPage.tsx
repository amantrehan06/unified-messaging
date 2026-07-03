import { useEffect, useState } from 'react';
import { Radio, CheckCircle, AlertCircle, Clock, XCircle, RefreshCw } from 'lucide-react';
import type { Channel, ChannelType } from '../api/types';
import { fetchChannels, connectChannel, reconnectChannel } from '../api/client';
import { ChannelBadge } from '../inbox/ChannelBadge';
import styles from './ChannelsPage.module.css';

const AVAILABLE_CHANNELS: { type: ChannelType; name: string; description: string }[] = [
  { type: 'whatsapp', name: 'WhatsApp', description: 'Connect your WhatsApp Business account' },
  { type: 'instagram', name: 'Instagram', description: 'Connect your Instagram professional account' },
  { type: 'sms', name: 'SMS', description: 'Connect an SMS number via Twilio' },
  { type: 'email', name: 'Email', description: 'Connect your business email' },
];

const STATUS_CONFIG: Record<Channel['status'], { icon: typeof CheckCircle; label: string; className: string }> = {
  connected: { icon: CheckCircle, label: 'Connected', className: 'statusConnected' },
  error: { icon: AlertCircle, label: 'Error', className: 'statusError' },
  pending: { icon: Clock, label: 'Pending', className: 'statusPending' },
  disabled: { icon: XCircle, label: 'Disabled', className: 'statusDisabled' },
};

export function ChannelsPage() {
  const [channels, setChannels] = useState<Channel[]>([]);

  useEffect(() => {
    fetchChannels().then(setChannels);
  }, []);

  const handleConnect = async (type: ChannelType) => {
    const { url } = await connectChannel(type);
    window.open(url, '_blank');
  };

  const handleReconnect = async (id: string) => {
    const { url } = await reconnectChannel(id);
    window.open(url, '_blank');
  };

  return (
    <div className={styles.page}>
      <div className={styles.header}>
        <Radio size={20} strokeWidth={2} />
        <h2 className={styles.title}>Channels</h2>
      </div>
      <p className={styles.subtitle}>
        Connect your messaging channels to start receiving conversations.
      </p>

      <div className={styles.grid}>
        {AVAILABLE_CHANNELS.map((ch) => {
          const existing = channels.find((c) => c.type === ch.type);
          const status = existing ? STATUS_CONFIG[existing.status] : null;
          const StatusIcon = status?.icon;

          return (
            <div key={ch.type} className={styles.card}>
              <div className={styles.cardHeader}>
                <ChannelBadge type={ch.type} showLabel />
                {status && StatusIcon && (
                  <span className={`${styles.statusChip} ${styles[status.className]}`}>
                    <StatusIcon size={14} strokeWidth={2} />
                    {status.label}
                  </span>
                )}
              </div>
              <p className={styles.cardDesc}>{ch.description}</p>
              <div className={styles.cardActions}>
                {!existing && (
                  <button className={styles.connectBtn} onClick={() => handleConnect(ch.type)}>
                    Connect
                  </button>
                )}
                {existing?.status === 'error' && (
                  <button className={styles.reconnectBtn} onClick={() => handleReconnect(existing.id)}>
                    <RefreshCw size={14} strokeWidth={2} /> Reconnect
                  </button>
                )}
                {existing?.status === 'connected' && existing.connectedAt && (
                  <span className={styles.connectedAt}>
                    Since {new Date(existing.connectedAt).toLocaleDateString()}
                  </span>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
