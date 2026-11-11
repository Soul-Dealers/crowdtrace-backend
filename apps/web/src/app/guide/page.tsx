import type { Metadata } from 'next';
import Link from 'next/link';
import { WithRail } from '@/components/ct/app-shell';
import { CommunityRules } from '@/components/ct/community-rules';
import { PageHeader } from '@/components/ct/page-header';
import { Panel } from '@/components/ct/panel';

export const metadata: Metadata = { title: 'Posting Guide' };

const SECTIONS: { title: string; points: React.ReactNode[] }[] = [
  {
    title: 'Before you post',
    points: [
      'If someone is in danger right now, call the police first. A post is not an emergency line.',
      'File a report with the police. Every case needs a copy of that report before it can go live.',
      'Use a phone number you will answer. It is shown on the case so people with information can call.',
    ],
  },
  {
    title: 'What the public sees',
    points: [
      'Name, age, gender, region, when and where they were last seen, a description, clothing, what happened, photos and your contact number.',
      'Nothing goes public until a moderator has checked it against the police report.',
    ],
  },
  {
    title: 'What only moderators see',
    points: [
      'The police report, your relationship to the person, medical conditions, known associates, vehicle details and social media handles.',
      'These private details and the police report are kept while the case is open, then deleted 12 months after it is closed.',
    ],
  },
  {
    title: 'Good photos',
    points: [
      'Recent, clear, face to the camera, and only the missing person in the frame.',
      'Up to 5 photos. Location and device data are removed before they are stored.',
    ],
  },
  {
    title: 'After you submit',
    points: [
      'Cases about children go to the front of the review queue.',
      'If a moderator thinks another case may be the same person, they compare the two. Your submission is never blocked for this.',
      'Track review from My Cases. Once the case is live you can update its status, for example to found safe, and add a closing statement.',
      'False or hoax cases are rejected or taken down.',
    ],
  },
];

export default function GuidePage() {
  return (
    <WithRail rail={<CommunityRules />}>
      <div className="space-y-6">
        <PageHeader
          title="Posting Guide"
          description="How to file a case that a moderator can approve quickly, and what happens to your information."
          actions={
            <Link href="/report"
              className="rounded-lg bg-primary px-3 py-1.5 text-sm font-medium text-primary-foreground hover:bg-primary/90">
              File a case
            </Link>
          }
        />
        {SECTIONS.map((section) => (
          <Panel key={section.title} title={section.title} bodyClassName="p-4">
            <ul className="list-disc space-y-2 pl-5 text-sm text-foreground/90 marker:text-brand">
              {section.points.map((point, index) => <li key={index}>{point}</li>)}
            </ul>
          </Panel>
        ))}
      </div>
    </WithRail>
  );
}
