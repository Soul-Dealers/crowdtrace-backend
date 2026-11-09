import { cn } from '@/lib/utils';

/** Black rounded card with an optional header row; the base of most blocks in the design. */
export function Panel({ title, meta, mono, className, bodyClassName, children }: {
  title?: React.ReactNode;
  meta?: React.ReactNode;
  mono?: boolean;
  className?: string;
  bodyClassName?: string;
  children: React.ReactNode;
}) {
  return (
    <section className={cn('overflow-hidden rounded-2xl border border-border bg-card', className)}>
      {title && (
        <header className="flex items-baseline justify-between gap-4 border-b border-border px-4 py-3">
          <h2 className={cn(mono ? 'font-mono text-sm font-semibold' : 'text-lg font-semibold')}>{title}</h2>
          {meta && <div className="text-sm text-muted-foreground">{meta}</div>}
        </header>
      )}
      <div className={bodyClassName}>{children}</div>
    </section>
  );
}
