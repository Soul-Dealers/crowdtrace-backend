import * as React from 'react';
import { cva, type VariantProps } from 'class-variance-authority';

import { cn } from '@/lib/utils';

const badgeVariants = cva(
  'inline-flex items-center rounded-md border border-transparent px-1.5 py-0.5 text-xs font-medium',
  {
    variants: {
      variant: {
        default: 'bg-[#14261d] text-success',
        secondary: 'bg-secondary text-text-secondary',
        destructive: 'bg-[#191818] text-destructive',
        outline: 'border-border text-text-secondary',
      },
    },
    defaultVariants: {
      variant: 'default',
    },
  },
);

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>, VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />;
}

export { Badge, badgeVariants };
