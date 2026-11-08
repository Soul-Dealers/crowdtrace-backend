'use client';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { Field, FormMessage, NativeSelect, formValues } from '@/components/app/form';
import { Pager } from '@/components/app/pager';
import { RequireAuth } from '@/components/app/require-auth';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { api, errorMessage, unwrap, type Schemas } from '@/lib/api/client';
import { useSession } from '@/lib/auth';
import { VERIFICATION_TYPES, formatDate, humanize } from '@/lib/enums';

type Decision = 'approve' | 'reject';

function Verifications() {
  const qc = useQueryClient();
  const isSuperAdmin = useSession()?.role === 'SUPER_ADMIN';
  const [page, setPage] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const query = useQuery({
    queryKey: ['admin-verifications', page],
    queryFn: async () =>
      unwrap<Schemas['AdminVerificationPageResponse']>(
        await api.GET('/api/v1/admin/verification-requests', {
          params: { query: { page, size: 20 } },
        }),
      ),
  });

  async function decide(id: number, decision: Decision) {
    setError(null);
    const notes = window.prompt(`Notes for ${decision} (optional)`);
    if (notes === null) return;
    const reviewNotes = notes || undefined;
    const opts = { params: { path: { id } }, body: { reviewNotes } };
    const { error } =
      decision === 'approve'
        ? await api.POST('/api/v1/admin/verification-requests/{id}/approve', opts)
        : await api.POST('/api/v1/admin/verification-requests/{id}/reject', opts);
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
    <div className="grid gap-6">
      <FormMessage error={error} success={notice} />
      {query.isPending && <p>Loading…</p>}
      <table className="w-full text-left text-sm">
        <thead className="text-muted-foreground">
          <tr>
            <th className="py-2">User</th>
            <th>Type</th>
            <th>Evidence</th>
            <th>Status</th>
            <th>Requested</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {query.data?.content?.map((r: Schemas['AdminVerificationRequestResponse']) => (
            <tr key={r.id} className="border-t">
              <td className="py-2">{r.displayName}</td>
              <td>{humanize(r.verificationType)}</td>
              <td>{r.evidenceReference}</td>
              <td>
                <Badge variant="outline">{humanize(r.status)}</Badge>
              </td>
              <td>{formatDate(r.createdAt)}</td>
              <td className="space-x-1 text-right">
                {r.status === 'PENDING' && (
                  <>
                    <Button size="sm" onClick={() => decide(r.id!, 'approve')}>
                      Approve
                    </Button>
                    <Button size="sm" variant="outline" onClick={() => decide(r.id!, 'reject')}>
                      Reject
                    </Button>
                  </>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <Pager page={page} totalPages={query.data?.totalPages} onChange={setPage} />

      {/* Granting is SUPER_ADMIN-only on the API. */}

      {isSuperAdmin && (
        <Card>
          <CardHeader>
            <CardTitle>Grant a badge directly</CardTitle>
          </CardHeader>
          <CardContent>
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
              <Button className="justify-self-start">Grant</Button>
            </form>
          </CardContent>
        </Card>
      )}
    </div>
  );
}

export default function AdminVerificationsPage() {
  return (
    <main className="mx-auto max-w-5xl px-4 py-8">
      <h1 className="mb-6 text-2xl font-semibold">Verification requests</h1>
      <RequireAuth admin>
        <Verifications />
      </RequireAuth>
    </main>
  );
}
