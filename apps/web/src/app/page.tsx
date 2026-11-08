'use client';

import { useInfiniteQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';
import { FormMessage, NativeSelect, formValues } from '@/components/app/form';
import { StatusBadge } from '@/components/app/status-badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { api, unwrap, type Schemas } from '@/lib/api/client';
import { CASE_STATUSES, REGIONS, formatDate, humanize } from '@/lib/enums';

type Filters = { name?: string; region?: string; status?: string };

export default function PublicCasesPage() {
  const [filters, setFilters] = useState<Filters>({});

  const query = useInfiniteQuery({
    queryKey: ['public-cases', filters],
    initialPageParam: undefined as string | undefined,
    queryFn: async ({ pageParam }) =>
      unwrap<Schemas['PublicCaseCursorResponse']>(
        await api.GET('/api/public/cases', {
          params: {
            query: {
              name: filters.name || undefined,
              region: (filters.region || undefined) as (typeof REGIONS)[number] | undefined,
              status: filters.status
                ? [filters.status as (typeof CASE_STATUSES)[number]]
                : undefined,
              cursor: pageParam,
              size: 20,
            },
          },
        }),
      ),
    getNextPageParam: (last) => (last.hasNext ? (last.nextCursor ?? undefined) : undefined),
  });

  const cases = query.data?.pages.flatMap((p) => p.content ?? []) ?? [];

  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <h1 className="mb-6 text-2xl font-semibold">Missing persons</h1>
      <form
        className="mb-6 grid gap-3 sm:grid-cols-[1fr_auto_auto_auto] sm:items-end"
        onSubmit={(e) => {
          e.preventDefault();
          setFilters(formValues(e.currentTarget));
        }}
      >
        <Input name="name" placeholder="Search by name" aria-label="Name" />
        <NativeSelect
          label="Region"
          name="region"
          options={REGIONS}
          placeholder="All regions"
          format={humanize}
        />
        <NativeSelect
          label="Status"
          name="status"
          options={CASE_STATUSES}
          placeholder="Any status"
          format={humanize}
        />
        <Button>Search</Button>
      </form>

      {query.isError && <FormMessage error={(query.error as Error).message} />}
      {query.isPending && <p className="text-muted-foreground">Loading…</p>}
      {query.isSuccess && cases.length === 0 && (
        <p className="text-muted-foreground">No cases match these filters.</p>
      )}

      <ul className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {cases.map((c) => (
          <li key={c.id}>
            <Link href={`/cases/${c.id}`}>
              <Card className="h-full transition-colors hover:bg-accent">
                <CardContent className="grid gap-1">
                  <div className="flex items-start justify-between gap-2">
                    <span className="font-medium">{c.fullName}</span>
                    <StatusBadge status={c.caseStatus} />
                  </div>
                  <span className="text-sm text-muted-foreground">
                    {c.age} · {humanize(c.gender)} · {humanize(c.region)}
                  </span>
                  <span className="text-sm text-muted-foreground">
                    Last seen {formatDate(c.lastSeenDate)}
                  </span>
                </CardContent>
              </Card>
            </Link>
          </li>
        ))}
      </ul>

      {query.hasNextPage && (
        <div className="mt-6 text-center">
          <Button
            variant="outline"
            onClick={() => query.fetchNextPage()}
            disabled={query.isFetchingNextPage}
          >
            {query.isFetchingNextPage ? 'Loading…' : 'Load more'}
          </Button>
        </div>
      )}
    </main>
  );
}
