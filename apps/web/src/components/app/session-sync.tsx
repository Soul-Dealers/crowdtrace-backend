'use client';

import { useEffect } from 'react';
import { api } from '@/lib/api/client';
import { getSession, saveSession, useSession, type Role } from '@/lib/auth';

/**
 * Re-reads the signed-in user once per page load, so a role a Super Admin changed shows up in the menus without
 * logging in again. A 401 (for example a deactivated account) clears the session in the API client.
 */
export function SessionSync() {
  const token = useSession()?.token;
  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    void api.GET('/api/v1/auth/me').then(({ data }) => {
      const me = data?.data;
      const current = getSession();
      if (cancelled || !me || !current || current.token !== token) return;
      if (me.role !== current.role || me.displayName !== current.displayName) {
        saveSession({ ...current, role: me.role as Role, displayName: me.displayName ?? current.displayName });
      }
    });
    return () => {
      cancelled = true;
    };
  }, [token]);
  return null;
}

