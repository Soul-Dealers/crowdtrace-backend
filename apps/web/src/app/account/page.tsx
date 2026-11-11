'use client';

import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { DetailList } from '@/components/app/detail-list';
import { Field, FormMessage, NativeSelect, formValues } from '@/components/app/form';
import { RequireAuth } from '@/components/app/require-auth';
import { StatusBadge } from '@/components/app/status-badge';
import { PageHeader } from '@/components/ct/page-header';
import { UserAvatar } from '@/components/ct/user-avatar';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { api, errorMessage, unwrap, type Schemas } from '@/lib/api/client';
import { saveSession, useSession } from '@/lib/auth';
import { VERIFICATION_TYPES, formatDate, humanize } from '@/lib/enums';

function Account() {
  const qc = useQueryClient();
  const session = useSession()!;
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const me = useQuery({
    queryKey: ['me'],
    queryFn: async () => unwrap<Schemas['UserResponse']>(await api.GET('/api/v1/auth/me')),
  });
  const requests = useQuery({
    queryKey: ['my-verifications'],
    queryFn: async () =>
      unwrap<Schemas['VerificationRequestResponse'][]>(
        await api.GET('/api/v1/verification-requests/me'),
      ),
  });

  async function rename(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    const { displayName } = formValues(e.currentTarget);
    const { error } = await api.PATCH('/api/v1/auth/profile-settings', { body: { displayName } });
    if (error) return setError(errorMessage(error));
    saveSession({ ...session, displayName });
    setNotice('Profile updated.');
    qc.invalidateQueries({ queryKey: ['me'] });
  }

  async function requestBadge(e: React.FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setError(null);
    const v = formValues(e.currentTarget);
    const { error } = await api.POST('/api/v1/verification-requests', {
      body: {
        verificationType: v.verificationType as (typeof VERIFICATION_TYPES)[number],
        evidenceReference: v.evidenceReference,
      },
    });
    if (error) return setError(errorMessage(error));
    setNotice('Verification request submitted.');
    qc.invalidateQueries({ queryKey: ['my-verifications'] });
  }

  const u = me.data;
  return (
    <div className="grid gap-6">
      <FormMessage error={error} success={notice} />
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-3">
            {u?.avatarSeed && <UserAvatar seed={u.avatarSeed} size="xl" />}
            Profile {u?.verified && <Badge>{humanize(u.badgeType) || 'Verified'}</Badge>}
          </CardTitle>
        </CardHeader>
        <CardContent className="grid gap-6">
          {u && (
            <DetailList
              items={[
                ['Email', u.email],
                ['Role', humanize(u.role)],
                ['Status', humanize(u.accountStatus)],
                ['Joined', formatDate(u.createdAt)],
              ]}
            />
          )}
          <form onSubmit={rename} className="flex items-end gap-2">
            <Field
              label="Display name"
              name="displayName"
              defaultValue={session.displayName}
              required
            />
            <Button>Save</Button>
          </form>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Verified badge</CardTitle>
        </CardHeader>
        <CardContent className="grid gap-4">
          <ul className="grid gap-2 text-sm">
            {requests.data?.map((r) => (
              <li key={r.id} className="flex flex-wrap gap-2">
                <StatusBadge status={r.status} />
                {humanize(r.verificationType)} · {r.evidenceReference} · {formatDate(r.createdAt)}
              </li>
            ))}
          </ul>
          <form
            onSubmit={requestBadge}
            className="grid gap-4 sm:grid-cols-[auto_1fr_auto] sm:items-end"
          >
            <NativeSelect
              label="Type"
              name="verificationType"
              options={VERIFICATION_TYPES}
              format={humanize}
            />
            <Field label="Evidence reference" name="evidenceReference" required />
            <Button>Request</Button>
          </form>
        </CardContent>
      </Card>
    </div>
  );
}

export default function AccountPage() {
  return (
    <div className="max-w-3xl space-y-5">
      <PageHeader title="Account" description="Your profile and verification badge." />
      <RequireAuth>
        <Account />
      </RequireAuth>
    </div>
  );
}
