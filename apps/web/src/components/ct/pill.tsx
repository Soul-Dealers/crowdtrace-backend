import { cn } from '@/lib/utils';

const tones = {
  neutral: 'bg-secondary text-text-secondary',
  warning: 'bg-[#3c3c3b] text-warning',
  danger: 'bg-[#191818] text-destructive',
  brand: 'bg-[#2a2114] text-[#ff8d28]',
  pinned: 'bg-[#2a2114] text-warning',
  expert: 'bg-[#201c35] text-[#cbc6ff]',
  success: 'bg-[#14261d] text-success',
} as const;

export type PillTone = keyof typeof tones;

/** Small rounded label: case status ("Missing"), "Day 9", "Under 18", poster roles. */
export function Pill({ tone = 'neutral', icon, children, className }: {
  tone?: PillTone;
  icon?: React.ReactNode;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <span className={cn('inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs font-medium [&_svg]:size-3',
      tones[tone], className)}>
      {icon}
      {children}
    </span>
  );
}

/** Count badge used in the side navigation ("6", "5 new"). */
export function CountBadge({ children, highlight }: { children: React.ReactNode; highlight?: boolean }) {
  return (
    <span className={cn('rounded-md bg-secondary px-1.5 py-0.5 text-xs leading-none',
      highlight ? 'text-brand' : 'text-text-secondary')}>
      {children}
    </span>
  );
}
