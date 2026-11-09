'use client';

import Link from 'next/link';
import { Lock } from 'lucide-react';
import { isAdmin, useSession } from '@/lib/auth';

function Gate({ title, body, action }: { title: string; body: string; action?: React.ReactNode }) {
  return (
    <section className="mx-auto my-10 max-w-md rounded-2xl border border-border bg-card p-6 text-center">
      <Lock className="mx-auto size-6 text-text-secondary" aria-hidden />
      <h1 className="mt-3 text-lg font-semibold">{title}</h1>
      <p className="mt-1 text-sm text-muted-foreground">{body}</p>
      {action && <div className="mt-5">{action}</div>}
    </section>
  );
}

/** Client-side gate. The API enforces roles; this only avoids rendering pages that would 401/403. */
export function RequireAuth({ admin, children }: { admin?: boolean; children: React.ReactNode }) {
  const session = useSession();
  if (!session) {
    return (
      <Gate
        title="Sign in to continue"
        body="You need an account to see this page."
        action={
          <Link
            href="/login"
            className="inline-flex h-10 items-center rounded-xl bg-white px-5 text-sm font-semibold text-black hover:bg-white/90"
          >
            Log in
          </Link>
        }
      />
    );
  }
  if (admin && !isAdmin(session)) {
    return <Gate title="No access" body="This page is for moderators only." />;
  }
  return <>{children}</>;
}
