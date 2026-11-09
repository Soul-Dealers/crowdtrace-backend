export function DetailList({ items }: { items: [string, React.ReactNode][] }) {
  return (
    <dl className="grid gap-x-6 gap-y-3 text-sm sm:grid-cols-[10rem_1fr]">
      {items
        .filter(([, v]) => v !== null && v !== undefined && v !== '')
        .map(([k, v]) => (
          <div key={k} className="contents">
            <dt className="text-text-subtle">{k}</dt>
            <dd className="whitespace-pre-wrap break-words">{v}</dd>
          </div>
        ))}
    </dl>
  );
}
