'use client';

import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';
import { Pager } from '@/components/app/pager';
import { RequireAuth } from '@/components/app/require-auth';
import { Panel } from '@/components/ct/panel';
import { Pill, type PillTone } from '@/components/ct/pill';
import { api, errorMessage, unwrap, type Schemas } from '@/lib/api/client';
import { formatShortDate } from '@/lib/case-display';

type Summary = Schemas['ReporterCaseSummaryResponse'];

const REVIEW: Record<NonNullable<Summary['reviewStatus']>, { label: string; tone: PillTone }> = {
  SUBMITTED: { label: 'Awaiting review', tone: 'neutral' },
  UNDER_REVIEW: { label: 'Under review', tone: 'warning' },
  APPROVED: { label: 'Published', tone: 'success' },
  REJECTED: { label: 'Not approved', tone: 'danger' },
  TAKEN_DOWN: { label: 'Taken down', tone: 'danger' },
};

const OUTCOME: Record<string, string> = {
  MISSING: 'Missing', FOUND_SAFE: 'Found safe', FOUND_DECEASED: 'Found deceased', CLOSED: 'Closed',
};

function MyCases() {
  const [page, setPage] = useState(0);
  const query = useQuery({
    queryKey: ['my-cases', page],
    queryFn: async () => unwrap<Schemas['ReporterCasePageResponse']>(
      await api.GET('/api/user/cases', { params: { query: { page, size: 20 } } })),
  });
  const cases = query.data?.content ?? [];

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold sm:text-3xl">My Cases</h1>
          <p className="text-muted-foreground">Cases you reported, newest first. Only you can see this list.</p>
        </div>
        <Link href="/report" className="rounded-lg bg-white px-3 py-2 text-sm font-semibold text-black hover:bg-white/90">
          Report a missing person
        </Link>
      </header>

      {query.isError && <p className="text-sm text-destructive">{errorMessage(query.error)}</p>}
      {query.isPending && <div className="h-40 animate-pulse rounded-2xl bg-muted" aria-busy />}
      {query.isSuccess && cases.length === 0 && (
        <p className="py-10 text-center text-muted-foreground">You haven’t reported any cases yet.</p>
      )}

      {cases.length > 0 && (
        <Panel bodyClassName="divide-y divide-border">
          {cases.map((c) => {
            const review = REVIEW[c.reviewStatus!];
            return (
              <Link key={c.id} href={`/my/cases/${c.id}`}
                className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3 hover:bg-white/[0.02]">
                <div className="min-w-0 flex-1">
                  <div className="truncate font-medium">{c.fullName}</div>
                  <div className="text-sm text-muted-foreground">
                    Submitted {c.submittedAt ? formatShortDate(c.submittedAt) : '—'}
                    {c.caseStatus && <> · {OUTCOME[c.caseStatus] ?? c.caseStatus}</>}
                  </div>
                </div>
                <Pill tone={review.tone}>{review.label}</Pill>
              </Link>
            );
          })}
        </Panel>
      )}
      <Pager page={page} totalPages={query.data?.totalPages} onChange={setPage} />
    </div>
  );
}

export default function MyCasesPage() {
  return <RequireAuth><MyCases /></RequireAuth>;
}
