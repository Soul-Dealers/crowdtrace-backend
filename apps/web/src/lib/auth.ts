'use client';

import { useSyncExternalStore } from 'react';

export type Role = 'REGISTERED_USER' | 'MODERATOR' | 'SUPER_ADMIN';

export interface Session {
  token: string;
  displayName: string;
  email: string;
  role: Role;
}

const KEY = 'crowdtrace.session';
const listeners = new Set<() => void>();
let cachedRaw: string | null = null;
let cached: Session | null = null;

function read(): Session | null {
  const raw = localStorage.getItem(KEY);
  if (raw !== cachedRaw) {
    cachedRaw = raw;
    cached = raw ? (JSON.parse(raw) as Session) : null;
  }
  return cached;
}

function emit() {
  listeners.forEach((l) => l());
}

/** The backend issues a 15-minute JWT and has no refresh endpoint, so the session lives in localStorage. */
export function saveSession(session: Session) {
  localStorage.setItem(KEY, JSON.stringify(session));
  emit();
}

export function clearSession() {
  localStorage.removeItem(KEY);
  emit();
}

export function getToken(): string | null {
  return typeof window === 'undefined' ? null : (read()?.token ?? null);
}

export function isAdmin(session: Session | null) {
  return session?.role === 'MODERATOR' || session?.role === 'SUPER_ADMIN';
}

export function useSession(): Session | null {
  return useSyncExternalStore(
    (l) => {
      listeners.add(l);
      window.addEventListener('storage', l);
      return () => {
        listeners.delete(l);
        window.removeEventListener('storage', l);
      };
    },
    read,
    () => null,
  );
}
