'use client';

import { Check, Copy } from 'lucide-react';
import { useState } from 'react';
import { cn } from '@/lib/utils';

export async function copyText(text: string): Promise<boolean> {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

/** A number or short code with a copy button; shows a tick for a moment after copying. */
export function CopyChip({ value, label, className }: { value: string; label?: string; className?: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      onClick={async () => {
        if (await copyText(value)) {
          setCopied(true);
          setTimeout(() => setCopied(false), 1500);
        }
      }}
      aria-label={`Copy ${label ?? value}`}
      className={cn(
        'inline-flex items-center gap-1.5 rounded-md bg-chip px-1.5 py-0.5 font-mono text-xs text-foreground hover:bg-secondary',
        className,
      )}
    >
      {value}
      {copied ? <Check className="size-3 text-success" /> : <Copy className="size-3 text-text-secondary" />}
    </button>
  );
}
