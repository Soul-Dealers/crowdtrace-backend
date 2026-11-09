import { ShieldCheck } from 'lucide-react';

export function AuthCard({
  title,
  description,
  children,
}: {
  title: string;
  description?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="mx-auto w-full max-w-md py-6 sm:py-12">
      <section className="rounded-2xl border border-border bg-card p-6 sm:p-8">
        <span className="mb-5 flex size-11 items-center justify-center rounded-xl bg-gradient-to-b from-brand-strong to-brand-deep shadow-[inset_0_1px_0_rgb(255_255_255/0.25)]">
          <ShieldCheck className="size-5 text-[#ffd9c4]" aria-hidden />
        </span>
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1.5 text-sm text-muted-foreground">{description}</p>}
        <div className="mt-6">{children}</div>
      </section>
      <p className="mt-4 px-2 text-center text-xs text-text-subtle">
        Someone in danger right now? Call 191 or 112 before anything else.
      </p>
    </div>
  );
}
