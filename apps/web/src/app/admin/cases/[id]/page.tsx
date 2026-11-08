'use client';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { DetailList } from '@/components/app/detail-list';
import { FormMessage } from '@/components/app/form';
import { LifecycleActions } from '@/components/app/lifecycle-actions';
import { RequireAuth } from '@/components/app/require-auth';
import { StatusBadge } from '@/components/app/status-badge';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { api, errorMessage, unwrap, type Schemas } from '@/lib/api/client';
import { formatDate, humanize } from '@/lib/enums';

/** `caseDetails` is AdminCaseResponse on the backend but untyped (`object`) in the OpenAPI spec. */
interface AdminCase {
  id: number;
  reporterAccountEmail: string;
  fullName: string;
  age: number;
  gender: string;
  lastSeenDate: string;
  region: string;
  lastSeenLocation?: string;
  physicalDescription?: string;
  clothing?: string;
  circumstances?: string;
  publicContactNumber?: string;
  reviewStatus: string;
  caseStatus?: string;
  closingStatement?: string;
  priorityMinor: boolean;
  duplicateFlag: boolean;
  version: number;
  submittedAt: string;
  approvedAt?: string;
  sensitiveDetails?: Record<string, string | null>;
  takedownReason?: string;
  takenDownAt?: string;
}

function AdminCaseView({ id }: { id: number }) {
  const qc = useQueryClient();
  const queryKey = ['admin-case', id];
  const [error, setError] = useState<string | null>(null);
  const query = useQuery({
    queryKey,
    queryFn: async () =>
      unwrap<Schemas['ReviewCaseDetailResponse']>(
        await api.GET('/api/v1/admin/review/cases/{id}', { params: { path: { id } } }),
      ),
  });

  if (query.isPending) return <p>Loading…</p>;
  if (query.isError) return <FormMessage error={(query.error as Error).message} />;
  const d = query.data;
  const c = d.caseDetails as unknown as AdminCase;
  const inReview = c.reviewStatus === 'SUBMITTED' || c.reviewStatus === 'UNDER_REVIEW';

  async function decide(decision: 'approve' | 'reject', form: HTMLFormElement) {
    setError(null);
    const reviewNotes = String(new FormData(form).get('reviewNotes') ?? '');
    const path = { params: { path: { id } } };
    const { error } =
      decision === 'approve'
        ? await api.POST('/api/v1/admin/review/cases/{id}/approve', {
            ...path,
            body: { expectedCaseVersion: c.version, reviewNotes: reviewNotes || undefined },
          })
        : await api.POST('/api/v1/admin/review/cases/{id}/reject', {
            ...path,
            body: { expectedCaseVersion: c.version, reviewNotes },
          });
    if (error) return setError(errorMessage(error));
    qc.invalidateQueries({ queryKey });
  }

  return (
    <div className="grid gap-6">
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold">{c.fullName}</h1>
        <Badge variant="outline">{humanize(c.reviewStatus)}</Badge>
        <StatusBadge status={c.caseStatus} />
        {c.priorityMinor && <Badge variant="destructive">Minor</Badge>}
        {c.takenDownAt && <Badge variant="destructive">Taken down</Badge>}
      </div>
      <FormMessage error={error} />

      <DetailList
        items={[
          ['Reporter', c.reporterAccountEmail],
          ['Submitted', formatDate(c.submittedAt)],
          ['Age', c.age],
          ['Gender', humanize(c.gender)],
          ['Region', humanize(c.region)],
          ['Last seen', formatDate(c.lastSeenDate)],
          ['Last seen at', c.lastSeenLocation],
          ['Description', c.physicalDescription],
          ['Clothing', c.clothing],
          ['Circumstances', c.circumstances],
          ['Contact', c.publicContactNumber],
          ['Takedown reason', c.takedownReason],
          ...Object.entries(c.sensitiveDetails ?? {}).map(
            ([k, v]) =>
              [`🔒 ${humanize(k.replace(/([A-Z])/g, '_$1'))}`, v] as [string, React.ReactNode],
          ),
        ]}
      />

      {!!d.duplicateMatches?.length && (
        <Card>
          <CardHeader>
            <CardTitle>Possible duplicates</CardTitle>
          </CardHeader>
          <CardContent className="grid gap-2 text-sm">
            {d.duplicateMatches.map((m) => (
              <div key={m.matchedCaseId}>
                <Link className="underline" href={`/admin/cases/${m.matchedCaseId}`}>
                  Case #{m.matchedCaseId}
                </Link>{' '}
                · {m.confidence}% confidence · {m.reasons?.join(', ')}
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      {!!d.evidence?.length && (
        <Card>
          <CardHeader>
            <CardTitle>Evidence</CardTitle>
          </CardHeader>
          <CardContent className="grid gap-1 text-sm">
            {d.evidence.map((e) => (
              <div key={e.file?.id}>
                {humanize(e.file?.purpose)} · {e.file?.contentType} ·{' '}
                {Math.round((e.file?.sizeBytes ?? 0) / 1024)} KB
              </div>
            ))}
          </CardContent>
        </Card>
      )}

      {inReview && (
        <Card>
          <CardHeader>
            <CardTitle>Decision</CardTitle>
          </CardHeader>
          <CardContent>
            <form className="grid gap-2" onSubmit={(e) => e.preventDefault()}>
              <Label htmlFor="reviewNotes">Review notes (required to reject)</Label>
              <Textarea id="reviewNotes" name="reviewNotes" />
              <div className="flex gap-2">
                <Button type="button" onClick={(e) => decide('approve', e.currentTarget.form!)}>
                  Approve
                </Button>
                <Button
                  type="button"
                  variant="destructive"
                  onClick={(e) => decide('reject', e.currentTarget.form!)}
                >
                  Reject
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      )}

      {c.reviewStatus === 'APPROVED' && (
        <LifecycleActions
          caseId={c.id}
          version={c.version}
          as="admin"
          queryKey={queryKey}
          takenDown={!!c.takenDownAt}
        />
      )}

      {!!d.reviewHistory?.length && (
        <Card>
          <CardHeader>
            <CardTitle>Review history</CardTitle>
          </CardHeader>
          <CardContent className="grid gap-1 text-sm">
            {d.reviewHistory.map((h) => (
              <div key={h.reviewId}>
                {humanize(h.decision)} · {formatDate(h.decidedAt)}{' '}
                {h.reviewNotes && `· ${h.reviewNotes}`}
              </div>
            ))}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

export default function AdminCasePage() {
  const { id } = useParams<{ id: string }>();
  return (
    <main className="mx-auto max-w-3xl px-4 py-8">
      <RequireAuth admin>
        <AdminCaseView id={Number(id)} />
      </RequireAuth>
    </main>
  );
}
