import { cn } from '@/lib/utils';

/** Daily sightings as bars (oldest first). Zero days show as a short dash; no data shows a note, not zeros. */
export function SightingsBars({ counts, className, height = 56 }: { counts: number[] | null; className?: string; height?: number }) {
  if (!counts) {
    return <p className={cn('text-xs text-text-subtle', className)}>Sightings data isn’t available yet.</p>;
  }
  const max = Math.max(1, ...counts);
  return (
    <div className={cn('flex items-end gap-1.5', className)} style={{ height }}
      role="img" aria-label={`Sightings per day over ${counts.length} days, ${counts.reduce((a, b) => a + b, 0)} in total`}>
      {counts.map((count, index) => count === 0
        ? <span key={index} className="h-[3px] flex-1 rounded-full bg-secondary" />
        : <span key={index} className="flex-1 rounded-[4px] bg-info" style={{ height: `${Math.max(14, (count / max) * 100)}%` }} />)}
    </div>
  );
}
