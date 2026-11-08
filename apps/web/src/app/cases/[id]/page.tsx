'use client';

import { useQuery } from '@tanstack/react-query';
import { useParams } from 'next/navigation';
import { DetailList } from '@/components/app/detail-list';
import { FormMessage } from '@/components/app/form';
import { StatusBadge } from '@/components/app/status-badge';
import { api, unwrap, type Schemas } from '@/lib/api/client';
import { formatDate, humanize } from '@/lib/enums';

export default function PublicCasePage() {
  const { id } = useParams<{ id: string }>();
  const query = useQuery({
    queryKey: ['public-case', id],
    queryFn: async () =>
      unwrap<Schemas['PublicCaseResponse']>(
        await api.GET('/api/public/cases/{caseId}', { params: { path: { caseId: id } } }),
      ),
  });

  if (query.isPending) return <p className="mx-auto max-w-3xl px-4 py-8">Loading…</p>;
  if (query.isError) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-8">
        <FormMessage error={(query.error as Error).message} />
      </div>
    );
  }

  const c = query.data;
  return (
    <main className="mx-auto max-w-3xl px-4 py-8">
      <div className="mb-6 flex items-center gap-3">
        <h1 className="text-2xl font-semibold">{c.fullName}</h1>
        <StatusBadge status={c.caseStatus} />
      </div>
      {c.closingStatement && <FormMessage success={c.closingStatement} />}
      <DetailList
        items={[
          ['Age', c.age],
          ['Gender', humanize(c.gender)],
          ['Region', humanize(c.region)],
          ['Last seen', formatDate(c.lastSeenDate)],
          ['Last seen at', c.lastSeenLocation],
          ['Description', c.physicalDescription],
          ['Clothing', c.clothing],
          ['Circumstances', c.circumstances],
          ['Contact', c.publicContactNumber],
          ['Published', formatDate(c.approvedAt)],
        ]}
      />
    </main>
  );
}
