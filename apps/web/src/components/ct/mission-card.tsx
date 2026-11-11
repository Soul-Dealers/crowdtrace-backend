import Link from 'next/link';
import { Megaphone } from 'lucide-react';

/** Numbers or short labels such as "50+"; unknown values render as a dash, never as zero. */
export type MissionStats = { openCases?: number | string; foundSafe?: number | string; sightings?: number | string };

function Stat({ value, label }: { value?: number | string; label: string }) {
  return (
    <div className="text-center">
      <div className="text-2xl font-semibold">{value ?? '—'}</div>
      <div className="mt-1 text-sm text-[#ffd2b8]/80">{label}</div>
    </div>
  );
}

/** Right-rail pitch with headline stats and the main call to action. Unknown stats show a dash, not zero. */
export function MissionCard({ stats }: { stats: MissionStats }) {
  return (
    <section className="overflow-hidden rounded-2xl border border-border bg-panel">
      <div className="space-y-2 p-5 pb-4">
        <h2 className="text-xl font-semibold leading-tight">
          Every Case Reviewed.<br /><span className="text-brand">Every Lead Heard</span>
        </h2>
        <p className="text-sm text-muted-foreground">
          A moderated community helping families find missing loved ones across all 16 regions
        </p>
      </div>
      <div className="grid grid-cols-3 border-t border-white/10 px-2 pt-4">
        <Stat value={stats.openCases} label="Open Cases" />
        <Stat value={stats.foundSafe} label="Found Safe" />
        <Stat value={stats.sightings} label="Sightings" />
      </div>
      <div className="p-5 pt-6">
        <Link href="/report"
          className="flex items-center justify-center gap-2 rounded-xl bg-white px-4 py-3 text-base font-semibold text-black shadow-lg hover:bg-white/90">
          <Megaphone className="size-5 text-brand" /> Report A Missing Person
        </Link>
      </div>
    </section>
  );
}
