export function DetailList({ items }: { items: [string, React.ReactNode][] }) {
  return (
    <dl className="grid gap-3 sm:grid-cols-[12rem_1fr]">
      {items
        .filter(([, v]) => v !== null && v !== undefined && v !== '')
        .map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-sm text-muted-foreground">{k}</dt>
            <dd className="whitespace-pre-wrap">{v}</dd>
          </div>
        ))}
    </dl>
  );
}
