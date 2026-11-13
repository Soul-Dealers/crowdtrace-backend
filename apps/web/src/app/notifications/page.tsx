'use client';

import { useQuery } from '@tanstack/react-query';
import { useRouter } from 'next/navigation';
import { RequireAuth } from '@/components/app/require-auth';
import { Panel } from '@/components/ct/panel';
import { api, errorMessage, unwrap, type Schemas } from '@/lib/api/client';
import { timeAgo } from '@/lib/case-display';
import { type Notification, useMarkRead, useNotifications } from '@/lib/notifications';
import { cn } from '@/lib/utils';

const STATUS: Record<string, string> = {
  MISSING: 'missing again', FOUND_SAFE: 'found safe', FOUND_DECEASED: 'found deceased', CLOSED: 'closed',
};

/** Notifications hold ids only; the name comes from the public case, shared with the case page's cache. */
function useCaseName(caseId: number) {
  const query = useQuery({
    queryKey: ['public-case', String(caseId)],
    retry: false,
    queryFn: async () => unwrap<Schemas['PublicCaseResponse']>(
      await api.GET('/api/public/cases/{caseId}', { params: { path: { caseId: String(caseId) } } })),
  });
  return query.data?.fullName;
}

function Row({ n }: { n: Notification }) {
  const router = useRouter();
  const markRead = useMarkRead();
  const name = useCaseName(n.caseId!) ?? 'a case you follow';
  const text = n.type === 'NEW_COMMENT'
    ? `New comment on ${name}`
    : `${name} was marked ${STATUS[n.caseStatus ?? ''] ?? 'updated'}`;
  function open() {
    if (!n.read) markRead.mutate(n.id!);
    router.push(`/cases/${n.caseId}`);
  }
  return (
    <li>
      <button type="button" onClick={open}
        className="flex w-full items-start gap-3 px-4 py-3 text-left hover:bg-secondary/60">
        <span aria-hidden className={cn('mt-1.5 size-2 shrink-0 rounded-full', n.read ? 'bg-transparent' : 'bg-brand')} />
        <span className="flex-1">
          <span className={cn('block text-sm', n.read ? 'text-text-secondary' : 'font-medium text-foreground')}>{text}</span>
          <span className="block text-xs text-text-subtle">{timeAgo(n.createdAt!)}</span>
        </span>
        {!n.read && <span className="sr-only">Unread</span>}
      </button>
    </li>
  );
}

function Notifications() {
  const query = useNotifications();
  const items = query.data?.pages.flatMap((page) => page.content ?? []) ?? [];
  return (
    <div className="space-y-5">
      <header>
        <h1 className="text-2xl font-semibold sm:text-3xl">Notifications</h1>
        <p className="mt-1 text-sm text-muted-foreground">New comments and status changes on cases you follow.</p>
      </header>
      {query.isError && <p className="text-sm text-destructive">{errorMessage(query.error)}</p>}
      {query.isPending ? (
        <div className="h-40 animate-pulse rounded-2xl bg-muted" aria-busy />
      ) : items.length === 0 ? (
        <Panel><p className="p-6 text-center text-sm text-muted-foreground">
          Nothing yet. Follow a case to hear about new tips and status changes.
        </p></Panel>
      ) : (
        <Panel bodyClassName="p-0">
          <ul className="divide-y divide-border">{items.map((n) => <Row key={n.id} n={n} />)}</ul>
        </Panel>
      )}
      {query.hasNextPage && (
        <button type="button" onClick={() => query.fetchNextPage()} disabled={query.isFetchingNextPage}
          className="rounded-lg border border-border px-3 py-1.5 text-sm text-text-secondary hover:text-foreground disabled:opacity-60">
          {query.isFetchingNextPage ? 'Loading…' : 'Load older'}
        </button>
      )}
    </div>
  );
}

export default function NotificationsPage() {
  return <RequireAuth><Notifications /></RequireAuth>;
}
