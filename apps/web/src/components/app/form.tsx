import { Alert, AlertDescription } from '@/components/ui/alert';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';

type FieldProps = React.ComponentProps<typeof Input> & {
  label: string;
  name: string;
  hint?: string;
};

export function Field({ label, name, hint, ...props }: FieldProps) {
  return (
    <div className="grid content-start gap-1.5">
      <Label htmlFor={name}>{label}</Label>
      <Input
        id={name}
        name={name}
        aria-describedby={hint ? `${name}-hint` : undefined}
        {...props}
      />
      {hint && (
        <p id={`${name}-hint`} className="text-xs text-text-subtle">
          {hint}
        </p>
      )}
    </div>
  );
}

export function FormMessage({
  error,
  success,
}: {
  error?: string | null;
  success?: string | null;
}) {
  if (!error && !success) return null;
  return (
    <Alert variant={error ? 'destructive' : 'default'}>
      <AlertDescription>{error ?? success}</AlertDescription>
    </Alert>
  );
}

/** Native select styled to match shadcn inputs; keeps forms usable with FormData. */
export function NativeSelect({
  label,
  name,
  options,
  placeholder,
  format = (v) => v,
  ...props
}: React.ComponentProps<'select'> & {
  label: string;
  name: string;
  options: readonly string[];
  placeholder?: string;
  format?: (v: string) => string;
}) {
  return (
    <div className="grid gap-1.5">
      <Label htmlFor={name}>{label}</Label>
      <select
        id={name}
        name={name}
        className="h-11 rounded-xl border border-border bg-muted px-3 text-sm hover:border-white/15 focus-visible:border-brand/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/25"
        {...props}
      >
        {placeholder !== undefined && <option value="">{placeholder}</option>}
        {options.map((o) => (
          <option key={o} value={o}>
            {format(o)}
          </option>
        ))}
      </select>
    </div>
  );
}

export function formValues(form: HTMLFormElement) {
  return Object.fromEntries(new FormData(form)) as Record<string, string>;
}
