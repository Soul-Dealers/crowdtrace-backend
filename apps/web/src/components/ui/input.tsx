import * as React from 'react';

import { cn } from '@/lib/utils';

const Input = React.forwardRef<HTMLInputElement, React.ComponentProps<'input'>>(
  ({ className, type, ...props }, ref) => {
    return (
      <input
        type={type}
        className={cn(
          'flex h-11 w-full rounded-xl border border-border bg-muted px-3.5 py-1 text-base transition-colors placeholder:text-text-subtle hover:border-white/15 focus-visible:border-brand/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/25 disabled:cursor-not-allowed disabled:opacity-50 md:text-sm file:mr-3 file:rounded-lg file:border-0 file:bg-chip file:px-3 file:py-1 file:text-sm file:font-medium file:text-foreground',
          className,
        )}
        ref={ref}
        {...props}
      />
    );
  },
);
Input.displayName = 'Input';

export { Input };
