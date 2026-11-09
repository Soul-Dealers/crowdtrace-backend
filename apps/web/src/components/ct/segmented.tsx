'use client';

import { cn } from '@/lib/utils';

/** Pill-shaped single-choice control: status filters on Browse, sort order on a discussion. */
export function Segmented<T extends string>({ options, value, onChange, className, label }: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (value: T) => void;
  className?: string;
  label: string;
}) {
  return (
    <div role="radiogroup" aria-label={label}
      className={cn('inline-flex items-center gap-0.5 rounded-full bg-[#191919] p-1', className)}>
      {options.map((option) => (
        <button key={option.value} type="button" role="radio" aria-checked={option.value === value}
          onClick={() => onChange(option.value)}
          className={cn('rounded-full px-3 py-1 text-sm transition-colors',
            option.value === value ? 'bg-[#474747] text-foreground' : 'text-muted-foreground hover:text-foreground')}>
          {option.label}
        </button>
      ))}
    </div>
  );
}
