import Link from 'next/link';
import { AvatarInitials } from './avatar-initials';
import { FlipClock } from './flip-clock';
import { Pill } from './pill';
import { SightingsBars } from './sightings-bars';

export type TrendingCase = {
  id: number;
  fullName: string;
  age: number;
  place: string;
  lastSeen: string;
  sightingsByDay: number[] | null;
};

export function TrendingCaseCard({ item }: { item: TrendingCase }) {
  const total = item.sightingsByDay?.reduce((a, b) => a + b, 0);
  return (
    <Link href={`/cases/${item.id}`} className="block space-y-5 p-4 transition-colors hover:bg-white/[0.02]">
      <div className="flex items-center gap-3">
        <AvatarInitials name={item.fullName} />
        <div className="min-w-0">
          <div className="truncate font-medium">{item.fullName}</div>
          <div className="truncate text-sm text-text-secondary">{item.age} · {item.place}</div>
        </div>
      </div>
      <FlipClock since={item.lastSeen} />
      <SightingsBars counts={item.sightingsByDay} />
      <div className="flex items-center justify-between text-sm">
        {item.age < 18 ? <Pill tone="danger">Under 18</Pill> : <span />}
        {total != null && <span className="text-muted-foreground"><b className="font-semibold text-foreground">{total}</b> Sightings</span>}
      </div>
    </Link>
  );
}
