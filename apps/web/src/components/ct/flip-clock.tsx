'use client';

import { useEffect, useState } from 'react';
import { cn } from '@/lib/utils';

function parts(since: number, now: number) {
  let s = Math.max(0, Math.floor((now - since) / 1000));
  const days = Math.floor(s / 86_400); s -= days * 86_400;
  const hours = Math.floor(s / 3_600); s -= hours * 3_600;
  const minutes = Math.floor(s / 60);
  return [`${days}d`, String(hours).padStart(2, '0'), String(minutes).padStart(2, '0'), String(s - minutes * 60).padStart(2, '0')];
}

/**
 * Time since a person was last seen, as flip tiles (days, hours, minutes, seconds).
 * The server renders placeholders and the clock starts after mount, so the markup never mismatches on hydration.
 */
export function FlipClock({ since, className }: { since: string; className?: string }) {
  const [now, setNow] = useState<number | null>(null);
  useEffect(() => {
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const start = Date.parse(since.length === 10 ? `${since}T00:00:00Z` : since);
  const tiles = now == null ? ['--', '--', '--', '--'] : parts(start, now);
  return (
    <div role="timer" aria-label="Time since last seen"
      className={cn('inline-flex rounded-xl bg-gradient-to-r from-[#e0484c] to-brand-strong p-[3px] shadow-[0_6px_20px_-8px_rgb(244_115_50/0.6)]', className)}>
      <div className="flex gap-1 rounded-[9px] bg-[#1d1d20] p-1">
        {tiles.map((tile, index) => (
          <span key={index}
            className="relative min-w-9 rounded-md bg-gradient-to-b from-[#3d3e42] to-[#2a2b2f] px-1.5 py-1 text-center font-mono text-lg tabular-nums text-white shadow-[inset_0_1px_0_rgb(255_255_255/0.08)] after:absolute after:inset-x-0 after:top-1/2 after:h-px after:bg-black/50">
            {tile}
          </span>
        ))}
      </div>
    </div>
  );
}
