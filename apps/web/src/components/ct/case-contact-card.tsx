'use client';

import { Check, Copy, Link2, Phone, Plus } from 'lucide-react';
import { useState } from 'react';
import { formatPhone } from '@/lib/case-display';
import { cn } from '@/lib/utils';
import { copyText } from './copy-chip';

const pronoun = { FEMALE: 'her', MALE: 'him', UNKNOWN: 'them' } as const;

function useFlash() {
  const [flashed, setFlashed] = useState<string | null>(null);
  return {
    flashed,
    async copy(key: string, text: string) {
      if (await copyText(text)) {
        setFlashed(key);
        setTimeout(() => setFlashed(null), 1500);
      }
    },
  };
}

/**
 * The family's public contact number with call / copy / share actions.
 * Follow is disabled until the follow API exists (pass onFollow to enable it).
 */
export function CaseContactCard({ phone, gender, shareUrl, onFollow, className }: {
  phone: string;
  gender: keyof typeof pronoun;
  shareUrl: string;
  onFollow?: () => void;
  className?: string;
}) {
  const { flashed, copy } = useFlash();
  const icon = (key: string, Icon: typeof Copy) => flashed === key ? <Check className="text-success" /> : <Icon />;
  return (
    <section className={cn('overflow-hidden rounded-2xl border border-[#3a2a20] bg-gradient-to-b from-black from-40% to-[#531c01]', className)}>
      <div className="px-4 pb-3 pt-4">
        <p className="text-sm text-warning">If you have seen {pronoun[gender]}, call</p>
        <p className="mt-1 select-all font-mono text-2xl font-semibold tracking-wide">{formatPhone(phone)}</p>
      </div>
      <div className="space-y-3 border-t border-white/10 px-4 pb-4 pt-3">
        <div className="flex flex-wrap gap-2 [&_svg]:size-3.5">
          <a href={`tel:${phone.replace(/\s/g, '')}`}
            className="inline-flex items-center gap-1.5 rounded-lg bg-[#ff7a3d] px-3 py-1.5 text-sm font-semibold text-white sm:hidden">
            <Phone /> Call
          </a>
          <button type="button" onClick={onFollow} disabled={!onFollow} title={onFollow ? undefined : 'Following is coming soon'}
            className="hidden items-center gap-1.5 rounded-lg bg-white px-3 py-1.5 text-sm font-semibold text-black disabled:opacity-60 sm:inline-flex">
            Follow <Plus />
          </button>
          <button type="button" onClick={() => copy('number', phone)}
            className="inline-flex items-center gap-1.5 rounded-lg bg-info px-3 py-1.5 text-sm font-semibold text-black">
            Copy Number {icon('number', Copy)}
          </button>
          <button type="button" onClick={() => copy('link', shareUrl)}
            className="inline-flex items-center gap-1.5 rounded-lg border border-white/15 bg-black px-3 py-1.5 text-sm font-semibold">
            Copy Link {icon('link', Link2)}
          </button>
        </div>
        <p className="text-xs text-[#e9b9a1]/80">
          This number belongs to the family or a contact they chose. It is not the family’s private account.
        </p>
      </div>
    </section>
  );
}
