import Link from 'next/link';
import { Check, CircleAlert, Eye, Plus } from 'lucide-react';
import { cn } from '@/lib/utils';
import { Panel } from './panel';

export type FeedItem =
  | { id: string; time: string; kind: 'sighting' | 'official'; actor: string; actorRole?: 'Police' | 'NGO';
      caseName: string; caseId: number; body: string }
  | { id: string; time: string; kind: 'published'; caseName: string; caseId: number }
  | { id: string; time: string; kind: 'found'; summary: string; caseId: number };

const ICONS = {
  sighting: { Icon: Eye, className: 'bg-[#172239] text-info' },
  official: { Icon: CircleAlert, className: 'bg-[#172239] text-[#a9b8ff]' },
  published: { Icon: Plus, className: 'bg-[#2a2114] text-brand' },
  found: { Icon: Check, className: 'bg-[#14261d] text-success' },
} as const;

function CaseLink({ id, name }: { id: number; name: string }) {
  return <Link href={`/cases/${id}`} className="text-info hover:underline">{name}</Link>;
}

/** Community activity across cases. Data comes from props; there is no feed API yet. */
export function FeedList({ items }: { items: FeedItem[] }) {
  return (
    <Panel title="Feed" className="bg-panel" bodyClassName="divide-y divide-transparent px-4 py-2">
      {items.length === 0 && <p className="py-6 text-sm text-muted-foreground">No activity yet.</p>}
      <ol>
        {items.map((item) => {
          const { Icon, className } = ICONS[item.kind];
          return (
            <li key={item.id} className="grid grid-cols-[3rem_1.75rem_1fr] items-start gap-x-3 py-3 text-[15px]">
              <span className="pt-0.5 tabular-nums text-muted-foreground">{item.time}</span>
              <span className={cn('flex size-7 items-center justify-center rounded-full [&_svg]:size-3.5', className)}><Icon /></span>
              <div className="min-w-0 space-y-2 pt-0.5">
                {item.kind === 'published' ? (
                  <p className="text-muted-foreground">Case approved and published for <CaseLink id={item.caseId} name={item.caseName} />.</p>
                ) : item.kind === 'found' ? (
                  <p className="text-foreground/90">{item.summary}</p>
                ) : (
                  <>
                    <p className="text-muted-foreground">
                      <span className="text-foreground">{item.actor}</span>
                      {item.actorRole && <span className="text-success"> <Check className="inline size-3.5" /> {item.actorRole}</span>}
                      {' '}reported on <CaseLink id={item.caseId} name={item.caseName} />
                    </p>
                    <blockquote className="rounded-xl border-l-2 border-border bg-black px-4 py-3 text-foreground/90">{item.body}</blockquote>
                  </>
                )}
              </div>
            </li>
          );
        })}
      </ol>
    </Panel>
  );
}
