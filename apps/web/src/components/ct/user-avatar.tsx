import { Navii } from '@usenavii/react';
import { cn } from '@/lib/utils';

const px = { sm: 28, md: 36, lg: 40, xl: 64 } as const;

const rings = {
  brand: 'ring-[#3d2e17]',
  expert: 'ring-[#2e2850]',
} as const;

/** Default profile picture: a Navii mascot that is always the same for the same seed. */
export function UserAvatar({ seed, size = 'md', tone = 'brand' }: {
  seed: string;
  size?: keyof typeof px;
  tone?: keyof typeof rings;
}) {
  const s = px[size];
  return (
    <span aria-hidden className={cn('inline-flex shrink-0 overflow-hidden rounded-full ring-1', rings[tone])}
      style={{ width: s, height: s }}>
      <Navii seed={seed} size={s} background="solid" />
    </span>
  );
}
