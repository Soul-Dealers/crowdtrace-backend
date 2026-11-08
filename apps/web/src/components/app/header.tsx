'use client';

import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { api } from '@/lib/api/client';
import { clearSession, isAdmin, useSession } from '@/lib/auth';

export function Header() {
  const session = useSession();
  const router = useRouter();
  const qc = useQueryClient();

  async function logout() {
    // The bearer middleware sets the real header; the spec marks it required.
    await api
      .POST('/api/v1/auth/logout', { params: { header: { Authorization: '' } } })
      .catch(() => undefined);
    clearSession();
    qc.clear(); // don't leak the previous account's cached data to the next login
    router.push('/');
  }

  return (
    <header className="border-b">
      <nav className="mx-auto flex max-w-5xl items-center gap-4 px-4 py-3 text-sm">
        <Link href="/" className="mr-auto text-base font-semibold">
          CrowdTrace
        </Link>
        {session ? (
          <>
            <Link href="/report">Report a case</Link>
            {isAdmin(session) && (
              <>
                <Link href="/admin/review">Review queue</Link>
                <Link href="/admin/verifications">Verifications</Link>
              </>
            )}
            <Link href="/account">{session.displayName}</Link>
            <Button size="sm" variant="outline" onClick={logout}>
              Log out
            </Button>
          </>
        ) : (
          <>
            <Link href="/login">Log in</Link>
            <Button size="sm" asChild>
              <Link href="/signup">Sign up</Link>
            </Button>
          </>
        )}
      </nav>
    </header>
  );
}
