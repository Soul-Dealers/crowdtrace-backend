'use client';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Field, FormMessage, NativeSelect, formValues } from '@/components/app/form';
import { Pager } from '@/components/app/pager';
import { RequireAuth } from '@/components/app/require-auth';
import { StatusBadge } from '@/components/app/status-badge';
import { PageHeader, TABLE } from '@/components/ct/page-header';
import { Panel } from '@/components/ct/panel';
import { Segmented } from '@/components/ct/segmented';
import { Button } from '@/components/ui/button';
import { api, errorMessage, unwrap, type Schemas } from '@/lib/api/client';
import { useSession } from '@/lib/auth';
import { VERIFICATION_TYPES, formatDate, humanize } from '@/lib/enums';

type Status = 'PENDING' | 'APPROVED' | 'REJECTED' | 'REVOKED';
type Decision = 'approve' | 'reject' | 'revoke';

const TABS: { value: Status; label: string }[] = [
  { value: 'PENDING', label: 'Pending' },
  { value: 'APPROVED', label: 'Approved' },
  { value: 'REJECTED', label: 'Rejected' },
  { value: 'REVOKED', label: 'Revoked' },
];

const PATHS = {
  approve: '/api/v1/admin/verification-requests/{id}/approve',
  reject: '/api/v1/admin/verification-requests/{id}/reject',
  revoke: '/api/v1/admin/verification-requests/{id}/revoke',
} as const;

function Verifications() {
  const qc = useQueryClient();
  const isSuperAdmin = useSession()?.role === 'SUPER_ADMIN';
  const [status, setStatus] = useState<Status>('PENDING');
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const query = useQuery({
    queryKey: ['admin-verifications', status, page],
    queryFn: async () =>
      unwrap<Schemas['AdminVerificationPageResponse']>(
        await api.GET('/api/v1/admin/verification-requests', {
          params: { query: { status, page, size: 20 } },
        }),
      ),
  });
  const rows = query.data?.content ?? [];

  async function decide(r: Schemas['AdminVerificationRequestResponse'], decision: Decision) {
    setError(null);
    setNotice(null);
    const notes = window.prompt(
      decision === 'revoke'
        ? `Why are you revoking ${r.displayName}'s badge?`
        : `Notes for ${decision} (optional)`,
    );
    if (notes === null) return;
    const { error } = await api.POST(PATHS[decision], {
      params: { path: { id: r.id! } },
      body: { reviewNotes: notes || undefined },
    });
    if (error) return setError(errorMessage(error));
    qc.invalidateQueries({ queryKey: ['admin-verifications'] });
  }

  async function grant(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    const form = e.currentTarget;
    const v = formValues(form);
    const { error } = await api.POST('/api/v1/admin/verification-grants', {
      body: {
        email: v.email,
        verificationType: v.verificationType as (typeof VERIFICATION_TYPES)[number],
        evidenceReference: v.evidenceReference,
        reviewNotes: v.reviewNotes || undefined,
      },
    });
    if (error) return setError(errorMessage(error));
    form.reset();
    setNotice('Badge granted.');
    qc.invalidateQueries({ queryKey: ['admin-verifications'] });
  }

  return (
    <div className="space-y-4">
      <Segmented
        label="Request status"
        value={status}
        onChange={(v) => (setPage(0), setStatus(v))}
        options={TABS}
        className="max-w-full overflow-x-auto"
      />
      <FormMessage error={error} success={notice} />
      {query.isError && <FormMessage error={(query.error as Error).message} />}
      {query.isPending && <div className="h-40 animate-pulse rounded-2xl bg-muted" aria-busy />}
      {query.isSuccess && rows.length === 0 && (
        <p className="py-10 text-center text-muted-foreground">
          No {status.toLowerCase()} requests.
        </p>
      )}
      {rows.length > 0 && (
        <Panel bodyClassName="overflow-x-auto">
          <table className={TABLE.table}>
            <thead className={TABLE.head}>
              <tr>
                <th>User</th>
                <th>Type</th>
                <th>Evidence</th>
                <th>Status</th>
                <th>Requested</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className={TABLE.row}>
                  <td className="font-medium">{r.displayName}</td>
                  <td className="text-text-secondary">{humanize(r.verificationType)}</td>
                  <td className="max-w-56 truncate text-text-secondary" title={r.evidenceReference}>
                    {r.evidenceReference}
                  </td>
                  <td>
                    <StatusBadge status={r.status} />
                  </td>
                  <td className="text-text-secondary">{formatDate(r.createdAt)}</td>
                  <td>
                    <span className="flex justify-end gap-1.5">
                      {r.status === 'PENDING' && (
                        <>
                          <Button size="sm" onClick={() => decide(r, 'approve')}>
                            Approve
                          </Button>
                          <Button size="sm" variant="outline" onClick={() => decide(r, 'reject')}>
                            Reject
                          </Button>
                        </>
                      )}
                      {/* Revoking is SUPER_ADMIN-only on the API. */}
                      {r.status === 'APPROVED' && isSuperAdmin && (
                        <Button
                          size="sm"
                          variant="outline"
                          className="text-destructive"
                          onClick={() => decide(r, 'revoke')}
                        >
                          Revoke
                        </Button>
                      )}
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </Panel>
      )}
      <Pager page={page} totalPages={query.data?.totalPages} onChange={setPage} />

      {/* Granting is SUPER_ADMIN-only on the API. */}
      {isSuperAdmin && (
        <Panel title="Grant a badge directly" bodyClassName="p-4 sm:p-5">
          <form onSubmit={grant} className="grid gap-4 sm:grid-cols-2">
            <Field label="User email" name="email" type="email" required />
            <NativeSelect
              label="Type"
              name="verificationType"
              options={VERIFICATION_TYPES}
              format={humanize}
            />
            <Field label="Evidence reference" name="evidenceReference" required />
            <Field label="Notes" name="reviewNotes" />
            <Button className="justify-self-start">Grant badge</Button>
          </form>
        </Panel>
      )}
    </div>
  );
}

export default function AdminVerificationsPage() {
  return (
    <div className="space-y-5">
      <PageHeader
        title="Verifications"
        description="Badge requests from police, NGOs and subject-matter experts."
      />
      <RequireAuth admin>
        <Verifications />
      </RequireAuth>
    </div>
  );
}
