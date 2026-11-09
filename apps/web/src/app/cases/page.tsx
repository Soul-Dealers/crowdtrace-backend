'use client';

import { Search } from 'lucide-react';
import { usePathname, useRouter, useSearchParams } from 'next/navigation';
import { Suspense, useEffect, useState } from 'react';
import { CaseTile } from '@/components/ct/case-tile';
import { Segmented } from '@/components/ct/segmented';
import { errorMessage } from '@/lib/api/client';
import { humanRegion } from '@/lib/case-display';
import { CASE_STATUSES, REGIONS } from '@/lib/enums';
import { toTile, usePublicCases, type PublicCaseFilters } from '@/lib/public-cases';

const STATUS_OPTIONS = [
  { value: 'ALL', label: 'All' },
  { value: 'MISSING', label: 'Missing' },
  { value: 'FOUND_SAFE', label: 'Found Safe' },
  { value: 'FOUND_DECEASED', label: 'Found Deceased' },
  { value: 'CLOSED', label: 'Closed' },
] as const;

const AGES = { any: {}, child: { ageMax: 17 }, adult: { ageMin: 18 } } as const;
type AgeKey = keyof typeof AGES;

function pick<T extends readonly string[]>(values: T, raw: string | null): T[number] | undefined {
  return values.includes(raw ?? '') ? (raw as T[number]) : undefined;
}

const selectClass = 'h-9 rounded-full border border-border bg-[#191919] px-3 text-sm text-text-secondary focus:outline-none focus:ring-1 focus:ring-brand';

function BrowseCases() {
  const params = useSearchParams();
  const router = useRouter();
  const pathname = usePathname();

  const status = pick(CASE_STATUSES, params.get('status'));
  const region = pick(REGIONS, params.get('region'));
  const age = (pick(['any', 'child', 'adult'] as const, params.get('age')) ?? 'any') as AgeKey;
  const name = params.get('name') ?? '';
  const [draft, setDraft] = useState(name);
  useEffect(() => setDraft(name), [name]);

  function update(next: Record<string, string | undefined>) {
    const query = new URLSearchParams(params);
    for (const [key, value] of Object.entries(next)) {
      if (value) query.set(key, value);
      else query.delete(key);
    }
    router.replace(`${pathname}${query.size ? `?${query}` : ''}`, { scroll: false });
  }

  // The API needs 3+ characters to search by name; shorter input is ignored rather than rejected.
  const filters: PublicCaseFilters = { status, region, ...AGES[age], name: name.trim().length >= 3 ? name.trim() : undefined };
  const query = usePublicCases(filters);
  const cases = query.data?.pages.flatMap((page) => page.content ?? []) ?? [];

  return (
    <div className="space-y-5">
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold sm:text-3xl">Everyone on CrowdTrace</h1>
        <p className="text-muted-foreground">
          People reported missing in Ghana, and what happened to them. Each case was checked against a police
          report before it was published.
        </p>
      </header>

      <div className="flex flex-wrap items-center gap-2 border-b border-border pb-4">
        <form role="search" className="w-full sm:w-auto" onSubmit={(event) => {
          event.preventDefault();
          update({ name: draft.trim() || undefined });
        }}>
          <label className="flex h-9 items-center gap-2 rounded-full border border-border bg-muted px-3 text-sm sm:w-56">
            <Search className="size-4 text-muted-foreground" aria-hidden />
            <span className="sr-only">Search case or person</span>
            <input value={draft} onChange={(event) => setDraft(event.target.value)} placeholder="Search case or person"
              className="min-w-0 flex-1 bg-transparent placeholder:text-muted-foreground focus:outline-none" />
          </label>
        </form>
        <div className="max-w-full overflow-x-auto">
          <Segmented label="Case status" options={[...STATUS_OPTIONS]} value={status ?? 'ALL'}
            onChange={(value) => update({ status: value === 'ALL' ? undefined : value })} />
        </div>
        <select aria-label="Region" className={selectClass} value={region ?? ''}
          onChange={(event) => update({ region: event.target.value || undefined })}>
          <option value="">All 16 Regions</option>
          {REGIONS.map((r) => <option key={r} value={r}>{humanRegion(r)}</option>)}
        </select>
        <select aria-label="Age" className={selectClass} value={age}
          onChange={(event) => update({ age: event.target.value === 'any' ? undefined : event.target.value })}>
          <option value="any">Any Age</option>
          <option value="child">Under 18</option>
          <option value="adult">18 and over</option>
        </select>
      </div>

      {name.trim().length > 0 && name.trim().length < 3 && (
        <p className="text-sm text-muted-foreground">Type at least 3 characters to search by name.</p>
      )}
      {query.isError && <p className="text-sm text-destructive">{errorMessage(query.error)}</p>}
      {query.isPending && (
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4" aria-busy>
          {Array.from({ length: 8 }, (_, i) => <div key={i} className="aspect-[5/4] animate-pulse rounded-2xl bg-muted" />)}
        </div>
      )}
      {query.isSuccess && cases.length === 0 && (
        <p className="py-10 text-center text-muted-foreground">No cases match these filters.</p>
      )}

      <ul className="grid grid-cols-2 gap-x-4 gap-y-6 lg:grid-cols-4">
        {cases.map((summary) => <li key={summary.id}><CaseTile item={toTile(summary)} /></li>)}
      </ul>

      {query.hasNextPage && (
        <div className="flex justify-center">
          <button type="button" onClick={() => query.fetchNextPage()} disabled={query.isFetchingNextPage}
            className="rounded-full border border-border px-5 py-2 text-sm text-text-secondary hover:text-foreground disabled:opacity-50">
            {query.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </button>
        </div>
      )}
    </div>
  );
}

export default function BrowseCasesPage() {
  return <Suspense><BrowseCases /></Suspense>;
}
