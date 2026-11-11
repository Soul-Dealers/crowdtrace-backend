'use client';

import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';
import { FormMessage } from '@/components/app/form';
import { Pager } from '@/components/app/pager';
import { RequireAuth } from '@/components/app/require-auth';
import { StatusBadge } from '@/components/app/status-badge';
import { PageHeader, TABLE } from '@/components/ct/page-header';
import { Panel } from '@/components/ct/panel';
import { Pill } from '@/components/ct/pill';
import { Segmented } from '@/components/ct/segmented';
import { api, unwrap, type Schemas } from '@/lib/api/client';
import { formatDate, humanize } from '@/lib/enums';

type Filter = 'all' | 'duplicates';

function Queue() {
  const [page, setPage] = useState(0);
  const [filter, setFilter] = useState<Filter>('all');
  const query = useQuery({
    queryKey: ['review-queue', page, filter],
    queryFn: async () =>
      unwrap<Schemas['ReviewQueuePageResponse']>(
        await api.GET('/api/v1/admin/review/cases', {
          params: {
            query: { page, size: 20, duplicateFlag: filter === 'duplicates' || undefined },
          },
        }),
      ),
  });
  const rows = query.data?.content ?? [];

  return (
    <div className="space-y-4">
      <Segmented
        label="Queue filter"
        value={filter}
        onChange={(v) => (setPage(0), setFilter(v))}
        options={[
          { value: 'all', label: 'All waiting' },
          { value: 'duplicates', label: 'Possible duplicates' },
        ]}
      />
      {query.isError && <FormMessage error={(query.error as Error).message} />}
      {query.isPending && <div className="h-40 animate-pulse rounded-2xl bg-muted" aria-busy />}
      {query.isSuccess && rows.length === 0 && (
        <p className="py-10 text-center text-muted-foreground">Nothing is waiting for review.</p>
      )}
      {rows.length > 0 && (
        <Panel bodyClassName="overflow-x-auto">
          <table className={TABLE.table}>
            <thead className={TABLE.head}>
              <tr>
                <th>Name</th>
                <th>Age</th>
                <th>Region</th>
                <th>Status</th>
                <th>Flags</th>
                <th>Submitted</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((c) => (
                <tr key={c.id} className={TABLE.row}>
                  <td>
                    <Link className="font-medium hover:text-brand" href={`/admin/cases/${c.id}`}>
                      {c.fullName}
                    </Link>
                  </td>
                  <td>{c.age}</td>
                  <td className="text-text-secondary">{humanize(c.region)}</td>
                  <td>
                    <StatusBadge status={c.reviewStatus} />
                  </td>
                  <td>
                    <span className="flex flex-wrap gap-1">
                      {c.priorityMinor && <Pill tone="danger">Minor</Pill>}
                      {c.duplicateFlag && <Pill tone="warning">Duplicate?</Pill>}
                    </span>
                  </td>
                  <td className="text-text-secondary">{formatDate(c.submittedAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      )}
      <Pager page={page} totalPages={query.data?.totalPages} onChange={setPage} />
    </div>
  );
}

export default function ReviewQueuePage() {
  return (
    <div className="space-y-5">
      <PageHeader
        title="Review queue"
        description="New reports waiting for a moderator. Minors are listed first."
      />
      <RequireAuth admin>
        <Queue />
      </RequireAuth>
    </div>
  );
}
