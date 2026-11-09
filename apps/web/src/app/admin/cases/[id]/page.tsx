'use client';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, Download, FileText, Lock } from 'lucide-react';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { DetailList } from '@/components/app/detail-list';
import { FormMessage } from '@/components/app/form';
import { LifecycleActions } from '@/components/app/lifecycle-actions';
import { RequireAuth } from '@/components/app/require-auth';
import { StatusBadge } from '@/components/app/status-badge';
import { Button } from '@/components/ui/button';
import { WithRail } from '@/components/ct/app-shell';
import { PageHeader } from '@/components/ct/page-header';
import { Panel } from '@/components/ct/panel';
import { Pill } from '@/components/ct/pill';
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

  if (query.isPending) return <div className="h-60 animate-pulse rounded-2xl bg-muted" aria-busy />;
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

  /** Opens the tab first so the popup isn't blocked after the await; the signed link lasts one minute. */
  async function openFile(fileId: number) {
    setError(null);
    const tab = window.open('', '_blank');
    if (tab) tab.opener = null;
    try {
      const { url } = unwrap<Schemas['SignedFileUrl']>(
        await api.GET('/api/v1/admin/review/cases/{id}/files/{fileId}/url', {
          params: { path: { id, fileId } },
        }),
      );
      if (tab) tab.location.href = url;
      else window.location.assign(url);
    } catch (err) {
      tab?.close();
      setError((err as Error).message);
    }
  }

  return (
    <div className="space-y-5">
      <PageHeader
        title={c.fullName}
        description={`Case #${c.id} · reported by ${c.reporterAccountEmail} · ${formatDate(c.submittedAt)}`}
      >
        <Link
          href="/admin/review"
          className="mb-2 inline-flex items-center gap-1 text-sm text-text-secondary hover:text-foreground"
        >
          <ArrowLeft className="size-4" aria-hidden /> Review queue
        </Link>
        <div className="mb-2 flex flex-wrap gap-1.5">
          <StatusBadge status={c.reviewStatus} />
          <StatusBadge status={c.caseStatus} />
          {c.priorityMinor && <Pill tone="danger">Minor</Pill>}
          {c.duplicateFlag && <Pill tone="warning">Duplicate?</Pill>}
        </div>
      </PageHeader>
      <FormMessage error={error} />

      <WithRail
        rail={
          <>
            {inReview && (
              <Panel title="Decision" bodyClassName="p-4">
                <form className="grid gap-3" onSubmit={(e) => e.preventDefault()}>
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
              </Panel>
            )}
            {(c.reviewStatus === 'APPROVED' || c.reviewStatus === 'TAKEN_DOWN') && (
              <LifecycleActions
                caseId={c.id}
                version={c.version}
                as="admin"
                queryKey={queryKey}
                takenDown={c.reviewStatus === 'TAKEN_DOWN'}
              />
            )}
            {!!d.reviewHistory?.length && (
              <Panel title="Review history" mono bodyClassName="divide-y divide-border">
                {d.reviewHistory.map((h) => (
                  <div key={h.reviewId} className="px-4 py-3 text-sm">
                    <div className="flex items-center justify-between gap-2">
                      <StatusBadge status={h.decision} />
                      <span className="text-xs text-text-subtle">{formatDate(h.decidedAt)}</span>
                    </div>
                    {h.reviewNotes && <p className="mt-1.5 text-text-secondary">{h.reviewNotes}</p>}
                  </div>
                ))}
              </Panel>
            )}
          </>
        }
      >
        <div className="space-y-5">
          <Panel
            title="Public details"
            meta="Shown on the case page once approved"
            bodyClassName="p-4 sm:p-5"
          >
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
                ['Closing statement', c.closingStatement],
                ['Takedown reason', c.takedownReason],
              ]}
            />
          </Panel>

          {Object.keys(c.sensitiveDetails ?? {}).length > 0 && (
            <Panel
              title={
                <span className="flex items-center gap-2">
                  <Lock className="size-4 text-warning" aria-hidden /> Private details
                </span>
              }
              meta="Reviewers only"
              bodyClassName="p-4 sm:p-5"
            >
              <DetailList
                items={Object.entries(c.sensitiveDetails ?? {}).map(
                  ([k, v]) =>
                    [humanize(k.replace(/([A-Z])/g, '_$1')), v] as [string, React.ReactNode],
                )}
              />
            </Panel>
          )}

          {!!d.evidence?.length && (
            <Panel
              title="Evidence"
              meta="Links last one minute and are logged"
              bodyClassName="divide-y divide-border"
            >
              {d.evidence.map((e) => (
                <div
                  key={e.file?.id}
                  className="flex flex-wrap items-center gap-3 px-4 py-3 text-sm"
                >
                  <FileText className="size-4 text-text-secondary" aria-hidden />
                  <div className="min-w-0 flex-1">
                    <div className="font-medium">{humanize(e.file?.purpose)}</div>
                    <div className="text-xs text-text-subtle">
                      {e.file?.contentType} ·{' '}
                      {Math.max(1, Math.round((e.file?.sizeBytes ?? 0) / 1024))} KB
                    </div>
                  </div>
                  {e.accessAvailable ? (
                    <Button size="sm" variant="outline" onClick={() => openFile(e.file!.id!)}>
                      <Download aria-hidden /> Open
                    </Button>
                  ) : (
                    <span className="text-xs text-text-subtle">Storage unavailable</span>
                  )}
                </div>
              ))}
            </Panel>
          )}

          {!!d.duplicateMatches?.length && (
            <Panel title="Possible duplicates" bodyClassName="divide-y divide-border">
              {d.duplicateMatches.map((m) => (
                <div
                  key={m.matchedCaseId}
                  className="flex flex-wrap items-center gap-3 px-4 py-3 text-sm"
                >
                  <Link
                    className="font-medium hover:text-brand"
                    href={`/admin/cases/${m.matchedCaseId}`}
                  >
                    Case #{m.matchedCaseId}
                  </Link>
                  <Pill tone="warning">{m.confidence}% match</Pill>
                  <span className="text-text-secondary">{m.reasons?.join(', ')}</span>
                </div>
              ))}
            </Panel>
          )}
        </div>
      </WithRail>
    </div>
  );
}

export default function AdminCasePage() {
  const { id } = useParams<{ id: string }>();
  return (
    <RequireAuth admin>
      <AdminCaseView id={Number(id)} />
    </RequireAuth>
  );
}
