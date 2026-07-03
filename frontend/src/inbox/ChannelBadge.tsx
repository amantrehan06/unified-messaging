import { MessageCircle, Camera, Mail, Smartphone } from 'lucide-react';
import type { ChannelType } from '../api/types';
import styles from './ChannelBadge.module.css';

const CHANNEL_CONFIG: Record<ChannelType, { icon: typeof MessageCircle; bg: string; color: string; label: string }> = {
  whatsapp: { icon: MessageCircle, bg: '#25D366', color: '#fff', label: 'WhatsApp' },
  instagram: { icon: Camera, bg: '#E4405F', color: '#fff', label: 'Instagram' },
  sms: { icon: Smartphone, bg: '#6366F1', color: '#fff', label: 'SMS' },
  email: { icon: Mail, bg: '#0EA5E9', color: '#fff', label: 'Email' },
};

interface ChannelBadgeProps {
  type: ChannelType;
  className?: string;
  showLabel?: boolean;
}

export function ChannelBadge({ type, className, showLabel }: ChannelBadgeProps) {
  const config = CHANNEL_CONFIG[type];
  const Icon = config.icon;

  return (
    <span
      className={`${styles.badge} ${className ?? ''}`}
      style={{ background: config.bg, color: config.color }}
      title={config.label}
    >
      <Icon size={12} strokeWidth={2.5} />
      {showLabel && <span className={styles.label}>{config.label}</span>}
    </span>
  );
}
