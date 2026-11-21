import type { Role, Session } from './auth';

/**
 * The session to save after re-reading the signed-in user, or null to save nothing. An answer only applies to the
 * session it was asked for: if the session changed while it was in flight (logout, or another tab saving a newer
 * role), the late answer is dropped rather than overwriting the newer one.
 */
export function refreshedSession(
  started: Session,
  current: Session | null,
  me: { role?: string; displayName?: string },
): Session | null {
  if (!current || JSON.stringify(current) !== JSON.stringify(started)) return null;
  const role = (me.role ?? current.role) as Role;
  const displayName = me.displayName ?? current.displayName;
  if (role === current.role && displayName === current.displayName) return null;
  return { ...current, role, displayName };
}
