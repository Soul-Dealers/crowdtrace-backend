'use client';

import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useState } from 'react';
import { FormMessage } from '@/components/app/form';
import { Pager } from '@/components/app/pager';
import { RequireAuth } from '@/components/app/require-auth';
import { Badge } from '@/components/ui/badge';
import { api, unwrap, type Schemas } from '@/lib/api/client';
import { formatDate, humanize } from '@/lib/enums';

function Queue() {
  const [page, setPage] = useState(0);
  const [onlyFlagged, setOnlyFlagged] = useState(false);
  const query = useQuery({
    queryKey: ['review-queue', page, onlyFlagged],
    queryFn: async () =>
      unwrap<Schemas['ReviewQueuePageResponse']>(
        await api.GET('/api/v1/admin/review/cases', {
          params: { query: { page, size: 20, duplicateFlag: onlyFlagged || undefined } },
        }),
      ),
  });

  return (
    <>
      <label className="mb-4 flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          checked={onlyFlagged}
          onChange={(e) => (setPage(0), setOnlyFlagged(e.target.checked))}
        />
        Only possible duplicates
      </label>
      {query.isError && <FormMessage error={(query.error as Error).message} />}
      {query.isPending && <p>Loading…</p>}
      {query.data?.content?.length === 0 && (
        <p className="text-muted-foreground">The queue is empty.</p>
      )}
      <table className="w-full text-left text-sm">
        <thead className="text-muted-foreground">
          <tr>
            <th className="py-2">Name</th>
            <th>Age</th>
            <th>Region</th>
            <th>Status</th>
            <th>Flags</th>
            <th>Submitted</th>
          </tr>
        </thead>
        <tbody>
          {query.data?.content?.map((c) => (
            <tr key={c.id} className="border-t">
              <td className="py-2">
                <Link className="underline" href={`/admin/cases/${c.id}`}>
                  {c.fullName}
                </Link>
              </td>
              <td>{c.age}</td>
              <td>{humanize(c.region)}</td>
              <td>{humanize(c.reviewStatus)}</td>
              <td className="space-x-1">
                {c.priorityMinor && <Badge variant="destructive">Minor</Badge>}
                {c.duplicateFlag && <Badge variant="secondary">Duplicate?</Badge>}
              </td>
              <td>{formatDate(c.submittedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <Pager page={page} totalPages={query.data?.totalPages} onChange={setPage} />
    </>
  );
}

export default function ReviewQueuePage() {
  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <h1 className="mb-6 text-2xl font-semibold">Review queue</h1>
      <RequireAuth admin>
        <Queue />
      </RequireAuth>
    </main>
  );
}
