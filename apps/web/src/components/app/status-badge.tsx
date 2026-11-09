import { Pill, type PillTone } from '@/components/ct/pill';
import { humanize } from '@/lib/enums';

const TONE: Record<string, PillTone> = {
  MISSING: 'danger',
  FOUND_SAFE: 'success',
  FOUND_DECEASED: 'neutral',
  CLOSED: 'neutral',
  SUBMITTED: 'neutral',
  UNDER_REVIEW: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
  TAKEN_DOWN: 'danger',
  PENDING: 'warning',
  REVOKED: 'danger',
};

/** Case outcome, review status or verification status as a themed pill. */
export function StatusBadge({ status }: { status?: string | null }) {
  if (!status) return null;
  return <Pill tone={TONE[status] ?? 'neutral'}>{humanize(status)}</Pill>;
}
