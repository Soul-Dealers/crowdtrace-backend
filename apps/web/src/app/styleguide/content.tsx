'use client';

import { useState } from 'react';
import { WithRail } from '@/components/ct/app-shell';
import { CaseContactCard } from '@/components/ct/case-contact-card';
import { CasePhoto } from '@/components/ct/case-photo';
import { CaseTile, type CaseTileData } from '@/components/ct/case-tile';
import { CommentThread, type CaseComment } from '@/components/ct/comment-thread';
import { CommunityRules } from '@/components/ct/community-rules';
import { FeedList, type FeedItem } from '@/components/ct/feed-list';
import { MissionCard } from '@/components/ct/mission-card';
import { Panel } from '@/components/ct/panel';
import { Pill } from '@/components/ct/pill';
import { Segmented } from '@/components/ct/segmented';
import { TrendingCaseCard, type TrendingCase } from '@/components/ct/trending-case-card';

const ago = (days: number) => new Date(Date.now() - days * 86_400_000 - 3 * 3_600_000).toISOString();
const bars = [0, 0, 1, 2, 4, 3, 6, 2, 0, 2, 0, 1, 0, 0];

const trending: TrendingCase[] = [1, 2, 3].map((id) => ({
  id, fullName: 'Abena Owusu', age: 14, place: 'Kejetia, Kumasi', lastSeen: ago(9), sightingsByDay: bars,
}));

const tiles: CaseTileData[] = [
  { id: 1, fullName: 'Abena Owusu', age: 14, region: 'ASHANTI', lastSeenDate: ago(9).slice(0, 10), caseStatus: 'MISSING' },
  { id: 2, fullName: 'Kwame Asante-Boateng', age: 34, region: 'GREATER_ACCRA', lastSeenDate: ago(4).slice(0, 10), caseStatus: 'MISSING' },
  { id: 3, fullName: 'Kojo Mensah', age: 7, region: 'ASHANTI', lastSeenDate: ago(20).slice(0, 10), caseStatus: 'FOUND_SAFE' },
  { id: 4, fullName: 'Esi Quaye', age: 16, region: 'CENTRAL', lastSeenDate: ago(2).slice(0, 10), caseStatus: 'MISSING' },
];

const feed: FeedItem[] = [
  { id: 'a', time: '18:39', kind: 'sighting', actor: 'KejetiaWatch', caseName: 'Abena Owusu', caseId: 1,
    body: 'A girl in that uniform waited alone by the Accra-bound buses around 3:30 pm. The station master is asking his drivers.' },
  { id: 'b', time: '17:12', kind: 'official', actor: 'Sgt_Ofori', actorRole: 'Police', caseName: 'Abena Owusu', caseId: 1,
    body: 'If you were at the lorry station that afternoon, call the number on her case. Don’t post suspects’ names.' },
  { id: 'c', time: '11:30', kind: 'published', caseName: 'Esi Quaye', caseId: 4 },
  { id: 'd', time: 'Sun', kind: 'found', caseId: 3, summary: 'Kojo Mensah, 7, Ashanti, was marked found safe. Thank you to 22 contributors.' },
];

const comments: CaseComment[] = [
  { id: '1', author: 'Maame_Owusu', role: 'FAMILY', pinned: true, ago: '1 d ago',
    body: 'Update from the family: a trotro driver on the Kumasi–Accra route thinks he saw Abena at Nkawkaw. We are going there today. Thank you all for sharing.',
    replies: [{ id: '1a', author: 'Ama_ChildSafe', role: 'EXPERT', ago: '1 d ago',
      body: 'Thinking of you all. When you get to Nkawkaw, tell the district police there first so they can support the search.',
      replies: [{ id: '1b', author: 'Maame_Owusu', role: 'FAMILY', ago: '1 d ago', body: 'Thank you. We did, and they were very helpful.' }] }] },
  { id: '2', author: 'OseiObserves', ago: '4 h ago', body: 'Shared in three church WhatsApp groups in Konongo and Nkawkaw. Praying she comes home.',
    replies: [{ id: '2a', author: 'NanaYaa_K', ago: '3 h ago', body: 'Same here, shared in our Konongo market group. Many people are looking.' }] },
];

export function StyleguideContent() {
  const [status, setStatus] = useState('ALL');
  const [sort, setSort] = useState('LATEST');
  return (
    <div className="space-y-10">
      <WithRail rail={<><MissionCard stats={{ openCases: 5, foundSafe: 1, sightings: 11 }} /><CommunityRules /></>}>
        <Panel className="rounded-3xl" bodyClassName="">
          <div className="space-y-2 p-5">
            <h1 className="text-3xl font-semibold">Search status</h1>
            <p className="text-muted-foreground">Where every open case stands right now, and what the community has found today.</p>
          </div>
          <div className="flex flex-wrap items-center gap-2 border-y border-border bg-band px-5 py-3 text-sm text-muted-foreground">
            <span className="size-2 rounded-full bg-warning" />
            <span className="font-mono text-foreground">Top Trending Cases</span>
            <span>· children and anyone missing under 10 days · Last <b className="text-foreground">14</b> Days</span>
          </div>
          <div className="grid divide-border md:grid-cols-3 md:divide-x">
            {trending.map((item) => <TrendingCaseCard key={item.id} item={item} />)}
          </div>
        </Panel>
      </WithRail>

      <section className="space-y-4">
        <h2 className="text-2xl font-semibold">Browse</h2>
        <Segmented label="Case status" value={status} onChange={setStatus} options={[
          { value: 'ALL', label: 'All' }, { value: 'MISSING', label: 'Missing' }, { value: 'FOUND_SAFE', label: 'Found Safe' },
          { value: 'FOUND_DECEASED', label: 'Found Deceased' }, { value: 'CLOSED', label: 'Closed' }]} />
        <div className="grid grid-cols-2 gap-4 lg:grid-cols-4">{tiles.map((t) => <CaseTile key={t.id} item={t} />)}</div>
      </section>

      <section className="grid gap-6 lg:grid-cols-[264px_1fr]">
        <div className="space-y-3">
          <CasePhoto caseId={1} alt="Abena Owusu" className="aspect-[4/3]" />
          <div className="grid grid-cols-3 gap-3">
            {[1, 2, 3].map((i) => <CasePhoto key={i} caseId={1} alt="Abena Owusu" className="aspect-square" />)}
          </div>
        </div>
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">Missing child · CT-GH-2026-0148</p>
          <h2 className="text-3xl font-semibold">Abena Owusu</h2>
          <div className="flex gap-2"><Pill tone="warning">Missing</Pill><Pill>Day <b className="text-foreground">9</b></Pill><Pill tone="danger">Under 18</Pill></div>
          <CaseContactCard phone="0245617720" gender="FEMALE" sharePath="/cases/1" className="max-w-md" />
        </div>
      </section>

      <Panel title="Tips and updates" meta={<><b className="text-foreground">6</b> posts · names are pseudonyms</>} className="bg-panel">
        <div className="flex items-center gap-3 border-b border-border bg-black px-4 py-3 text-sm text-muted-foreground">
          Sort
          <Segmented label="Sort" value={sort} onChange={setSort} options={[
            { value: 'LATEST', label: 'Latest Activity' }, { value: 'NEWEST', label: 'Newest' },
            { value: 'OLDEST', label: 'Oldest' }, { value: 'REPLIES', label: 'Most Replies' }]} />
        </div>
        <CommentThread comments={comments} />
      </Panel>

      <FeedList items={feed} />
    </div>
  );
}
