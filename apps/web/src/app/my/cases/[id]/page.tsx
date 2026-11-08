'use client';

import { useQuery } from '@tanstack/react-query';
import { useParams } from 'next/navigation';
import { DetailList } from '@/components/app/detail-list';
import { FormMessage } from '@/components/app/form';
import { LifecycleActions } from '@/components/app/lifecycle-actions';
import { RequireAuth } from '@/components/app/require-auth';
import { StatusBadge } from '@/components/app/status-badge';
import { Badge } from '@/components/ui/badge';
import { api, unwrap, type Schemas } from '@/lib/api/client';
import { formatDate, humanize } from '@/lib/enums';

function ReporterCase({ id }: { id: number }) {
  const queryKey = ['reporter-case', id];
  const query = useQuery({
    queryKey,
    queryFn: async () =>
      unwrap<Schemas['ReporterCaseResponse']>(
        await api.GET('/api/user/cases/{id}', { params: { path: { id } } }),
      ),
  });

  if (query.isPending) return <p>Loading…</p>;
  if (query.isError) return <FormMessage error={(query.error as Error).message} />;
  const c = query.data;

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold">{c.fullName}</h1>
        <Badge variant="outline">{humanize(c.reviewStatus)}</Badge>
        <StatusBadge status={c.caseStatus} />
      </div>
      <DetailList
        items={[
          ['Submitted', formatDate(c.submittedAt)],
          ['Approved', c.approvedAt && formatDate(c.approvedAt)],
          ['Age', c.age],
          ['Gender', humanize(c.gender)],
          ['Region', humanize(c.region)],
          ['Last seen', formatDate(c.lastSeenDate)],
          ['Last seen at', c.lastSeenLocation],
          ['Description', c.physicalDescription],
          ['Clothing', c.clothing],
          ['Circumstances', c.circumstances],
          ['Contact', c.publicContactNumber],
          ['Closing statement', c.closingStatement],
        ]}
      />
      {c.reviewStatus === 'APPROVED' && (
        <LifecycleActions caseId={c.id!} version={c.version!} as="reporter" queryKey={queryKey} />
      )}
    </div>
  );
}

export default function MyCasePage() {
  const { id } = useParams<{ id: string }>();
  return (
    <main className="mx-auto max-w-3xl px-4 py-8">
      <RequireAuth>
        <ReporterCase id={Number(id)} />
      </RequireAuth>
    </main>
  );
}
