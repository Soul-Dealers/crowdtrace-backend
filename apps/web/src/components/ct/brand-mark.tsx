import Link from 'next/link';
import { MapPin } from 'lucide-react';

export function BrandMark() {
  return (
    <Link href="/" className="flex items-center gap-2.5" aria-label="Over Here home">
      <span className="flex size-10 items-center justify-center rounded-xl bg-gradient-to-b from-brand-strong to-brand-deep shadow-[inset_0_1px_0_rgb(255_255_255/0.25)]">
        <MapPin className="size-5 text-[#ffd9c4]" strokeWidth={2.25} />
      </span>
      <span className="leading-none">
        <span className="block text-xl font-semibold tracking-tight">ovrhr</span>
        <span className="block text-[11px] tracking-wide text-text-secondary">GHANA</span>
      </span>
    </Link>
  );
}
