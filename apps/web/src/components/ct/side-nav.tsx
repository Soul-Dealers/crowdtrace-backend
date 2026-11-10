'use client';

import Link from 'next/link';
import { usePathname, useSearchParams } from 'next/navigation';
import {
  BadgeCheck, CheckCheck, ClipboardList, FileText, FolderOpen, House, Megaphone, MessageCircle, SearchCheck,
  ShieldCheck, UsersRound,
} from 'lucide-react';
import { isAdmin, useSession } from '@/lib/auth';
import { cn } from '@/lib/utils';
import { CountBadge } from './pill';

type Item = {
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  /** Missing href = feature not built yet; shown muted and not clickable. */
  href?: string;
  count?: React.ReactNode;
  highlight?: boolean;
};

function NavItem({ item, active, onNavigate }: { item: Item; active: boolean; onNavigate?: () => void }) {
  const body = (
    <>
      <item.icon className={cn('size-4', active ? 'text-brand' : 'text-text-secondary')} />
      <span className="flex-1">{item.label}</span>
      {item.count != null && <CountBadge highlight={item.highlight}>{item.count}</CountBadge>}
    </>
  );
  const base = 'flex items-center gap-3 rounded-lg px-2.5 py-1.5 text-[15px]';
  if (!item.href) {
    return <span aria-disabled title="Coming soon" className={cn(base, 'cursor-not-allowed text-text-subtle')}>{body}</span>;
  }
  return (
    <Link href={item.href} onClick={onNavigate} aria-current={active ? 'page' : undefined}
      className={cn(base, active ? 'bg-secondary text-brand ring-1 ring-white/5'
        : 'text-text-secondary hover:bg-secondary/60 hover:text-foreground')}>
      {body}
    </Link>
  );
}

export function SideNav({ browseCount, onNavigate }: { browseCount?: number; onNavigate?: () => void }) {
  const pathname = usePathname();
  const status = useSearchParams().get('status');
  const session = useSession();

  const sections: { title: string; items: (Item & { active: boolean })[] }[] = [
    {
      title: 'Overview',
      items: [
        { label: 'Home', icon: House, href: '/', active: pathname === '/' },
        { label: 'Browse Cases', icon: SearchCheck, href: '/cases', count: browseCount,
          active: pathname.startsWith('/cases') && status !== 'FOUND_SAFE' },
        { label: 'Found Safe', icon: CheckCheck, href: '/cases?status=FOUND_SAFE',
          active: pathname === '/cases' && status === 'FOUND_SAFE' },
      ],
    },
    {
      title: 'Operations',
      items: [
        ...(session ? [{ label: 'My Cases', icon: FolderOpen, href: '/my/cases', active: pathname.startsWith('/my') }] : []),
        { label: 'Following', icon: UsersRound, active: false },
        { label: 'Your Sightings', icon: MessageCircle, active: false },
      ],
    },
    {
      title: 'Contribute',
      items: [
        { label: 'File A Case', icon: Megaphone, href: '/report', active: pathname === '/report' },
        { label: 'Posting Guide', icon: FileText, href: '/guide', active: pathname === '/guide' },
        { label: 'Get Verified', icon: BadgeCheck, href: '/account', active: pathname === '/account' },
      ],
    },
    ...(isAdmin(session) ? [{
      title: 'Moderation',
      items: [
        { label: 'Review Queue', icon: ClipboardList, href: '/admin/review', active: pathname.startsWith('/admin/review') },
        { label: 'Verifications', icon: ShieldCheck, href: '/admin/verifications',
          active: pathname.startsWith('/admin/verifications') },
      ],
    }] : []),
  ];

  return (
    <nav aria-label="Main" className="space-y-6">
      {sections.map((section) => (
        <div key={section.title}>
          <h2 className="mb-2 px-2.5 text-xs uppercase tracking-wide text-text-subtle">{section.title}</h2>
          <ul className="space-y-0.5">
            {section.items.map((item) => (
              <li key={item.label}><NavItem item={item} active={item.active} onNavigate={onNavigate} /></li>
            ))}
          </ul>
        </div>
      ))}
    </nav>
  );
}
