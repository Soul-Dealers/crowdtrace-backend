'use client';

import Link from 'next/link';
import { Plus, X } from 'lucide-react';
import { Suspense, useEffect, useState } from 'react';
import { usePathname } from 'next/navigation';
import { SideNav } from './side-nav';
import { TopBar } from './top-bar';

/** Top bar, left navigation and the page. Below lg the nav moves into a drawer. */
export function AppShell({ children }: { children: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const pathname = usePathname();
  useEffect(() => setOpen(false), [pathname]);

  const sidebar = (
    <div className="p-4">
      <Suspense><SideNav onNavigate={() => setOpen(false)} /></Suspense>
    </div>
  );

  return (
    <div className="min-h-screen">
      <TopBar onOpenMenu={() => setOpen(true)} />
      <div className="flex">
        <aside className="sticky top-16 hidden h-[calc(100vh-4rem)] w-[264px] shrink-0 overflow-y-auto border-r border-border lg:block">
          {sidebar}
        </aside>
        <main className="min-w-0 flex-1 px-4 py-5 lg:px-6">{children}</main>
      </div>

      {open && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal aria-label="Menu">
          <button type="button" aria-label="Close menu" onClick={() => setOpen(false)} className="absolute inset-0 bg-black/70" />
          <div className="absolute inset-y-0 left-0 w-[280px] overflow-y-auto border-r border-border bg-background">
            <button type="button" aria-label="Close menu" onClick={() => setOpen(false)}
              className="absolute right-3 top-3 rounded-full p-1.5 text-text-secondary hover:bg-secondary"><X className="size-4" /></button>
            <div className="pt-8">{sidebar}</div>
          </div>
        </div>
      )}

      {pathname !== '/report' && (
        <Link href="/report" aria-label="Report a missing person"
          className="fixed bottom-6 right-5 z-30 flex size-14 items-center justify-center rounded-full bg-brand-strong text-white shadow-[0_8px_30px_rgb(244_115_50/0.45)] lg:hidden">
          <Plus className="size-6" />
        </Link>
      )}
    </div>
  );
}

/** Main column plus the right rail used on Home and case pages; the rail drops below on narrow screens. */
export function WithRail({ children, rail }: { children: React.ReactNode; rail: React.ReactNode }) {
  return (
    <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_360px]">
      <div className="min-w-0">{children}</div>
      <aside className="space-y-6">{rail}</aside>
    </div>
  );
}
