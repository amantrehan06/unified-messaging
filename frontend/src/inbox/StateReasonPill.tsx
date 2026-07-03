import type { StateReason } from '../api/types';
import styles from './StateReasonPill.module.css';

const REASON_LABELS: Record<NonNullable<StateReason>, string> = {
  QUOTE_REQUESTED: 'Quote requested',
  HUMAN_REQUESTED: 'Human requested',
  AI_FAILED: 'AI needs help',
  OUT_OF_SCOPE: 'Out of scope',
};

export function StateReasonPill({ reason }: { reason: NonNullable<StateReason> }) {
  return (
    <span className={styles.pill}>
      {REASON_LABELS[reason]}
    </span>
  );
}
