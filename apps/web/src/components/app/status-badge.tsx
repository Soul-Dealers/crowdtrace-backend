import { Badge } from '@/components/ui/badge';
import { humanize } from '@/lib/enums';

const VARIANT: Record<string, 'default' | 'secondary' | 'destructive' | 'outline'> = {
  MISSING: 'destructive',
  FOUND_SAFE: 'default',
  FOUND_DECEASED: 'secondary',
  CLOSED: 'outline',
};

export function StatusBadge({ status }: { status?: string | null }) {
  if (!status) return null;
  return <Badge variant={VARIANT[status] ?? 'outline'}>{humanize(status)}</Badge>;
}
