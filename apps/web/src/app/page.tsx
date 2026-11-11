'use client';

import Link from 'next/link';
import { WithRail } from '@/components/ct/app-shell';
import { CommunityRules } from '@/components/ct/community-rules';
import { MissionCard } from '@/components/ct/mission-card';
import { Panel } from '@/components/ct/panel';
import { TrendingCaseCard, type TrendingCase } from '@/components/ct/trending-case-card';
import { errorMessage, type Schemas } from '@/lib/api/client';
import { daysSince, humanRegion } from '@/lib/case-display';
import { usePublicCases } from '@/lib/public-cases';

const SAMPLE = 50;
const TRENDING_DAYS = 10;

/** Exact when the first page holds everything, otherwise a floor ("50+"); the API has no totals. */
function countLabel(query: ReturnType<typeof usePublicCases>): string | undefined {
  const first = query.data?.pages[0];
  if (!first) return undefined;
  return first.hasNext ? `${SAMPLE}+` : String(first.content?.length ?? 0);
}

function toTrending(summary: Schemas['PublicCaseSummaryResponse']): TrendingCase {
  return {
    id: summary.id!,
    fullName: summary.fullName!,
    age: summary.age!,
    place: humanRegion(summary.region!),
    lastSeen: summary.lastSeenDate!,
    sightingsByDay: null, // no sightings API yet
  };
}

export default function HomePage() {
  const missing = usePublicCases({ status: 'MISSING' }, SAMPLE);
  const foundSafe = usePublicCases({ status: 'FOUND_SAFE' }, SAMPLE);

  const trending = (missing.data?.pages[0]?.content ?? [])
    .filter((c) => c.age! < 18 || daysSince(c.lastSeenDate!) < TRENDING_DAYS)
    .sort((a, b) => b.lastSeenDate!.localeCompare(a.lastSeenDate!))
    .slice(0, 3)
    .map(toTrending);

  return (
    <WithRail rail={<>
      <MissionCard stats={{ openCases: countLabel(missing), foundSafe: countLabel(foundSafe) }} />
      <CommunityRules />
    </>}>
      <Panel className="rounded-3xl">
        <div className="space-y-2 p-5">
          <h1 className="text-3xl font-semibold">Search status</h1>
          <p className="text-muted-foreground">
            Where every open case stands right now, and what the community has found today.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 border-y border-border bg-band px-5 py-3 text-sm text-muted-foreground">
          <span className="size-2 rounded-full bg-warning" aria-hidden />
          <h2 className="font-mono text-foreground">Top Trending Cases</h2>
          <span className="hidden sm:inline">· children and anyone missing under {TRENDING_DAYS} days</span>
        </div>

        {missing.isError && <p className="p-5 text-sm text-destructive">{errorMessage(missing.error)}</p>}
        {missing.isPending && (
          <div className="grid gap-px md:grid-cols-3" aria-busy>
            {[0, 1, 2].map((i) => <div key={i} className="m-4 h-56 animate-pulse rounded-xl bg-muted" />)}
          </div>
        )}
        {missing.isSuccess && trending.length === 0 && (
          <p className="p-5 text-sm text-muted-foreground">
            No children or recent cases right now. <Link href="/cases" className="text-info hover:underline">Browse every case</Link>.
          </p>
        )}
        {trending.length > 0 && (
          <ul className="flex snap-x snap-mandatory overflow-x-auto md:grid md:grid-cols-3 md:divide-x md:divide-border md:overflow-visible">
            {trending.map((item) => (
              <li key={item.id} className="w-[85%] shrink-0 snap-start border-r border-border last:border-r-0 md:w-auto md:border-r-0">
                <TrendingCaseCard item={item} />
              </li>
            ))}
          </ul>
        )}
      </Panel>
    </WithRail>
  );
}
