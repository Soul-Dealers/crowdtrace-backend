'use client';

import { Copy, Check } from 'lucide-react';
import { useState } from 'react';
import { copyText } from './copy-chip';

export const EMERGENCY_LINES = [
  { number: '191', label: 'Police' },
  { number: '18555', label: 'Police Toll-Free' },
  { number: '112', label: 'All Emergencies' },
] as const;

/** "Someone in danger now": tapping a line copies the number. */
export function EmergencyCard() {
  const [copied, setCopied] = useState<string | null>(null);
  return (
    <section aria-label="Emergency numbers"
      className="overflow-hidden rounded-2xl border border-[#3a1416] bg-gradient-to-b from-danger-deep via-[#2a0d0e] to-black">
      <div className="px-3.5 pb-3 pt-3.5">
        <h2 className="font-mono text-sm font-semibold">Someone in danger now</h2>
        <p className="mt-1 text-xs text-[#d8b4b5]">Call before you post. Tap a line to copy it.</p>
      </div>
      <ul className="border-t border-[#3a1416]">
        {EMERGENCY_LINES.map((line) => (
          <li key={line.number}>
            <button type="button"
              onClick={async () => {
                if (await copyText(line.number)) {
                  setCopied(line.number);
                  setTimeout(() => setCopied(null), 1500);
                }
              }}
              className="flex w-full items-center gap-3 px-3.5 py-2.5 text-left text-sm hover:bg-white/5">
              <span className="rounded-md bg-chip px-1.5 py-0.5 font-mono text-xs">{line.number}</span>
              <span className="flex-1 select-text">{line.label}</span>
              {copied === line.number
                ? <Check className="size-3.5 text-success" aria-label="Copied" />
                : <Copy className="size-3.5 text-text-secondary" aria-hidden />}
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
