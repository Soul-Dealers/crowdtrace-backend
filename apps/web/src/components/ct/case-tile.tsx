import Link from 'next/link';
import { caseKindLabel, daysSince, formatShortDate, humanRegion } from '@/lib/case-display';
import { CasePhoto } from './case-photo';

export type CaseTileData = {
  id: number;
  fullName: string;
  age: number;
  region: string;
  lastSeenDate: string;
  caseStatus: 'MISSING' | 'FOUND_SAFE' | 'FOUND_DECEASED' | 'CLOSED';
  thumbnailPhotoId?: number | null;
};

const OUTCOME: Record<Exclude<CaseTileData['caseStatus'], 'MISSING'>, { label: string; className: string }> = {
  FOUND_SAFE: { label: 'Found safe', className: 'text-success' },
  FOUND_DECEASED: { label: 'Found deceased', className: 'text-text-secondary' },
  CLOSED: { label: 'Closed', className: 'text-text-secondary' },
};

/** Browse grid tile: photo, status line, name, age · region · last seen. */
export function CaseTile({ item }: { item: CaseTileData }) {
  const status = item.caseStatus === 'MISSING'
    ? { label: caseKindLabel(item.age), className: 'text-brand' }
    : OUTCOME[item.caseStatus];
  return (
    <Link href={`/cases/${item.id}`} className="group block space-y-2">
      <CasePhoto caseId={item.id} photoId={item.thumbnailPhotoId} alt={item.fullName}
        className="aspect-[5/4] transition-transform group-hover:scale-[1.01]" />
      <div className="text-sm">
        <span className={status.className}>{status.label}</span>
        {item.caseStatus === 'MISSING' && <span className="text-muted-foreground"> · Day {daysSince(item.lastSeenDate)}</span>}
      </div>
      <div className="font-semibold leading-tight">{item.fullName}</div>
      <div className="text-sm text-text-secondary">
        {item.age} · {humanRegion(item.region)}
        <span className="hidden sm:inline"> · Last seen {formatShortDate(item.lastSeenDate)}</span>
      </div>
    </Link>
  );
}
