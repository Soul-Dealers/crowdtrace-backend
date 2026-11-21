'use client';

import { useEffect } from 'react';
import { api } from '@/lib/api/client';
import { getSession, saveSession, useSession } from '@/lib/auth';
import { refreshedSession } from '@/lib/session-refresh';

/**
 * Re-reads the signed-in user once per page load, so a role a Super Admin changed shows up in the menus without
 * logging in again. A 401 (for example a deactivated account) clears the session in the API client.
 */
export function SessionSync() {
  const token = useSession()?.token;
  useEffect(() => {
    const started = getSession();
    if (!token || !started) return;
    let cancelled = false;
    void api.GET('/api/v1/auth/me').then(({ data }) => {
      const me = data?.data;
      if (cancelled || !me) return;
      const next = refreshedSession(started, getSession(), me);
      if (next) saveSession(next);
    });
    return () => {
      cancelled = true;
    };
  }, [token]);
  return null;
}
