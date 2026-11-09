'use client';

import { useQuery } from '@tanstack/react-query';
import Link from 'next/link';
import { useParams } from 'next/navigation';
import { useState } from 'react';
import { WithRail } from '@/components/ct/app-shell';
import { CaseContactCard } from '@/components/ct/case-contact-card';
import { CasePhoto } from '@/components/ct/case-photo';
import { CommunityRules } from '@/components/ct/community-rules';
import { Panel } from '@/components/ct/panel';
import { Pill, type PillTone } from '@/components/ct/pill';
import { SightingsBars } from '@/components/ct/sightings-bars';
import { api, errorMessage, unwrap, type Schemas } from '@/lib/api/client';
import { caseKindLabel, daysSince, formatShortDate, humanRegion } from '@/lib/case-display';
import { cn } from '@/lib/utils';

type PublicCase = Schemas['PublicCaseResponse'];

const STATUS: Record<NonNullable<PublicCase['caseStatus']>, { label: string; tone: PillTone }> = {
  MISSING: { label: 'Missing', tone: 'warning' },
  FOUND_SAFE: { label: 'Found safe', tone: 'success' },
  FOUND_DECEASED: { label: 'Found deceased', tone: 'neutral' },
  CLOSED: { label: 'Closed', tone: 'neutral' },
};

function Profile({ c }: { c: PublicCase }) {
  const rows: [string, React.ReactNode][] = [
    ['Last seen', c.lastSeenDate ? formatShortDate(c.lastSeenDate) : null],
    ['Last seen at', c.lastSeenLocation],
    ['Physical description', c.physicalDescription],
    ['Clothing', c.clothing],
    ['Circumstances', c.circumstances],
    ['Published', c.approvedAt ? formatShortDate(c.approvedAt) : null],
    ['Resolved', c.resolvedAt ? formatShortDate(c.resolvedAt) : null],
  ];
  return (
    <dl className="divide-y divide-border">
      {rows.filter(([, value]) => value).map(([label, value]) => (
        <div key={label} className="grid gap-1 px-4 py-3 sm:grid-cols-[200px_1fr]">
          <dt className="text-sm text-muted-foreground">{label}</dt>
          <dd className="whitespace-pre-line text-[15px]">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

function Discussion({ name }: { name: string }) {
  return (
    <div>
      <div className="space-y-2 border-b border-border bg-gradient-to-b from-panel to-[#372f14]/60 p-4">
        <label htmlFor="sighting" className="text-sm text-muted-foreground">Share what you saw</label>
        <textarea id="sighting" disabled rows={3}
          placeholder={`Where and when did you see ${name.split(' ')[0]}? What were they doing, and who were they with?`}
          className="w-full resize-none rounded-xl border border-border bg-black p-3 text-sm placeholder:text-text-subtle disabled:cursor-not-allowed" />
        <div className="flex items-center justify-between gap-4">
          <p className="text-xs text-muted-foreground">Say what you saw yourself. Don’t name suspects or post anyone’s address.</p>
          <button type="button" disabled className="rounded-lg bg-white px-3 py-1.5 text-sm font-semibold text-black disabled:opacity-50">Post</button>
        </div>
      </div>
      <p className="p-6 text-center text-sm text-muted-foreground">
        Tips and updates open soon. If you have seen this person, call the number above or the police now.
      </p>
    </div>
  );
}

export default function PublicCasePage() {
  const { id } = useParams<{ id: string }>();
  const [tab, setTab] = useState<'profile' | 'discussion'>('profile');
  const query = useQuery({
    queryKey: ['public-case', id],
    queryFn: async () =>
      unwrap<PublicCase>(await api.GET('/api/public/cases/{caseId}', { params: { path: { caseId: id } } })),
  });

  if (query.isPending) return <div className="h-80 animate-pulse rounded-2xl bg-muted" aria-busy />;
  if (query.isError) {
    return (
      <div className="space-y-3 py-10 text-center">
        <p className="text-lg">This case isn’t available.</p>
        <p className="text-sm text-muted-foreground">{errorMessage(query.error)}</p>
        <Link href="/cases" className="text-info hover:underline">Browse cases</Link>
      </div>
    );
  }

  const c = query.data;
  const status = STATUS[c.caseStatus!];
  const missing = c.caseStatus === 'MISSING';
  const deceased = c.presentation === 'DECEASED_NOTICE';

  return (
    <WithRail rail={<>
      <Panel title="Sightings, last 14 days" meta={<span className="text-xs">Not tracked yet</span>} bodyClassName="p-4">
        <SightingsBars counts={null} />
      </Panel>
      <CommunityRules />
    </>}>
      <nav aria-label="Breadcrumb" className="mb-4 text-sm text-muted-foreground">
        <Link href="/cases" className="hover:text-foreground">Browse Cases</Link> / <span className="text-foreground">{c.fullName}</span>
      </nav>

      <div className="grid gap-6 md:grid-cols-[minmax(0,264px)_1fr]">
        <CasePhoto caseId={c.id!} alt={c.fullName!} className="aspect-[4/3]" />
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">{caseKindLabel(c.age!)} · Case {c.id}</p>
          <h1 className="text-3xl font-semibold">{c.fullName}</h1>
          <p className="text-sm text-text-secondary">
            {c.age} years old · {c.gender === 'UNKNOWN' ? 'Gender not stated' : c.gender === 'FEMALE' ? 'Female' : 'Male'} · {humanRegion(c.region!)} Region
          </p>
          <div className="flex flex-wrap gap-2">
            <Pill tone={status.tone}>{status.label}</Pill>
            {missing && <Pill>Day <b className="text-foreground">{daysSince(c.lastSeenDate!)}</b></Pill>}
            {c.age! < 18 && <Pill tone="danger">Under 18</Pill>}
          </div>
          {c.closingStatement && (
            <p className="rounded-xl border border-border bg-panel p-3 text-sm">{c.closingStatement}</p>
          )}
          {deceased && (
            <p className="text-sm text-muted-foreground">
              Out of respect for the family, details and the contact number are no longer shown.
            </p>
          )}
          {missing && c.publicContactNumber && (
            <CaseContactCard phone={c.publicContactNumber} gender={c.gender === 'FEMALE' || c.gender === 'MALE' ? c.gender : 'UNKNOWN'} sharePath={`/cases/${c.id}`} className="max-w-md" />
          )}
        </div>
      </div>

      <div role="tablist" aria-label="Case sections" className="mt-8 flex gap-6 border-b border-border">
        {([['discussion', 'Discussion'], ['profile', 'Profile']] as const).map(([key, label]) => (
          <button key={key} type="button" role="tab" aria-selected={tab === key} onClick={() => setTab(key)}
            className={cn('-mb-px border-b-2 px-1 pb-2 text-[15px]',
              tab === key ? 'border-foreground font-medium' : 'border-transparent text-muted-foreground hover:text-foreground')}>
            {label}
          </button>
        ))}
      </div>
      <Panel className="mt-4 bg-panel" title={tab === 'discussion' ? 'Tips and updates' : undefined}
        meta={tab === 'discussion' ? 'names are pseudonyms' : undefined}>
        {tab === 'profile' ? <Profile c={c} /> : <Discussion name={c.fullName!} />}
      </Panel>
    </WithRail>
  );
}
