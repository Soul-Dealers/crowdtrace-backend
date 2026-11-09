/** Page title row used across the app: title, one-line description, optional actions on the right. */
export function PageHeader({
  title,
  description,
  actions,
  children,
}: {
  title: React.ReactNode;
  description?: React.ReactNode;
  actions?: React.ReactNode;
  children?: React.ReactNode;
}) {
  return (
    <header className="flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        {children}
        <h1 className="text-2xl font-semibold sm:text-3xl">{title}</h1>
        {description && <p className="text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </header>
  );
}

/** Table classes shared by admin lists; wrap the table in a Panel with overflow-x-auto. */
export const TABLE = {
  table: 'w-full min-w-[640px] text-left text-sm',
  head: 'border-b border-border text-xs uppercase tracking-wide text-text-subtle [&_th]:px-4 [&_th]:py-2.5 [&_th]:font-medium',
  row: 'border-b border-border last:border-0 hover:bg-white/[0.02] [&_td]:px-4 [&_td]:py-3',
};
