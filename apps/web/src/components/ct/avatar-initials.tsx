import { cn } from '@/lib/utils';

const tones = {
  teal: 'bg-[#000a09] text-teal ring-[#003b35]',
  brand: 'bg-[#2a2114] text-[#ff8d28] ring-[#3d2e17]',
  expert: 'bg-[#201c35] text-[#cbc6ff] ring-[#2e2850]',
  neutral: 'bg-[#2a2114] text-[#ff8d28] ring-[#3d2e17]',
} as const;

export function initials(name: string): string {
  const parts = name.replace(/[_\-.]+/g, ' ').trim().split(/\s+/).filter(Boolean);
  return ((parts[0]?.[0] ?? '') + (parts.length > 1 ? parts[parts.length - 1][0] : '')).toUpperCase();
}

export function AvatarInitials({ name, tone = 'teal', size = 'md' }: {
  name: string;
  tone?: keyof typeof tones;
  size?: 'sm' | 'md' | 'lg';
}) {
  const dims = { sm: 'size-7 text-[11px]', md: 'size-9 text-sm', lg: 'size-10 text-sm' }[size];
  return (
    <span aria-hidden className={cn('inline-flex shrink-0 items-center justify-center rounded-full font-medium ring-1',
      tones[tone], dims)}>
      {initials(name)}
    </span>
  );
}
