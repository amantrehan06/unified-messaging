import { Inbox, Radio, Settings } from 'lucide-react';
import { useAuth } from '../auth/AuthContext';
import styles from './Rail.module.css';

interface RailProps {
  waitingCount: number;
  activeView: 'inbox' | 'channels';
  onNavigate: (view: 'inbox' | 'channels') => void;
}

export function Rail({ waitingCount, activeView, onNavigate }: RailProps) {
  const { logout } = useAuth();

  return (
    <nav className={styles.rail}>
      <div className={styles.logo}>
        <span className={styles.logoDot} />
        <span className={styles.logoPulse} />
      </div>

      <div className={styles.navItems}>
        <button
          className={`${styles.navBtn} ${activeView === 'inbox' ? styles.active : ''}`}
          onClick={() => onNavigate('inbox')}
          title="Inbox"
        >
          <Inbox size={20} strokeWidth={2} />
          {waitingCount > 0 && (
            <span className={styles.badge}>{waitingCount}</span>
          )}
        </button>
        <button
          className={`${styles.navBtn} ${activeView === 'channels' ? styles.active : ''}`}
          onClick={() => onNavigate('channels')}
          title="Channels"
        >
          <Radio size={20} strokeWidth={2} />
        </button>
        <button className={styles.navBtn} title="Settings">
          <Settings size={20} strokeWidth={2} />
        </button>
      </div>

      <div className={styles.bottom}>
        <button className={styles.avatarBtn} onClick={logout} title="Sign out">
          Y
        </button>
      </div>
    </nav>
  );
}
