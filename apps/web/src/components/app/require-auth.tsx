'use client';

import Link from 'next/link';
import { isAdmin, useSession } from '@/lib/auth';

/** Client-side gate. The API enforces roles; this only avoids rendering pages that would 401/403. */
export function RequireAuth({ admin, children }: { admin?: boolean; children: React.ReactNode }) {
  const session = useSession();
  if (!session) {
    return (
      <p className="mx-auto max-w-5xl px-4 py-12">
        Please{' '}
        <Link className="underline" href="/login">
          log in
        </Link>{' '}
        to continue.
      </p>
    );
  }
  if (admin && !isAdmin(session)) {
    return <p className="mx-auto max-w-5xl px-4 py-12">You don&apos;t have access to this page.</p>;
  }
  return <>{children}</>;
}
