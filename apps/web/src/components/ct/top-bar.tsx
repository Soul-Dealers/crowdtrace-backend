'use client';

import Link from 'next/link';
import { useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { Bell, Megaphone, Menu, Search } from 'lucide-react';
import { useEffect, useRef } from 'react';
import { api } from '@/lib/api/client';
import { clearSession, useSession } from '@/lib/auth';
import { useUnreadCount } from '@/lib/notifications';
import { BrandMark } from './brand-mark';

function JumpSearch({ className }: { className?: string }) {
  const router = useRouter();
  const input = useRef<HTMLInputElement>(null);
  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement;
      if (event.key === '/' && !['INPUT', 'TEXTAREA'].includes(target.tagName) && !target.isContentEditable) {
        event.preventDefault();
        input.current?.focus();
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  return (
    <form role="search" className={className} onSubmit={(event) => {
      event.preventDefault();
      const name = input.current?.value.trim();
      router.push(name ? `/cases?name=${encodeURIComponent(name)}` : '/cases');
    }}>
      <label className="flex h-9 items-center gap-2 rounded-full border border-border bg-muted px-3 text-sm text-muted-foreground focus-within:border-brand/60">
        <Search className="size-4" aria-hidden />
        <span className="sr-only">Jump to a case or person</span>
        <input ref={input} placeholder="Jump to a case or person"
          className="min-w-0 flex-1 bg-transparent text-foreground placeholder:text-muted-foreground focus:outline-none" />
        <kbd className="font-mono text-xs text-text-subtle">/</kbd>
      </label>
    </form>
  );
}

function NotificationBell() {
  const unread = useUnreadCount();
  const label = unread ? `Notifications, ${unread} unread` : 'Notifications';
  return (
    <Link href="/notifications" aria-label={label} title={label}
      className="relative rounded-lg p-2 text-text-secondary hover:text-foreground">
      <Bell className="size-5" />
      {unread > 0 && (
        <span aria-hidden className="absolute right-0.5 top-0.5 min-w-4 rounded-full bg-brand px-1 text-center text-[10px] font-semibold leading-4 text-black">
          {unread > 99 ? '99+' : unread}
        </span>
      )}
    </Link>
  );
}

function Account() {
  const session = useSession();
  const router = useRouter();
  const qc = useQueryClient();
  if (!session) {
    return (
      <>
        <Link href="/login" className="hidden px-2 text-sm text-text-secondary hover:text-foreground sm:block">Sign In</Link>
        <Link href="/signup" className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90">Join</Link>
      </>
    );
  }
  async function logout() {
    await api.POST('/api/v1/auth/logout').catch(() => undefined);
    clearSession();
    qc.clear(); // don't leak the previous account's cached data to the next login
    router.push('/');
  }
  return (
    <>
      <NotificationBell />
      <Link href="/account" className="max-w-40 truncate px-2 text-sm text-text-secondary hover:text-foreground">
        {session.displayName}
      </Link>
      <button type="button" onClick={logout}
        className="rounded-lg border border-border px-3 py-1.5 text-sm text-text-secondary hover:text-foreground">
        Log out
      </button>
    </>
  );
}

export function TopBar({ onOpenMenu }: { onOpenMenu: () => void }) {
  return (
    <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-border bg-background/95 px-4 backdrop-blur lg:gap-6 lg:px-6">
      <button type="button" onClick={onOpenMenu} aria-label="Open menu"
        className="flex size-9 items-center justify-center rounded-lg bg-black ring-1 ring-border lg:hidden">
        <Menu className="size-4" />
      </button>
      <div className="lg:w-[232px]"><BrandMark /></div>
      <JumpSearch className="hidden w-full max-w-xs md:block" />
      <div className="ml-auto flex items-center gap-2">
        <Link href="/cases" aria-label="Search cases" className="p-2 text-text-secondary md:hidden"><Search className="size-5" /></Link>
        <Link href="/report" className="hidden items-center gap-2 px-2 text-sm font-medium hover:text-brand md:flex">
          <Megaphone className="size-4" /> Report A Case
        </Link>
        <span className="hidden h-6 w-px bg-border md:block" />
        <Account />
      </div>
    </header>
  );
}
