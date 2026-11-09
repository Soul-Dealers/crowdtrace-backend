import { CopyChip } from './copy-chip';
import { EMERGENCY_LINES } from './emergency-card';
import { Panel } from './panel';

const RULES: React.ReactNode[] = [
  'Share facts, not rumors or accusations',
  'Never post a family’s private details or addresses',
  <>
    Urgent Sighting? Call the case number or the police first
    <span className="mt-2 flex flex-wrap gap-2">
      {EMERGENCY_LINES.map((line) => <CopyChip key={line.number} value={line.number} label={line.label} />)}
    </span>
  </>,
  'Be kind. Families read these threads',
];

export function CommunityRules() {
  return (
    <Panel title="Community Rules" mono className="bg-background" bodyClassName="space-y-4 p-4">
      <ol className="space-y-4">
        {RULES.map((rule, index) => (
          <li key={index} className="flex gap-3 text-sm text-foreground/90">
            <span className="flex size-6 shrink-0 items-center justify-center rounded-md bg-chip text-xs">{index + 1}</span>
            <span className="pt-0.5">{rule}</span>
          </li>
        ))}
      </ol>
    </Panel>
  );
}
